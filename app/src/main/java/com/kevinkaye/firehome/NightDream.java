package com.kevinkaye.firehome;

import android.service.dreams.DreamService;
import android.webkit.WebView;

/** The dimmed night clock Android shows while the tablet charges and the screen times out. */
public class NightDream extends DreamService {

    private WebView web;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        web = HomeWeb.create(this, true, null, night -> setScreenBright(!night));
        setContentView(web);
    }

    @Override
    public void onDetachedFromWindow() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDetachedFromWindow();
    }
}
