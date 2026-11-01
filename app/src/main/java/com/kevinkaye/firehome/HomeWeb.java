package com.kevinkaye.firehome;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

/** Builds the WebView for the home, remote and night screens and wires up the native bridge. */
final class HomeWeb {

    interface NightListener {
        void onNight(boolean night);
    }

    private HomeWeb() {}

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static WebView create(Context context, boolean dream, SamsungTv tv, XboxLive xbox, NightListener listener) {
        WebView web = new WebView(context);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        Handler main = new Handler(Looper.getMainLooper());
        SharedPreferences prefs = context.getSharedPreferences("firehome", Context.MODE_PRIVATE);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public boolean isDream() { return dream; }

            @JavascriptInterface public String getCharacter() { return prefs.getString("character", "pebble"); }

            @JavascriptInterface public void setCharacter(String name) { prefs.edit().putString("character", name).apply(); }

            @JavascriptInterface public void setNight(boolean night) { main.post(() -> listener.onNight(night)); }

            @JavascriptInterface public String battery() {
                Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                JSONObject o = new JSONObject();
                try {
                    int level = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int plugged = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    o.put("pct", level < 0 ? -1 : Math.round(level * 100f / scale));
                    o.put("charging", plugged != 0);
                } catch (Exception ignored) {}
                return o.toString();
            }

            @JavascriptInterface public void openKindle() {
                main.post(() -> {
                    Intent i = context.getPackageManager().getLaunchIntentForPackage("com.amazon.kindle");
                    if (i == null) {
                        Toast.makeText(context, "Kindle app isn't installed", Toast.LENGTH_LONG).show();
                        return;
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                });
            }

            @JavascriptInterface public void openSettings() {
                main.post(() -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(i);
                    } catch (ActivityNotFoundException ignored) {}
                });
            }

            @JavascriptInterface public void tvConnect() { if (tv != null) tv.connect(); }

            @JavascriptInterface public void tvKey(String key) { if (tv != null) tv.send(key); }

            @JavascriptInterface public void tvPower() { if (tv != null) tv.power(); }

            @JavascriptInterface public void tvForget() { if (tv != null) { tv.forget(); tv.connect(); } }

            @JavascriptInterface public boolean tvConnected() { return tv != null && tv.isConnected(); }

            @JavascriptInterface public boolean xboxSignedIn() { return xbox != null && xbox.signedIn(); }

            @JavascriptInterface public void xboxSignIn() {
                main.post(() -> {
                    Intent i = new Intent(context, XboxSignIn.class);
                    if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                });
            }

            @JavascriptInterface public void xboxSignOut() { if (xbox != null) xbox.signOut(); }

            @JavascriptInterface public void xboxConnect() { if (xbox != null) xbox.connect(); }

            @JavascriptInterface public void xboxKey(String k) { if (xbox != null) xbox.key(k); }

            @JavascriptInterface public void xboxMedia(String c) { if (xbox != null) xbox.media(c); }

            @JavascriptInterface public void xboxText(String s) { if (xbox != null) xbox.text(s); }

            @JavascriptInterface public void xboxPower() { if (xbox != null) xbox.power(); }
        }, "Host");

        web.loadUrl("file:///android_asset/index.html" + (dream ? "#night" : ""));
        return web;
    }

    /** Push an Xbox status change into the page. */
    static void pushXbox(WebView web, String state, String detail) {
        if (web == null) return;
        String js = "window.onXboxStatus && window.onXboxStatus(" + JSONObject.quote(state) + "," + JSONObject.quote(detail == null ? "" : detail) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    /** Push a TV status change into the page. */
    static void pushStatus(WebView web, String state, String detail) {
        if (web == null) return;
        String js = "window.onTvStatus && window.onTvStatus(" + JSONObject.quote(state) + "," + JSONObject.quote(detail) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }
}
