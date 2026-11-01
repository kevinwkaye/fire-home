package com.kevinkaye.firehome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.WebView;

/**
 * Fire OS always sends the Home button to Amazon's launcher and won't let it be disabled.
 * This service watches for Amazon's launcher coming to the front and puts Fire Home there instead.
 *
 * Android delays app launches for about five seconds after Home is pressed, so while that runs
 * the service covers Amazon's launcher with an identical copy of the Fire Home screen. The real
 * activity takes over as soon as Android lets it start.
 */
public class HomeHijack extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.firelauncher";
    private static HomeHijack instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WebView cover;
    private boolean coverShown;
    private long last;
    private final Runnable hide = this::hideCover;

    /** Called by MainActivity when it reaches the front. */
    static void homeVisible() {
        HomeHijack s = instance;
        if (s != null) { s.main.removeCallbacks(s.hide); s.main.postDelayed(s.hide, 250); }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;
        long now = System.currentTimeMillis();
        if (now - last < 400) return;
        last = now;
        showCover();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        // Safety net: never leave the cover up for long if the activity doesn't arrive.
        main.removeCallbacks(hide);
        main.postDelayed(hide, 8000);
    }

    private void showCover() {
        if (wm == null || coverShown) return;
        if (cover == null) {
            cover = HomeWeb.create(this, false, null, null, night -> {});
        } else {
            cover.evaluateJavascript("window.goHome && window.goHome(); window.onResumed && window.onResumed()", null);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        cover.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        try {
            wm.addView(cover, lp);
            coverShown = true;
        } catch (Exception ignored) {}
    }

    private void hideCover() {
        if (!coverShown || cover == null) return;
        try { wm.removeView(cover); } catch (Exception ignored) {}
        coverShown = false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        hideCover();
        if (cover != null) { cover.destroy(); cover = null; }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
