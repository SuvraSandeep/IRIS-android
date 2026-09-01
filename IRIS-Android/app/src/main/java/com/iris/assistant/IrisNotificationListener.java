package com.iris.assistant;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Captures posted notifications so IRIS can read them back by voice.
 * Requires the user to grant "Notification access" in system settings
 * (Settings → Apps → Special access → Notification access).
 *
 * Filters out ongoing/system-noise notifications (foreground services,
 * media transport, IRIS's own listening notification).
 */
public class IrisNotificationListener extends NotificationListenerService {

    private static IrisNotificationListener instance;

    @Override public void onListenerConnected() {
        instance = this;
        // Seed with notifications already showing in the shade
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
            }
        } catch (Exception ignored) { }
    }
    @Override public void onListenerDisconnected() { if (instance == this) instance = null; }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    /** Dismiss all clearable system notifications, if access is granted. Returns true if attempted. */
    public static boolean dismissAll() {
        if (instance == null) return false;
        try { instance.cancelAllNotifications(); return true; }
        catch (Throwable t) { return false; }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            String pkg = sbn.getPackageName();
            if (pkg == null) return;
            // Skip our own notifications
            if (pkg.equals(getPackageName())) return;

            Notification n = sbn.getNotification();
            if (n == null) return;
            // Skip ongoing (foreground service, persistent) and group summaries
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

            Bundle extras = n.extras;
            if (extras == null) return;
            String title = charSeq(extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = charSeq(extras.getCharSequence(Notification.EXTRA_TEXT));
            if ((text == null || text.isEmpty())) {
                CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (big != null) text = big.toString();
            }

            String appLabel = resolveAppLabel(pkg);
            NotificationStore.add(getApplicationContext(), pkg, appLabel, title, text, sbn.getPostTime());
        } catch (Exception ignored) { }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { }

    private String resolveAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String charSeq(CharSequence cs) {
        return cs == null ? null : cs.toString().trim();
    }
}
