package com.iris.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * Captures the screen via MediaProjection — either a single screenshot (PNG → Pictures/IRIS)
 * or a timed screen recording (MP4 + mic audio → Movies/IRIS). Runs as a mediaProjection
 * foreground service. The consent token (resultCode + data) is passed in by ScreenCaptureActivity.
 */
public final class ScreenCaptureService extends Service {
    public static final String ACTION_SHOT = "com.iris.assistant.SCREENSHOT";
    public static final String ACTION_REC = "com.iris.assistant.SCREENREC";
    public static final String ACTION_STOP = "com.iris.assistant.SCREENREC_STOP";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_SECONDS = "seconds";

    private static final String CHANNEL = "iris_capture";
    private static final int NOTIF = 0x5EE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri mediaUri;
    private boolean recording;
    private volatile boolean handled;
    private int seconds = 30;
    private final Runnable autoStop = this::stopRecording;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopRecording(); return START_NOT_STICKY; }

        startForegroundCompat(ACTION_REC.equals(action) ? "Recording the screen…" : "Capturing screen…");

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        seconds = Math.max(1, Math.min(600, intent.getIntExtra(EXTRA_SECONDS, 30)));
        if (data == null) { finishWith("I didn't get permission to capture the screen."); return START_NOT_STICKY; }

        bgThread = new HandlerThread("IRIS-Capture");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) { finishWith("I couldn't start screen capture."); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { cleanup(); }
            }, main);
        } catch (Throwable t) {
            finishWith("I couldn't start screen capture."); return START_NOT_STICKY;
        }

        if (ACTION_SHOT.equals(action)) bg.post(this::takeScreenshot);
        else bg.post(this::startRecording);
        return START_NOT_STICKY;
    }

    // ---- Screenshot ----------------------------------------------------------
    private void takeScreenshot() {
        try {
            int[] m = metrics();
            final int w = m[0], h = m[1], dpi = m[2];
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay("iris-shot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, bg);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    Bitmap bmp = imageToBitmap(image, w, h);
                    String loc = savePng(bmp, "IRIS_SHOT_" + timestamp() + ".png");
                    finishWith("Screenshot saved to " + loc + ".");
                } catch (Throwable t) {
                    finishWith("I couldn't take the screenshot.");
                } finally {
                    if (image != null) image.close();
                }
            }, bg);
            main.postDelayed(() -> finishWith("I couldn't capture the screen."), 3000);  // safety net
        } catch (Throwable t) {
            finishWith("I couldn't take the screenshot.");
        }
    }

    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(buffer);
        if (full.getWidth() == w) return full;
        Bitmap cropped = Bitmap.createBitmap(full, 0, 0, w, h);
        full.recycle();
        return cropped;
    }

    private String savePng(Bitmap bmp, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IRIS");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("insert failed");
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            cv.clear();
            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return "Pictures/IRIS";
        }
        java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "IRIS");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return "the IRIS folder";
    }

    // ---- Screen recording ----------------------------------------------------
    private void startRecording() {
        try {
            int[] m = metrics();
            int dpi = m[2];
            // Cap the encoded size to ~1080p so the H264 encoder accepts it.
            float scale = Math.min(1f, 1920f / Math.max(m[0], m[1]));
            int w = ((int) (m[0] * scale)) / 2 * 2;
            int h = ((int) (m[1] * scale)) / 2 * 2;

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            try { recorder.setAudioSource(MediaRecorder.AudioSource.MIC); } catch (Exception ignored) { }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            openMovieOutput("IRIS_SCREEN_" + timestamp() + ".mp4");
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            try { recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); } catch (Exception ignored) { }
            recorder.setVideoSize(w, h);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.setVideoFrameRate(30);
            recorder.prepare();

            virtualDisplay = projection.createVirtualDisplay("iris-rec", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, bg);
            recorder.start();
            recording = true;
            updateNotification("Recording the screen — tap to stop");
            main.postDelayed(autoStop, seconds * 1000L);
        } catch (Throwable t) {
            finishWith("I couldn't start the screen recording.");
        }
    }

    private void stopRecording() {
        main.removeCallbacks(autoStop);
        if (!recording) { cleanup(); return; }
        recording = false;
        boolean ok = true;
        try { if (recorder != null) recorder.stop(); } catch (Throwable e) { ok = false; }
        finishWith(ok ? "Saved the screen recording to Movies/IRIS." : "The screen recording was too short to save.");
    }

    private void openMovieOutput(String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/IRIS");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            mediaUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (mediaUri == null) throw new Exception("insert failed");
            pfd = getContentResolver().openFileDescriptor(mediaUri, "w");
            recorder.setOutputFile(pfd.getFileDescriptor());
        } else {
            java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "IRIS");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            recorder.setOutputFile(new java.io.File(dir, name).getAbsolutePath());
        }
    }

    // ---- Shared --------------------------------------------------------------
    private int[] metrics() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int density = getResources().getConfiguration().densityDpi;
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = wm.getCurrentWindowMetrics().getBounds();
                return new int[]{ b.width(), b.height(), density };
            }
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{ dm.widthPixels, dm.heightPixels, dm.densityDpi };
        } catch (Throwable t) {
            return new int[]{ 720, 1280, 320 };
        }
    }

    /** Finalise media, tell IRIS to speak the result, release everything, stop the service. */
    private void finishWith(String message) {
        if (handled) return;
        handled = true;
        boolean success = message != null && (message.startsWith("Screenshot") || message.startsWith("Saved"));
        if (Build.VERSION.SDK_INT >= 29 && mediaUri != null) {
            try {
                if (success) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(mediaUri, cv, null, null);
                } else {
                    getContentResolver().delete(mediaUri, null, null);
                }
            } catch (Exception ignored) { }
            mediaUri = null;
        }
        try {
            startService(new Intent(this, IrisListeningService.class)
                    .setAction(IrisListeningService.ACTION_CAPTURE_DONE)
                    .putExtra(IrisListeningService.EXTRA_TEXT, message));
        } catch (Exception ignored) { }
        cleanup();
    }

    private void cleanup() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) { }
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) { }
        imageReader = null;
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        projection = null;
        try { if (bgThread != null) bgThread.quitSafely(); } catch (Exception ignored) { }
        recording = false;
        stopForeground(true);
        stopSelf();
    }

    // ---- Notification --------------------------------------------------------
    private void startForegroundCompat(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen capture",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF, n);
        }
    }

    private void updateNotification(String text) {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, buildNotification(text));
        } catch (Exception ignored) { }
    }

    private Notification buildNotification(String text) {
        PendingIntent stop = PendingIntent.getService(this, 11,
                new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_iris).setContentTitle("IRIS screen capture")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
