package com.iris.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional accessibility service used ONLY to take a screenshot on demand
 * (performGlobalAction TAKE_SCREENSHOT, API 30+) — this avoids the MediaProjection
 * "Start recording/casting?" consent pop-up. It reads nothing from the screen.
 */
public final class IrisAccessibilityService extends AccessibilityService {
    static IrisAccessibilityService instance;

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    static boolean available() { return instance != null; }

    boolean takeScreenshotNow() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); } catch (Throwable t) { return false; }
        }
        return false;
    }
}
