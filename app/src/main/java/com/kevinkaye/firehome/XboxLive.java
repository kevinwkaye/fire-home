package com.kevinkaye.firehome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Controls an Xbox through Microsoft's console-control service (the one the Xbox app's remote uses).
 * Sign-in is Microsoft's own page; we keep only the refresh token, then trade it for Xbox Live tokens.
 */
final class XboxLive {

    interface Status {
        /** state: signedout | connecting | ready | off | noconsole | error */
        void onXbox(String state, String detail);
    }

    static final String CLIENT_ID = "000000004C12AE6F";
    static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    static final String REDIRECT = "https://login.live.com/oauth20_desktop.srf";
    static final String AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf?client_id=" + CLIENT_ID
            + "&response_type=code&display=touch&scope=" + SCOPE.replace(":", "%3A")
            + "&redirect_uri=" + REDIRECT.replace(":", "%3A").replace("/", "%2F");

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String XCCS = "https://xccs.xboxlive.com";

    private final SharedPreferences prefs;
    private final Status status;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    private final String sessionId = UUID.randomUUID().toString();

    private String authHeader;
    private long authExpires;
    private String consoleId;
    private String consoleName = "Xbox";
    private boolean consoleOn;

    XboxLive(Context ctx, Status status) {
        this.prefs = ctx.getSharedPreferences("firehome", Context.MODE_PRIVATE);
        this.status = status;
    }

    boolean signedIn() { return prefs.getString("xbox_refresh", null) != null; }

    /** Finish sign-in with the code from Microsoft's redirect. */
    void finishSignIn(String code) {
        io.execute(() -> {
            try {
                status.onXbox("connecting", "");
                JSONObject t = postForm(new FormBody.Builder()
                        .add("client_id", CLIENT_ID).add("code", code)
                        .add("grant_type", "authorization_code").add("redirect_uri", REDIRECT)
                        .add("scope", SCOPE).build());
                saveLive(t);
                authHeader = null;
                refreshConsoles();
            } catch (Exception e) {
                status.onXbox("error", "Sign-in failed: " + e.getMessage());
            }
        });
    }

    void signOut() {
        prefs.edit().remove("xbox_refresh").remove("xbox_console").apply();
        authHeader = null;
        consoleId = null;
        status.onXbox("signedout", "");
    }

    /** Look up the console and its power state. */
    void connect() {
        if (!signedIn()) { status.onXbox("signedout", ""); return; }
        io.execute(() -> {
            try {
                status.onXbox("connecting", "");
                refreshConsoles();
            } catch (Exception e) {
                status.onXbox("error", e.getMessage());
            }
        });
    }

    void key(String keyType) {
        JSONObject p = new JSONObject();
        try { p.put("keyType", keyType); } catch (Exception ignored) {}
        command("Shell", "InjectKey", p);
    }

    void text(String s) {
        JSONObject p = new JSONObject();
        try { p.put("replacementString", s); } catch (Exception ignored) {}
        command("Shell", "InjectString", p);
    }

    void media(String cmd) { command("Media", cmd, new JSONObject()); }

    void wake() {
        command("Power", "WakeUp", new JSONObject());
        consoleOn = true;
    }

    void off() {
        command("Power", "TurnOff", new JSONObject());
        consoleOn = false;
    }

    void power() {
        if (consoleOn) {
            command("Power", "TurnOff", new JSONObject());
            consoleOn = false;
            status.onXbox("off", consoleName);
        } else {
            command("Power", "WakeUp", new JSONObject());
            consoleOn = true;
            status.onXbox("ready", consoleName);
        }
    }

    // --- internals ---

    private void command(String type, String command, JSONObject param) {
        if (!signedIn()) { status.onXbox("signedout", ""); return; }
        io.execute(() -> {
            try {
                if (consoleId == null) refreshConsoles();
                if (consoleId == null) return;
                JSONObject body = new JSONObject();
                body.put("destination", "Xbox");
                body.put("type", type);
                body.put("command", command);
                body.put("sessionId", sessionId);
                body.put("sourceId", "com.microsoft.smartglass");
                body.put("parameters", new JSONArray().put(param));
                body.put("linkedXboxId", consoleId);
                Request req = xccs(new Request.Builder().url(XCCS + "/commands")
                        .post(RequestBody.create(body.toString(), JSON))).build();
                try (Response r = http.newCall(req).execute()) {
                    if (r.code() == 401) { authHeader = null; }
                    if (!r.isSuccessful()) status.onXbox("error", "Xbox didn't respond (" + r.code() + ")");
                }
            } catch (Exception e) {
                status.onXbox("error", e.getMessage());
            }
        });
    }

    private void refreshConsoles() throws Exception {
        Request req = xccs(new Request.Builder()
                .url(XCCS + "/lists/devices?queryCurrentDevice=false&includeStorageDevices=true")).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new Exception("console list " + r.code());
            JSONObject o = new JSONObject(r.body().string());
            JSONArray list = o.optJSONArray("result");
            if (list == null || list.length() == 0) { consoleId = null; status.onXbox("noconsole", ""); return; }
            JSONObject c = list.getJSONObject(0);
            consoleId = c.optString("id");
            consoleName = c.optString("name", "Xbox");
            consoleOn = "On".equalsIgnoreCase(c.optString("powerState"));
            prefs.edit().putString("xbox_console", consoleId).apply();
            status.onXbox(consoleOn ? "ready" : "off", consoleName);
        }
    }

    private Request.Builder xccs(Request.Builder b) throws Exception {
        return b.header("Authorization", auth())
                .header("x-xbl-contract-version", "4")
                .header("skillplatform", "RemoteManagement")
                .header("Accept-Language", "en-GB");
    }

    /** Build (or reuse) the "XBL3.0 x=uhs;token" header. */
    private String auth() throws Exception {
        if (authHeader != null && System.currentTimeMillis() < authExpires - 60_000) return authHeader;
        String refresh = prefs.getString("xbox_refresh", null);
        if (refresh == null) throw new Exception("not signed in");
        JSONObject live = postForm(new FormBody.Builder()
                .add("client_id", CLIENT_ID).add("refresh_token", refresh)
                .add("grant_type", "refresh_token").add("redirect_uri", REDIRECT)
                .add("scope", SCOPE).build());
        saveLive(live);
        String access = live.getString("access_token");

        String userToken;
        try {
            userToken = userToken(access);
        } catch (Exception first) {
            userToken = userToken("t=" + access);
        }

        JSONObject xstsBody = new JSONObject()
                .put("RelyingParty", "http://xboxlive.com")
                .put("TokenType", "JWT")
                .put("Properties", new JSONObject()
                        .put("SandboxId", "RETAIL")
                        .put("UserTokens", new JSONArray().put(userToken)));
        JSONObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody, "1");
        String token = xsts.getString("Token");
        String uhs = xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
        authHeader = "XBL3.0 x=" + uhs + ";" + token;
        try {
            authExpires = Instant.parse(xsts.getString("NotAfter").replaceAll("\\.\\d+Z$", "Z")).toEpochMilli();
        } catch (Exception e) {
            authExpires = System.currentTimeMillis() + 3_600_000;
        }
        return authHeader;
    }

    private String userToken(String ticket) throws Exception {
        JSONObject body = new JSONObject()
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT")
                .put("Properties", new JSONObject()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", ticket));
        return postJson("https://user.auth.xboxlive.com/user/authenticate", body, "1").getString("Token");
    }

    private void saveLive(JSONObject t) {
        String refresh = t.optString("refresh_token", "");
        if (!refresh.isEmpty()) prefs.edit().putString("xbox_refresh", refresh).apply();
    }

    private JSONObject postForm(FormBody form) throws Exception {
        Request req = new Request.Builder().url("https://login.live.com/oauth20_token.srf").post(form).build();
        try (Response r = http.newCall(req).execute()) {
            String s = r.body().string();
            if (!r.isSuccessful()) throw new Exception("login " + r.code());
            return new JSONObject(s);
        }
    }

    private JSONObject postJson(String url, JSONObject body, String contract) throws Exception {
        Request req = new Request.Builder().url(url)
                .header("x-xbl-contract-version", contract)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON)).build();
        try (Response r = http.newCall(req).execute()) {
            String s = r.body().string();
            if (!r.isSuccessful()) throw new Exception("xbox auth " + r.code());
            return new JSONObject(s);
        }
    }
}
