package com.kevinkaye.firehome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One-tap routines that drive the TV and Xbox together. */
final class Routines {

    interface Toast {
        void show(String text);
    }

    /** Lofi Girl's channel: we look up whichever live stream is running right now. */
    private static final String LOFI_CHANNEL_LIVE = "https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow/live";
    private static final String LOFI_FALLBACK = "jfKfPfyJRdk";

    private final SharedPreferences prefs;
    private final SamsungTv tv;
    private final XboxLive xbox;
    private final Toast toast;
    private final ExecutorService run = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();

    Routines(Context ctx, SamsungTv tv, XboxLive xbox, Toast toast) {
        this.prefs = ctx.getSharedPreferences("firehome", Context.MODE_PRIVATE);
        this.tv = tv;
        this.xbox = xbox;
        this.toast = toast;
    }

    int lofiVolume() { return prefs.getInt("lofi_volume", 8); }

    int profilePosition() { return prefs.getInt("lofi_profile_pos", 1); }

    /** Kindle has just opened: put Lofi Girl on the TV at a quiet level. */
    void lofi() {
        if (!prefs.getBoolean("lofi_on", true)) return;
        run.execute(() -> {
            toast.show("Starting Lofi Girl on the TV");
            boolean wasOn = tv.isConnected();
            if (!tv.ensureOn(30_000)) { toast.show("Couldn't reach the TV for lofi"); return; }
            if (!wasOn) sleep(3000); // let a freshly woken TV settle before sending keys
            tv.setVolume(lofiVolume());
            String id = liveVideoId();
            if (!tv.playYouTube(id)) { toast.show("The TV didn't accept the YouTube request"); return; }
            if (prefs.getBoolean("lofi_profile", true)) {
                // Wait until the TV says YouTube is actually running, then for the profile screen to draw.
                long end = System.currentTimeMillis() + 25_000;
                while (System.currentTimeMillis() < end && !tv.youTubeRunning()) sleep(700);
                sleep(wasOn ? 4500 : 6500);
                // Go to the first profile, step right to Kevin's, and choose it.
                tv.repeat("KEY_LEFT", 6, 250);
                tv.repeat("KEY_RIGHT", Math.max(0, profilePosition() - 1), 400);
                sleep(300);
                tv.send("KEY_ENTER");
            }
        });
    }

    void stopLofi() {
        run.execute(() -> {
            if (!tv.isConnected() && !tv.ensureOn(6_000)) return;
            tv.stopYouTube();
        });
    }

    void testVolume() {
        run.execute(() -> {
            if (!tv.ensureOn(15_000)) { toast.show("Couldn't reach the TV"); return; }
            toast.show("Setting the TV to volume " + lofiVolume());
            tv.setVolume(lofiVolume());
        });
    }

    /** TV on, over to the Xbox, Xbox awake. */
    void tellyTime() {
        run.execute(() -> {
            toast.show("Telly time: waking everything up");
            if (xbox.signedIn()) xbox.wake();
            if (!tv.ensureOn(25_000)) { toast.show("Couldn't reach the TV"); return; }
            sleep(1500);
            tv.send("KEY_HDMI1");
        });
    }

    /** Xbox off, TV off. The page shows the night clock. */
    void bedtime() {
        run.execute(() -> {
            if (xbox.signedIn()) xbox.off();
            if (tv.isConnected()) tv.send("KEY_POWER");
        });
    }

    void tvApp(String name) {
        run.execute(() -> {
            if (!tv.ensureOn(20_000)) { toast.show("Couldn't reach the TV"); return; }
            if (!tv.launchApp(name)) toast.show("Couldn't find " + name + " on the TV");
        });
    }

    private String liveVideoId() {
        try {
            Request req = new Request.Builder().url(LOFI_CHANNEL_LIVE)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                    .build();
            try (Response r = http.newCall(req).execute()) {
                String html = r.body() == null ? "" : r.body().string();
                Matcher m = Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})").matcher(html);
                if (m.find()) return m.group(1);
                m = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return LOFI_FALLBACK;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
