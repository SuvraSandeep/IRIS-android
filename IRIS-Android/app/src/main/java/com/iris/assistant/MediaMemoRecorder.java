package com.iris.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;

/**
 * Records a timed voice memo to a compact .m4a (AAC) file and saves it to the media library
 * (Recordings/IRIS on Android 12+, Music/IRIS on 10–11, or the app's Music folder below 10).
 * Optionally pins the input to a specific microphone (phone / wired / Bluetooth).
 */
public final class MediaMemoRecorder {

    public interface Listener {
        void onStarted(String micName);
        void onSaved(String location, int seconds);
        void onError(String message);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stop;

    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri mediaUri;
    private String savedLocation = "your recordings";
    private long startedAt;
    private boolean recording;
    private Listener listener;

    public MediaMemoRecorder(Context c) { this.ctx = c.getApplicationContext(); }

    public boolean isRecording() { return recording; }

    /**
     * @param durationMs 0 = record until stop() is called
     * @param preferred  optional input device to pin (may be null → system default)
     * @param micName    human-readable mic name for the callback
     */
    public void start(int durationMs, AudioDeviceInfo preferred, String micName, Listener l) {
        this.listener = l;
        if (recording) { l.onError("Already recording."); return; }
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(ctx) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
            savedLocation = openOutput("IRIS_" + timestamp() + ".m4a");
            recorder.prepare();
            if (Build.VERSION.SDK_INT >= 28 && preferred != null) {
                try { recorder.setPreferredDevice(preferred); } catch (Exception ignored) { }
            }
            recorder.start();
            recording = true;
            startedAt = System.currentTimeMillis();
            main.post(() -> l.onStarted(micName));
            if (durationMs > 0) main.postDelayed(autoStop, durationMs);
        } catch (Exception e) {
            cleanup(false);
            main.post(() -> l.onError("Couldn't start recording: " + e.getMessage()));
        }
    }

    /** Stop and save (also fired automatically when the timer elapses). */
    public void stop() {
        main.removeCallbacks(autoStop);
        if (!recording) return;
        recording = false;
        int secs = (int) Math.max(1, Math.round((System.currentTimeMillis() - startedAt) / 1000.0));
        boolean ok = true;
        try { recorder.stop(); } catch (Exception e) { ok = false; }
        cleanup(ok);
        final boolean fok = ok;
        if (listener == null) return;
        if (fok) main.post(() -> listener.onSaved(savedLocation, secs));
        else main.post(() -> listener.onError("The recording was too short to save."));
    }

    private String openOutput(String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Build.VERSION.SDK_INT >= 31 ? "Recordings/IRIS" : "Music/IRIS";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH, rel);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            mediaUri = ctx.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (mediaUri == null) throw new IOException("Could not create the recording file.");
            pfd = ctx.getContentResolver().openFileDescriptor(mediaUri, "w");
            if (pfd == null) throw new IOException("Could not open the recording file.");
            recorder.setOutputFile(pfd.getFileDescriptor());
            return rel;
        }
        File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "IRIS");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, name);
        recorder.setOutputFile(out.getAbsolutePath());
        return "the IRIS folder";
    }

    private void cleanup(boolean success) {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        try {
            if (pfd != null) pfd.close();
        } catch (Exception ignored) { }
        pfd = null;
        if (Build.VERSION.SDK_INT >= 29 && mediaUri != null) {
            try {
                if (success) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(mediaUri, cv, null, null);
                } else {
                    ctx.getContentResolver().delete(mediaUri, null, null);
                }
            } catch (Exception ignored) { }
        }
        if (!success) mediaUri = null;
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
