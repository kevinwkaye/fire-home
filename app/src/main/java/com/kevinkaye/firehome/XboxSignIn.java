package com.kevinkaye.firehome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Shows Microsoft's own sign-in page and hands the returned code to Fire Home. */
public class XboxSignIn extends Activity {

    private boolean done;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return check(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                check(url);
            }
        });
        setContentView(web);
        web.loadUrl(XboxLive.AUTHORIZE_URL);
    }

    private boolean check(String url) {
        if (done || url == null || !url.startsWith(XboxLive.REDIRECT)) return false;
        Uri u = Uri.parse(url);
        String code = u.getQueryParameter("code");
        done = true;
        if (code != null) MainActivity.onXboxCode(code);
        else MainActivity.onXboxCode(null);
        finish();
        return true;
    }
}
