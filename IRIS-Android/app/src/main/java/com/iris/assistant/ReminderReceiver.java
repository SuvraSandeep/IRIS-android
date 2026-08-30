package com.iris.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Fires when a scheduled reminder is due and posts a heads-up notification.
 * Scheduled by IrisListeningService via AlarmManager.
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String EXTRA_TEXT = "com.iris.assistant.REMINDER_TEXT";
    private static final String CHANNEL_ID = "iris_reminders_v1";

    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (text == null || text.isEmpty()) text = "You asked me to remind you.";

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "IRIS Reminders", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Reminders you asked IRIS to set");
            nm.createNotificationChannel(channel);
        }

        // Tapping opens IRIS
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, flags);

        Notification n = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_iris)
                .setContentTitle("\u23F0 Reminder")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();

        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }
}
