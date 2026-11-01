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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
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
