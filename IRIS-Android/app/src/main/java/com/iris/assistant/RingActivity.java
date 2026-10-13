package com.iris.assistant;

import android.app.Activity;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Rings the phone loudly on a confirmed-urgent family message \u2014 the find-my-phone behaviour the
 * owner asked for, driven by an incoming text instead of a web console.
 *
 * It shows over the lock screen, turns the screen on, raises the alarm stream to full, plays the
 * alarm ringtone and vibrates, and \u2014 if notification-policy access has been granted \u2014 drops Do
 * Not Disturb for the duration so a silenced phone still sounds.
 *
 * Two safety properties are built in. It is only ever started by {@link AutoResponder} after a
 * saved family contact has sent the exact confirm word, never on a first message. And it stops
 * itself after a bounded time even if nobody dismisses it, restoring the previous volume and DND
 * state, so a ring can never become a stuck siren.
 */
public final class RingActivity extends Activity {

    public static final String EXTRA_WHO = "com.iris.assistant.RING_WHO";

    /** Rings for at most this long if not dismissed. A ring is an alert, not a siege. */
    private static final long MAX_RING_MS = 60_000L;

    private Ringtone ringtone;
    private Vibrator vibrator;
    private AudioManager audio;
    private int previousAlarmVolume = -1;
    private int previousFilter = -1;
    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable autoStop = this::stopAndFinish;

    /** Full-screen-intent channel, so the ring can launch from the background and over the lock. */
    private static final String RING_CHANNEL = "iris_ring_v1";

    /**
     * Make the phone ring, correctly, from anywhere.
     *
     * The responder decides on a background worker inside the notification listener, and Android
     * 10+ forbids starting an activity from the background \u2014 which is exactly why the ring only
     * fired when the app happened to be open. The fix is a high-importance notification carrying a
     * FULL-SCREEN intent: Android launches that over the lock screen even from the background,
     * the sanctioned path for alarms and calls. A direct startActivity is kept as a foreground
     * fallback.
     *
     * @param who the family member who asked, shown on the ring screen
     */
    public static void ring(Context context, String who) {
        if (context == null) return;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        RING_CHANNEL, "IRIS urgent ring",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Rings the phone when family marks a message urgent");
                try { ch.setBypassDnd(true); } catch (Throwable ignored) { }
                nm.createNotificationChannel(ch);
            }

            android.content.Intent full = new android.content.Intent(context, RingActivity.class);
            full.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            full.putExtra(EXTRA_WHO, who == null ? "" : who);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    context, 0, full, flags);

            if (nm != null) {
                android.app.Notification.Builder b =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new android.app.Notification.Builder(context, RING_CHANNEL)
                                : new android.app.Notification.Builder(context);
                android.app.Notification n = b
                        .setSmallIcon(R.drawable.ic_iris)
                        .setContentTitle("\u260E Urgent")
                        .setContentText((who == null || who.isEmpty() ? "Family" : who)
                                + " marked a message urgent.")
                        .setCategory(android.app.Notification.CATEGORY_CALL)
                        .setFullScreenIntent(pi, true)
                        .setAutoCancel(true)
                        .build();
                nm.notify(0x1215, n);
            }

            // Foreground fallback: if we happen to be allowed, the activity shows immediately.
            try { context.startActivity(full); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String who = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_WHO);
        setContentView(buildView(who == null || who.isEmpty() ? "A family member" : who));

        startRinging();
        handler.postDelayed(autoStop, MAX_RING_MS);
    }

    private View buildView(String who) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF14141E);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("\u260E Urgent");
        title.setTextSize(26f);
        title.setTextColor(0xFFFF3B6B);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(who + " marked a message urgent and asked me to reach you.\n\nI'm IRIS, "
                + ReplyTemplates.OWNER + "'s assistant.");
        body.setTextSize(16f);
        body.setTextColor(0xFFE6E6F0);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 32, 0, 48);
        root.addView(body);

        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setOnClickListener(v -> stopAndFinish());
        root.addView(stop);
        return root;
    }

    private void startRinging() {
        try {
            audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            // Drop Do Not Disturb for the ring, only if the owner granted policy access.
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && nm.isNotificationPolicyAccessGranted()) {
                previousFilter = nm.getCurrentInterruptionFilter();
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            }

            // Alarm stream to full, remembering the old level to restore it.
            if (audio != null) {
                previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
                audio.setStreamVolume(AudioManager.STREAM_ALARM,
                        audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }

            Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarm == null) alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), alarm);
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                ringtone.play();
            }

            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 800, 400};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Throwable ignored) {
            // If anything in the ring setup fails, the activity still shows its message, which is
            // better than crashing out of an urgent alert.
        }
    }

    private void stopAndFinish() {
        handler.removeCallbacks(autoStop);
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Throwable ignored) { }
        try { if (vibrator != null) vibrator.cancel(); } catch (Throwable ignored) { }
        try {
            if (audio != null && previousAlarmVolume >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            }
        } catch (Throwable ignored) { }
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && previousFilter > 0
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(previousFilter);
            }
        } catch (Throwable ignored) { }
        finish();
    }

    @Override
    protected void onDestroy() {
        stopAndFinish();
        super.onDestroy();
    }
}
