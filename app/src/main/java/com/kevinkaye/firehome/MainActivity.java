package com.kevinkaye.firehome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

/** The home screen. Registered as a launcher so the Home button always lands here. */
public class MainActivity extends Activity {

    private static MainActivity current;
    private WebView web;
    private SamsungTv tv;
    private XboxLive xbox;
    private Routines routines;
    private Updater updater;
    private long lastUpdateCheck;

    /** Sign-in finished (code may be null if it was cancelled). */
    static void onXboxCode(String code) {
        MainActivity a = current;
        if (a == null || a.xbox == null) return;
        if (code == null) a.xbox.connect();
        else a.xbox.finishSignIn(code);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        tv = new SamsungTv(this, (state, detail) -> HomeWeb.pushStatus(web, state, detail));
        xbox = new XboxLive(this, (state, detail) -> HomeWeb.pushXbox(web, state, detail));
        routines = new Routines(this, tv, xbox, text -> HomeWeb.pushToast(web, text));
        updater = new Updater(this, (state, detail) -> HomeWeb.pushUpdate(web, state, detail));
        web = HomeWeb.create(this, false, tv, xbox, routines, updater, night -> {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = night ? 0.05f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        });
        setContentView(web);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while we're already in front: go back to the home view.
        if (web != null) web.evaluateJavascript("window.goHome && window.goHome()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeHijack.homeVisible();
        // Look for a new build at most every six hours.
        long now = System.currentTimeMillis();
        if (updater != null && now - lastUpdateCheck > 6 * 3600_000L) {
            lastUpdateCheck = now;
            updater.check();
        }
        if (web != null) web.evaluateJavascript("window.onResumed && window.onResumed()", null);
    }

    @Override
    public void onBackPressed() {
        // A launcher never closes; Back returns from the remote to the home view.
        if (web != null) web.evaluateJavascript("window.goHome && window.goHome()", null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onDestroy() {
        if (tv != null) tv.close();
        if (current == this) current = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
