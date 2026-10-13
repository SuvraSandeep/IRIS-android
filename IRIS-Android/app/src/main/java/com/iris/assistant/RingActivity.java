package com.iris.assistant;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Rings the phone loudly on a confirmed-urgent family message — the find-my-phone behaviour the
 * owner asked for, driven by an incoming text instead of a web console.
 *
 * The hard-won lesson: the actual RINGING must not depend on this Activity launching. The
 * responder decides on a background worker inside the notification listener, and Android 10+
 * forbids starting an Activity from the background. The previous version only played the alarm
 * inside {@link #onCreate}, so when the Activity could not launch — screen on but app in the
 * background, or a full-screen intent downgraded to a heads-up notification — nothing sounded,
 * which is exactly the "it only rings when the app is open" the owner reported.
 *
 * The fix: {@link #ring} starts the alarm sound and vibration DIRECTLY, from any context, with no
 * Activity required (audio playback is not subject to the background-activity-start limit). It
 * also posts a high-importance, DND-bypassing, full-screen-intent notification so the lock screen
 * lights up and the owner has a one-tap way in; and it still tries a direct startActivity as a
 * best-effort foreground path. The Activity, if and when it appears, is only the visual STOP
 * screen — it does not start a second ring.
 *
 * Two safety properties remain. It is only ever started by {@link AutoResponder} after a saved
 * family contact has sent the exact confirm word (or an explicit ring request), never on a first
 * message. And it stops itself after a bounded time even if nobody dismisses it, restoring the
 * previous volume and DND state, so a ring can never become a stuck siren.
 */
public final class RingActivity extends Activity {

    public static final String EXTRA_WHO = "com.iris.assistant.RING_WHO";
    /** When set, the Activity stops the ring immediately on launch (used by the Stop action). */
    public static final String EXTRA_STOP = "com.iris.assistant.RING_STOP";

    /** Rings for at most this long if not dismissed. A ring is an alert, not a siege. */
    private static final long MAX_RING_MS = 60_000L;

    /** Full-screen-intent channel, so the ring can launch from the background and over the lock. */
    private static final String RING_CHANNEL = "iris_ring_v1";
    private static final int RING_NOTIFICATION_ID = 0x1215;

    // ─── The ring itself, held statically so it plays and stops independently of the Activity ───
    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Ringtone sRingtone;
    private static Vibrator sVibrator;
    private static AudioManager sAudio;
    private static int sPreviousAlarmVolume = -1;
    private static int sPreviousFilter = -1;
    private static Runnable sAutoStop;

    /**
     * Make the phone ring, correctly, from anywhere — including a background worker.
     *
     * @param who the family member who asked, shown on the ring screen
     */
    public static void ring(Context context, String who) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        RING_CHANNEL, "IRIS urgent ring",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Rings the phone when family marks a message urgent");
                try { ch.setBypassDnd(true); } catch (Throwable ignored) { }
                nm.createNotificationChannel(ch);
            }

            // Start the audible ring NOW, not when (if) the Activity launches. This is the change
            // that makes it work from the background.
            startRinging(app);

            android.content.Intent full = new android.content.Intent(app, RingActivity.class);
            full.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            full.putExtra(EXTRA_WHO, who == null ? "" : who);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    app, 0, full, flags);

            // A one-tap stop, delivered as a notification action that opens the Activity with the
            // stop flag set, so the owner can silence it from the shade without unlocking.
            android.content.Intent stopIntent =
                    new android.content.Intent(app, RingActivity.class);
            stopIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            stopIntent.putExtra(EXTRA_STOP, true);
            android.app.PendingIntent stopPi = android.app.PendingIntent.getActivity(
                    app, 1, stopIntent, flags);

            if (nm != null) {
                android.app.Notification.Builder b =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new android.app.Notification.Builder(app, RING_CHANNEL)
                                : new android.app.Notification.Builder(app);
                android.app.Notification n = b
                        .setSmallIcon(R.drawable.ic_iris)
                        .setContentTitle("\u260E Urgent")
                        .setContentText((who == null || who.isEmpty() ? "Family" : who)
                                + " marked a message urgent.")
                        .setCategory(android.app.Notification.CATEGORY_CALL)
                        .setFullScreenIntent(pi, true)
                        .setContentIntent(pi)
                        .addAction(0, "Stop", stopPi)
                        .setOngoing(true)
                        .setAutoCancel(true)
                        .build();
                nm.notify(RING_NOTIFICATION_ID, n);
            }

            // Foreground fallback: if we happen to be allowed, the activity shows immediately.
            try { app.startActivity(full); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    /** Start (or restart) the alarm sound and vibration, independent of any Activity. */
    private static void startRinging(Context context) {
        synchronized (LOCK) {
            stopRinging(context); // clear any previous ring first
            try {
                NotificationManager nm = (NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && nm.isNotificationPolicyAccessGranted()) {
                    sPreviousFilter = nm.getCurrentInterruptionFilter();
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                }

                sAudio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (sAudio != null) {
                    sPreviousAlarmVolume = sAudio.getStreamVolume(AudioManager.STREAM_ALARM);
                    sAudio.setStreamVolume(AudioManager.STREAM_ALARM,
                            sAudio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                }

                Uri alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (alarm == null) {
                    alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                }
                sRingtone = RingtoneManager.getRingtone(context, alarm);
                if (sRingtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        sRingtone.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { sRingtone.setLooping(true); } catch (Throwable ignored) { }
                    }
                    sRingtone.play();
                }

                sVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (sVibrator != null && sVibrator.hasVibrator()) {
                    long[] pattern = {0, 800, 400};
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        sVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    } else {
                        sVibrator.vibrate(pattern, 0);
                    }
                }

                // Bounded auto-stop, so a ring nobody dismisses still ends and restores state.
                final Context app = context.getApplicationContext();
                sAutoStop = () -> stopRinging(app);
                HANDLER.postDelayed(sAutoStop, MAX_RING_MS);
            } catch (Throwable ignored) {
                // If setup fails midway, leave whatever started playing to the auto-stop/STOP.
            }
        }
    }

    /** Stop the ring and restore the volume, DND filter and notification. Safe to call twice. */
    static void stopRinging(Context context) {
        synchronized (LOCK) {
            if (sAutoStop != null) {
                HANDLER.removeCallbacks(sAutoStop);
                sAutoStop = null;
            }
            try {
                if (sRingtone != null && sRingtone.isPlaying()) sRingtone.stop();
            } catch (Throwable ignored) { }
            sRingtone = null;
            try { if (sVibrator != null) sVibrator.cancel(); } catch (Throwable ignored) { }
            sVibrator = null;
            try {
                if (sAudio != null && sPreviousAlarmVolume >= 0) {
                    sAudio.setStreamVolume(AudioManager.STREAM_ALARM, sPreviousAlarmVolume, 0);
                }
            } catch (Throwable ignored) { }
            sAudio = null;
            sPreviousAlarmVolume = -1;
            try {
                if (context != null) {
                    NotificationManager nm = (NotificationManager)
                            context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        if (sPreviousFilter > 0
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && nm.isNotificationPolicyAccessGranted()) {
                            nm.setInterruptionFilter(sPreviousFilter);
                        }
                        nm.cancel(RING_NOTIFICATION_ID);
                    }
                }
            } catch (Throwable ignored) { }
            sPreviousFilter = -1;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The Stop action routes here with the stop flag set: silence and leave.
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_STOP, false)) {
            stopRinging(getApplicationContext());
            finish();
            return;
        }

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

        // The ring is already playing (started by ring() from the background). If for some reason
        // it is not — e.g. the Activity was launched directly — start it now so the screen is
        // never silent.
        synchronized (LOCK) {
            if (sRingtone == null) startRinging(getApplicationContext());
        }
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

    private void stopAndFinish() {
        stopRinging(getApplicationContext());
        finish();
    }

    // Note: onDestroy deliberately does NOT stop the ring. The ring lives in static state so it
    // keeps sounding from the background even if this Activity is never shown or is torn down; the
    // STOP button, the notification's Stop action and the bounded auto-stop are what end it.
}
