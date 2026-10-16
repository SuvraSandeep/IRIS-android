package com.iris.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.TimeZone;

/**
 * The immediate, silent alert posted when a message is caught being taken back.
 *
 * WHY SILENT: this is an archive marker, not something that needs attention in the moment. A
 * deletion caught at three in the morning must not buzz the phone, so the channel is created at
 * IMPORTANCE_LOW with sound and vibration explicitly disabled. The owner asked for both an instant
 * alert and an on-demand answer; this is the instant half.
 *
 * WHY THE TEXT IS VAGUE: the alert names the app and the sender but never the message body. It
 * appears on the lock screen, where a notification about an intercepted private message would
 * otherwise expose the very content the archive is meant to keep for the owner alone. The body is
 * only ever shown inside the app, after unlock.
 */
public final class ArchiveAlerts {

    private static final String CHANNEL_ID = "iris_archive_deletions";
    private static final int NOTIFICATION_ID = 0x1D15;

    private static final String FLAG_CHANNEL_ID = "iris_urgent_flags";
    private static final int FLAG_NOTIFICATION_ID = 0x1F1A;

    private ArchiveAlerts() { }

    /**
     * Post the owner-facing alert when a sender accepts IRIS's "shall I flag this urgent?" offer.
     *
     * Unlike {@link #deletionCaught}, this one is MEANT to be noticed — flagging urgent is only
     * useful if it actually reaches the owner — so its channel is created at IMPORTANCE_HIGH. It
     * still names only the sender and app, never the message body, because it can show on the lock
     * screen. This is the action that makes "Done, I've flagged this" true; before this, the reply
     * was an empty promise.
     */
    public static void urgentFlag(Context context, String appLabel, String sender, long whenMs) {
        if (context == null) return;
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            ensureFlagChannel(manager);

            String who = sender == null || sender.trim().isEmpty() ? "Someone" : sender.trim();
            who = ContactResolver.display(context, who);
            String body = FlagAlertText.body(who, appLabel).replace("{owner}", ReplyTemplates.OWNER);

            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_ARCHIVE, true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent tap = PendingIntent.getActivity(context, FLAG_NOTIFICATION_ID, open, flags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, FLAG_CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(FlagAlertText.title())
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            body + "\n" + DeletedMessageRules.formatTime(whenMs, TimeZone.getDefault())))
                    .setWhen(whenMs)
                    .setAutoCancel(true)
                    .setContentIntent(tap);
            if (Build.VERSION.SDK_INT < 26) {
                builder.setPriority(Notification.PRIORITY_HIGH);
            }
            builder.setVisibility(Notification.VISIBILITY_PRIVATE);

            manager.notify(FLAG_NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // An alert is best-effort; never let it break the reply path that already succeeded.
        }
    }

    private static void ensureFlagChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel channel = new NotificationChannel(FLAG_CHANNEL_ID,
                    "Flagged urgent", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(
                    "Heads-up when someone asks IRIS to flag a message as urgent for you.");
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) { }
    }



    /**
     * Post (or update) the alert for a freshly detected deletion.
     *
     * A single notification id is reused, so several deletions replace one another rather than
     * stacking into a pile the owner has to clear. The archive is the record; this is just a nudge.
     */
    public static void deletionCaught(Context context, String appLabel, String sender,
                                      long whenMs, DeletedMessageRules.Confidence confidence) {
        if (context == null || confidence == null
                || confidence == DeletedMessageRules.Confidence.NONE) return;
        try {
            AppSettings settings = new AppSettings(context);
            if (!settings.archiveAlerts()) return;

            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            ensureChannel(manager);

            boolean confirmed = confidence == DeletedMessageRules.Confidence.CONFIRMED;
            String who = sender == null || sender.trim().isEmpty() ? "Someone" : sender.trim();
            // A bare number read "Someone +919876543210 deleted a message". Resolved here too so
            // the alert names a person when the number is in contacts.
            who = ContactResolver.display(context, who);
            String app = appLabel == null || appLabel.trim().isEmpty() ? "an app" : appLabel.trim();

            String title = confirmed ? "Caught one" : "Suspicious disappearance";
            String body = confirmed
                    ? who + " deleted a message on " + app + ". Too slow. I already have it."
                    : who + "'s message on " + app + " vanished before you read it. I have a theory.";

            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_ARCHIVE, true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            body + "\n" + DeletedMessageRules.formatTime(whenMs, TimeZone.getDefault())))
                    .setWhen(whenMs)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(tap);
            if (Build.VERSION.SDK_INT < 26) {
                // Pre-channel releases take the silencing from the builder instead.
                builder.setPriority(Notification.PRIORITY_LOW);
                builder.setDefaults(0);
                builder.setSound(null);
            }
            // Private visibility: the alert is readable once unlocked, not on the lock screen.
            builder.setVisibility(Notification.VISIBILITY_PRIVATE);

            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // An alert is a convenience. Never let it break capture, which already succeeded by
            // the time this is called.
        }
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Caught deleting", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    "Silent notice when a message is caught being deleted or unsent by its sender.");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) { }
    }

    /** Clear the alert, used once the owner has opened the archive. */
    public static void clear(Context context) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) { }
    }
}
