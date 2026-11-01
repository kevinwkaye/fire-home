package com.kevinkaye.firehome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS always sends the Home button to Amazon's launcher and won't let it be disabled.
 * This service watches for Amazon's launcher coming to the front and puts Fire Home there instead.
 */
public class HomeHijack extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.firelauncher";
    private long last;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !AMAZON_LAUNCHER.contentEquals(pkg)) return;
        long now = System.currentTimeMillis();
        if (now - last < 400) return;
        last = now;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {}
}
