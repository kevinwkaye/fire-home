package com.kevinkaye.firehome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** One-tap routines that drive the TV and Xbox together. */
final class Routines {

    interface Toast {
        void show(String text);
    }

    /** The Flip Fella TV app (kwkaye84-coder/flip-fella-tv), which plays lofi radio itself. */
    private static final String TV_CLOCK_APP = "FlpFella01.FlipFellaTV";

    private final SharedPreferences prefs;
    private final SamsungTv tv;
    private final XboxLive xbox;
    private final Toast toast;
    private final ExecutorService run = Executors.newSingleThreadExecutor();

    Routines(Context ctx, SamsungTv tv, XboxLive xbox, Toast toast) {
        this.prefs = ctx.getSharedPreferences("firehome", Context.MODE_PRIVATE);
        this.tv = tv;
        this.xbox = xbox;
        this.toast = toast;
    }

    int lofiVolume() { return prefs.getInt("lofi_volume", 8); }


    /** Kindle has just opened: open Flip Fella on the TV, which plays lofi radio, at a quiet level. */
    void lofi() {
        if (!prefs.getBoolean("lofi_on", true)) return;
        run.execute(() -> {
            toast.show("Opening Flip Fella on the TV");
            boolean wasOn = tv.isConnected();
            if (!tv.ensureOn(30_000)) { toast.show("Couldn't reach the TV"); return; }
            if (!wasOn) sleep(3000); // let a freshly woken TV settle before sending keys
            tv.setVolume(lofiVolume());
            if (!tv.launchAppId(TV_CLOCK_APP) && !tv.launchApp("Flip Fella")) {
                toast.show("Couldn't open Flip Fella on the TV");
            }
        });
    }

    void stopLofi() {
        run.execute(() -> {
            if (!tv.isConnected() && !tv.ensureOn(6_000)) return;
            tv.closeAppId(TV_CLOCK_APP);
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

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
