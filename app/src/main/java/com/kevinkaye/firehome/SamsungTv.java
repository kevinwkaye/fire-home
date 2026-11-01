package com.kevinkaye.firehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Talks to a 2016+ Samsung Tizen TV over its local remote-control WebSocket (port 8001).
 * Finds the TV with SSDP, falls back to scanning the local /24, remembers the address and
 * MAC, and sends Wake-on-LAN packets to try to switch it on from standby.
 */
final class SamsungTv {

    interface Status {
        /** state: searching | connecting | allow | connected | notfound | offline */
        void onStatus(String state, String detail);
    }

    private static final String APP_NAME = "Fire Home";
    private static final int PORT = 8001;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Status status;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final Map<String, String> apps = new LinkedHashMap<>();
    private WebSocket socket;
    private volatile boolean connected;
    private volatile boolean connecting;

    SamsungTv(Context ctx, Status status) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("firehome", Context.MODE_PRIVATE);
        this.status = status;
    }

    boolean isConnected() { return connected; }

    /** Connect (finding the TV first if needed). Safe to call repeatedly. */
    void connect() {
        if (connected || connecting) return;
        connecting = true;
        io.execute(() -> {
            String ip = prefs.getString("tv_ip", null);
            if (ip == null || !probe(ip)) {
                status.onStatus("searching", "");
                ip = discover();
                if (ip == null) {
                    connecting = false;
                    status.onStatus("notfound", "");
                    return;
                }
                prefs.edit().putString("tv_ip", ip).apply();
            }
            open(ip);
        });
    }

    void send(String key) {
        if (!connected || socket == null) {
            connect();
            return;
        }
        try {
            JSONObject params = new JSONObject();
            params.put("Cmd", "Click");
            params.put("DataOfCmd", key);
            params.put("Option", "false");
            params.put("TypeOfRemote", "SendRemoteKey");
            JSONObject msg = new JSONObject();
            msg.put("method", "ms.remote.control");
            msg.put("params", params);
            socket.send(msg.toString());
        } catch (Exception ignored) {}
    }

    /** Power: KEY_POWER when the TV is reachable, otherwise wake it up and reconnect. */
    void power() {
        if (connected) {
            send("KEY_POWER");
            return;
        }
        io.execute(() -> {
            String mac = prefs.getString("tv_mac", null);
            if (mac != null) {
                for (int i = 0; i < 3; i++) {
                    wake(mac);
                    sleep(300);
                }
            }
        });
        // Give the TV a few seconds to bring its network up, then try to connect.
        io.execute(() -> sleep(4000));
        connect();
    }

    void forget() {
        prefs.edit().remove("tv_ip").remove("tv_token").apply();
        close();
    }

    void close() {
        connected = false;
        connecting = false;
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
    }

    // --- connection ---

    private void open(String ip) {
        status.onStatus("connecting", ip);
        String name = Base64.encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String url = "ws://" + ip + ":" + PORT + "/api/v2/channels/samsung.remote.control?name=" + name;
        String token = prefs.getString("tv_token", null);
        if (token != null) url += "&token=" + token;
        Request req = new Request.Builder().url(url).build();
        socket = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                // The TV may now be showing its "Allow" prompt; wait for ms.channel.connect.
                status.onStatus("allow", "");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    String event = o.optString("event");
                    if ("ms.channel.connect".equals(event)) {
                        JSONObject data = o.optJSONObject("data");
                        if (data != null && data.has("token")) {
                            prefs.edit().putString("tv_token", data.optString("token")).apply();
                        }
                        connected = true;
                        connecting = false;
                        status.onStatus("connected", "");
                        io.execute(() -> rememberMac(ip));
                    } else if ("ed.installedApp.get".equals(event)) {
                        JSONObject data = o.optJSONObject("data");
                        org.json.JSONArray list = data == null ? null : data.optJSONArray("data");
                        if (list != null) synchronized (apps) {
                            apps.clear();
                            for (int i = 0; i < list.length(); i++) {
                                JSONObject a = list.optJSONObject(i);
                                if (a != null) apps.put(a.optString("name"), a.optString("appId"));
                            }
                        }
                    } else if ("ms.channel.unauthorized".equals(event)) {
                        connected = false;
                        connecting = false;
                        status.onStatus("denied", "");
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                lost();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                lost();
            }
        });
    }

    private void lost() {
        boolean was = connected;
        connected = false;
        connecting = false;
        socket = null;
        status.onStatus("offline", was ? "lost" : "");
    }

    // --- discovery ---

    private boolean probe(String ip) {
        return info(ip, 600) != null;
    }

    private JSONObject info(String ip, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + PORT + "/api/v2/").openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout * 2);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            return o.has("device") ? o : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void rememberMac(String ip) {
        JSONObject o = info(ip, 1500);
        if (o == null) return;
        JSONObject d = o.optJSONObject("device");
        if (d == null) return;
        String mac = d.optString("wifiMac", "");
        if (!mac.isEmpty()) prefs.edit().putString("tv_mac", mac).apply();
    }

    private String discover() {
        String ip = ssdp();
        if (ip != null && probe(ip)) return ip;
        return scan();
    }

    private String ssdp() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("firehome");
        lock.acquire();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2500);
            String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n";
            byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) s.send(new DatagramPacket(b, b.length, group, 1900));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 3000;
            Pattern loc = Pattern.compile("(?i)LOCATION:\\s*http://([0-9.]+)");
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                Matcher m = loc.matcher(reply);
                if (m.find()) return m.group(1);
                return p.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        } finally {
            lock.release();
        }
        return null;
    }

    private String scan() {
        String self = localIp();
        if (self == null) return null;
        String prefix = self.substring(0, self.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicReference<String> found = new AtomicReference<>();
        List<Integer> hosts = new ArrayList<>();
        for (int i = 1; i < 255; i++) hosts.add(i);
        Collections.shuffle(hosts);
        for (int h : hosts) {
            String ip = prefix + h;
            if (ip.equals(self)) continue;
            pool.execute(() -> {
                if (found.get() == null && probe(ip)) found.compareAndSet(null, ip);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return found.get();
    }

    private static String localIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }


    // --- routines support (call these off the main thread) ---

    String ip() { return prefs.getString("tv_ip", null); }

    /** Make sure the TV is on and connected, waking it if needed. Blocks up to timeoutMs. */
    boolean ensureOn(long timeoutMs) {
        if (connected) return true;
        long end = System.currentTimeMillis() + timeoutMs;
        String mac = prefs.getString("tv_mac", null);
        long nextTry = 0;
        while (System.currentTimeMillis() < end) {
            if (connected) return true;
            if (System.currentTimeMillis() >= nextTry) {
                if (mac != null) wake(mac);
                connect();
                nextTry = System.currentTimeMillis() + 3000;
            }
            sleep(400);
        }
        return connected;
    }

    /** Send a key several times with a gap, e.g. to set the volume. */
    void repeat(String key, int times, long gapMs) {
        for (int i = 0; i < times && connected; i++) {
            send(key);
            sleep(gapMs);
        }
    }

    /** Set the volume to a known level: all the way down, then up n steps. */
    void setVolume(int level) {
        repeat("KEY_VOLDOWN", 45, 70);
        sleep(300);
        repeat("KEY_VOLUP", Math.max(0, level), 160);
    }

    /** Open an installed TV app whose name contains the given text (case-insensitive). */
    boolean launchApp(String nameContains) {
        if (!connected || socket == null) return false;
        String appId = findApp(nameContains);
        if (appId == null) {
            requestApps();
            for (int i = 0; i < 10 && appId == null; i++) { sleep(300); appId = findApp(nameContains); }
        }
        if (appId == null) return false;
        String ip = ip();
        try {
            Request req = new Request.Builder().url("http://" + ip + ":" + PORT + "/api/v2/applications/" + appId)
                    .post(RequestBody.create(new byte[0], null)).build();
            try (Response r = http.newCall(req).execute()) {
                if (r.isSuccessful()) return true;
            }
        } catch (Exception ignored) {}
        try {
            JSONObject data = new JSONObject().put("appId", appId).put("action_type", "NATIVE_LAUNCH");
            JSONObject msg = new JSONObject().put("method", "ms.channel.emit")
                    .put("params", new JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data));
            socket.send(msg.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestApps() {
        try {
            JSONObject msg = new JSONObject().put("method", "ms.channel.emit")
                    .put("params", new JSONObject().put("event", "ed.installedApp.get").put("to", "host"));
            if (socket != null) socket.send(msg.toString());
        } catch (Exception ignored) {}
    }

    private String findApp(String nameContains) {
        String want = nameContains.toLowerCase(Locale.ROOT);
        synchronized (apps) {
            for (Map.Entry<String, String> e : apps.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).contains(want)) return e.getValue();
            }
        }
        return null;
    }

    /** Start a YouTube video on the TV using DIAL (the same thing phone casting uses). */
    boolean playYouTube(String videoId) {
        String ip = ip();
        if (ip == null) return false;
        String base = prefs.getString("tv_dial", "http://" + ip + ":8080/ws/apps/");
        if (dial(base, videoId)) return true;
        String found = findDialUrl(ip);
        if (found != null && !found.equals(base)) {
            prefs.edit().putString("tv_dial", found).apply();
            return dial(found, videoId);
        }
        return false;
    }

    private boolean dial(String base, String videoId) {
        if (!base.endsWith("/")) base += "/";
        try {
            Request req = new Request.Builder().url(base + "YouTube")
                    .post(RequestBody.create("v=" + videoId, MediaType.get("text/plain; charset=utf-8"))).build();
            try (Response r = http.newCall(req).execute()) {
                return r.code() == 201 || r.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Ask the TV for its DIAL address via SSDP. */
    private String findDialUrl(String ip) {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("firehome-dial");
        lock.acquire();
        String location = null;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2500);
            String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                    + "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
            byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName("239.255.255.250"), 1900));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 3000;
            Pattern loc = Pattern.compile("(?i)LOCATION:\\s*(\\S+)");
            while (System.currentTimeMillis() < end && location == null) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                if (!p.getAddress().getHostAddress().equals(ip)) continue;
                Matcher m = loc.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
                if (m.find()) location = m.group(1);
            }
        } catch (Exception ignored) {
        } finally {
            lock.release();
        }
        if (location == null) return null;
        try (Response r = http.newCall(new Request.Builder().url(location).build()).execute()) {
            String app = r.header("Application-URL");
            return app;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Wake-on-LAN ---

    private void wake(String mac) {
        try {
            String[] hex = mac.split("[:-]");
            if (hex.length != 6) return;
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex[i], 16);
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
            for (int i = 6; i < pkt.length; i += 6) System.arraycopy(m, 0, pkt, i, 6);
            List<String> targets = new ArrayList<>();
            targets.add("255.255.255.255");
            String self = localIp();
            if (self != null) targets.add(self.substring(0, self.lastIndexOf('.') + 1) + "255");
            String ip = prefs.getString("tv_ip", null);
            if (ip != null) targets.add(ip);
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (String t : targets) {
                    InetAddress a = InetAddress.getByName(t);
                    s.send(new DatagramPacket(pkt, pkt.length, a, 9));
                    s.send(new DatagramPacket(pkt, pkt.length, a, 7));
                }
            }
        } catch (Exception ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
