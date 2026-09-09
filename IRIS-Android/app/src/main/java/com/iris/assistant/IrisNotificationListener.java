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

    private static volatile IrisNotificationListener instance;

    /** Active notifications only; cached history must never be used to send a reply. */
    public static StatusBarNotification[] activeForReply() {
        IrisNotificationListener service = instance;
        if (service == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] items = service.getActiveNotifications();
            return items == null ? new StatusBarNotification[0] : items;
        } catch (Exception e) { return new StatusBarNotification[0]; }
    }

    public static android.app.Notification.Action replyAction(StatusBarNotification item) {
        if (item == null || item.getNotification().actions == null) return null;
        if ((item.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        for (Notification.Action action : item.getNotification().actions) {
            if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
            if (android.os.Build.VERSION.SDK_INT >= 28
                    && action.getSemanticAction() != Notification.Action.SEMANTIC_ACTION_REPLY) continue;
            // Multiple text fields are ambiguous; support only an explicit single reply field.
            if (action.getRemoteInputs().length == 1
                    && action.getRemoteInputs()[0].getAllowFreeFormInput()) return action;
        }
        return null;
    }

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
