package com.iris.assistant;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;

/**
 * Records a short video with Camera2 + MediaRecorder, then finishes. Declared with
 * showWhenLocked / turnScreenOn so it can capture over the lock screen. No preview surface
 * is used (records straight to the recorder surface) to keep the pipeline simple and robust.
 *
 * NOTE: background/locked camera behaviour varies by OEM and Android version â€” this is the
 * best-effort path and may require the CAMERA permission to be pre-granted and battery
 * optimisation disabled for IRIS on some devices.
 */
public final class LockedCaptureActivity extends Activity {
    public static final String EXTRA_SECONDS = "seconds";
    public static final String EXTRA_FRONT = "front";
    /** "video" (default) or "photo". */
    public static final String EXTRA_MODE = "mode";
    private static final String CHANNEL = "iris_capture";
    private static final int REC_NOTIF = 0xC0DF;
    static volatile LockedCaptureActivity instance;

    /** Stop the active camera recording early (from the notification's Stop button). */
    static void stopActive() {
        LockedCaptureActivity a = instance;
        if (a != null) a.main.post(a::stopRecording);
    }

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bg;
    private final Handler main = new Handler();

    private ParcelFileDescriptor pfd;
    private Uri mediaUri;
    private String location = "your videos";
    private int seconds = 15;
    private boolean front;
    private boolean photoMode;
    private volatile boolean finished;
    private boolean opened;
    private CameraCharacteristics characteristics;
    private long startedAt;
    private java.io.File legacyFile;
    private TextView statusLabel;
    private volatile boolean recording;
    private volatile boolean usedFallbackLens;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (instance != null && !instance.finished) { finished = true; finish(); return; }
        seconds = Math.max(1, Math.min(3600, getIntent().getIntExtra(EXTRA_SECONDS, 60)));
        front = getIntent().getBooleanExtra(EXTRA_FRONT, false);
        photoMode = "photo".equals(getIntent().getStringExtra(EXTRA_MODE));

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        TextView label = new TextView(this);
        statusLabel = label;
        label.setText(photoMode ? "\u25CF Preparing photo\u2026" : "\u25CF Preparing video\u2026");
        label.setTextColor(Color.RED);
        label.setTextSize(20);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(label, lp);
        android.widget.Button stop = new android.widget.Button(this);
        stop.setText(photoMode ? "Cancel photo" : "Stop and save");
        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        stopLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        root.addView(stop, stopLp);
        stop.setOnClickListener(v -> {
            if (photoMode) done("Photo cancelled."); else stopRecording();
        });
        setContentView(root);

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            done(photoMode ? "I don't have camera permission, so I couldn't take a photo."
                    : "I don't have camera permission, so I couldn't record.");
            return;
        }
        bgThread = new HandlerThread("IRIS-Camera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    @Override protected void onResume() {
        super.onResume();
        if (opened || finished || bg == null) return;
        opened = true;
        instance = this;
        startService(new Intent(this, IrisListeningService.class)
                .setAction(IrisListeningService.ACTION_CAPTURE_STARTED));
        // While-in-use camera access begins only after the lock-screen activity is visible.
        bg.post(this::openCamera);
        if (photoMode) statusLabel.setText("Capturing photoâ€¦");
        main.postDelayed(() -> {
            if (!finished && !recording) done("The camera did not become ready. Check camera access and try again.");
        }, 12000);
    }

    private void openCamera() {
        if (finished) return;
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cameraManager, front);
            if (id == null) {
                // Requested lens is absent. Use another one, but never silently: say so.
                id = anyCamera(cameraManager);
                if (id == null) { done("This device has no usable camera."); return; }
                final String wanted = front ? "front" : "back";
                main.post(() -> {
                    if (statusLabel != null) statusLabel.setText("No " + wanted + " camera — using the other lens");
                });
                usedFallbackLens = true;
            }
            characteristics = cameraManager.getCameraCharacteristics(id);
            //noinspection MissingPermission
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (finished) { c.close(); return; }
                    camera = c;
                    if (photoMode) takePhoto(); else startRecording();
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); done("Camera disconnected."); }
                @Override public void onError(CameraDevice c, int error) { c.close(); done("The camera failed to open."); }
            }, bg);
        } catch (Exception e) {
            done("I couldn't open the camera.");
        }
    }

    /** Single still-frame capture (TEMPLATE_STILL_CAPTURE), saved to Pictures/IRIS. */
    private void takePhoto() {
        try {
            android.hardware.camera2.params.StreamConfigurationMap config = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size size = chooseSize(config == null ? null : config.getOutputSizes(ImageFormat.JPEG), 1920L * 1080);
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;
                    // A frame can still arrive after the capture was cancelled or the screen
                    // closed — drop it rather than publishing an unwanted photo.
                    if (finished) { img.close(); img = null; return; }
                    byte[] bytes = new byte[img.getPlanes()[0].getBuffer().remaining()];
                    img.getPlanes()[0].getBuffer().get(bytes);
                    img.close();
                    img = null;
                    if (finished) return;
                    savePhoto(bytes);
                } catch (Throwable t) {
                    done("I couldn't save the photo.");
                } finally {
                    if (img != null) img.close();
                }
            }, bg);
            Surface target = imageReader.getSurface();
            camera.createCaptureSession(Collections.singletonList(target),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (finished) { s.close(); return; }
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                                b.addTarget(target);
                                b.set(CaptureRequest.CONTROL_MODE,
                                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
                                b.set(CaptureRequest.JPEG_ORIENTATION, captureOrientation());
                                s.capture(b.build(), null, bg);
                            } catch (Exception e) {
                                done("I couldn't take the photo.");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            done("The camera session failed.");
                        }
                    }, bg);
            main.postDelayed(() -> { if (!finished) done("The camera timed out."); }, 8000);
        } catch (Exception e) {
            done("I couldn't take the photo.");
        }
    }

    private void savePhoto(byte[] jpeg) {
        String name = "IRIS_PHOTO_" + timestamp() + ".jpg";
        try {
            if (jpeg == null || jpeg.length == 0) throw new java.io.IOException("Empty photo");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IRIS");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                mediaUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (mediaUri == null) throw new Exception("insert failed");
                try (java.io.OutputStream os = getContentResolver().openOutputStream(mediaUri)) {
                    if (os == null) throw new java.io.IOException("No photo output stream");
                    os.write(jpeg);
                    os.flush();
                }
                location = "Pictures/IRIS";
            } else {
                java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "IRIS");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                legacyFile = new java.io.File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(legacyFile)) {
                    os.write(jpeg);
                }
                location = "the IRIS folder";
            }
            done("Saved a photo to " + location + ".");
        } catch (Exception e) {
            done("I couldn't save the photo.");
        }
    }

    private void startRecording() {
        try {
            setupRecorder();
            Surface recorderSurface = recorder.getSurface();
            camera.createCaptureSession(Collections.singletonList(recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (finished) { s.close(); return; }
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(recorderSurface);
                                b.set(CaptureRequest.CONTROL_MODE,
                                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
                                s.setRepeatingRequest(b.build(), null, bg);
                                recorder.start();
                                recording = true;
                                startedAt = android.os.SystemClock.elapsedRealtime();
                                main.post(() -> statusLabel.setText("Recording video â€” tap Stop and save to finish"));
                                main.post(LockedCaptureActivity.this::postRecordingNotification);
                                main.postDelayed(LockedCaptureActivity.this::stopRecording, seconds * 1000L);
                            } catch (Exception e) {
                                done("I couldn't start the recording.");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            done("The camera session failed.");
                        }
                    }, bg);
        } catch (Exception e) {
            done("I couldn't start recording video.");
        }
    }

    private void setupRecorder() throws Exception {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Microphone permission required for video audio");
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        openOutput("IRIS_VID_" + timestamp() + ".mp4");
        recorder.setVideoEncodingBitRate(8_000_000);
        recorder.setVideoFrameRate(30);
        android.hardware.camera2.params.StreamConfigurationMap config = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size size = chooseSize(config == null ? null : config.getOutputSizes(MediaRecorder.class), 1280L * 720);
        recorder.setVideoSize(size.getWidth(), size.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOrientationHint(captureOrientation());
        recorder.prepare();
    }

    private void openOutput(String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/IRIS");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            mediaUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (mediaUri == null) throw new Exception("insert failed");
            pfd = getContentResolver().openFileDescriptor(mediaUri, "w");
            if (pfd == null) throw new java.io.IOException("No video output descriptor");
            recorder.setOutputFile(pfd.getFileDescriptor());
            location = "Movies/IRIS";
        } else {
            java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "IRIS");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            legacyFile = new java.io.File(dir, name);
            recorder.setOutputFile(legacyFile.getAbsolutePath());
            location = "the IRIS folder";
        }
    }

    private void stopRecording() {
        if (bg != null && android.os.Looper.myLooper() != bg.getLooper()) { bg.post(this::stopRecording); return; }
        if (finished) return;
        if (!recording) { done("Recording cancelled before it started."); return; }
        recording = false;
        boolean ok = true;
        try { if (session != null) session.stopRepeating(); } catch (Exception ignored) { }
        try { if (recorder != null) recorder.stop(); } catch (Exception e) { ok = false; }
        long actualSeconds = Math.max(1, (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000);
        done(ok ? ("Saved a " + actualSeconds + " second video to " + location + ".")
                : "The video was too short to save.");
    }

    /** Release everything, finalise/cancel the MediaStore entry, tell the service, and finish. */
    /** Show a "â¹ Stop" notification so the user can end the recording early (works on the watch/lock screen). */
    private void postRecordingNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording",
                        NotificationManager.IMPORTANCE_LOW));
            }
            PendingIntent stop = PendingIntent.getService(this, 21,
                    new Intent(this, IrisListeningService.class).setAction(IrisListeningService.ACTION_STOP_VIDEO),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Notification n = b.setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle("IRIS is recording video")
                    .setContentText("Tap Stop to finish now").setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "\u23F9 Stop", stop).build())
                    .build();
            if (nm != null) nm.notify(REC_NOTIF, n);
        } catch (Throwable ignored) { }
    }

    private void done(String message) {
        if (bg != null && android.os.Looper.myLooper() != bg.getLooper()) { bg.post(() -> done(message)); return; }
        if (finished) return;
        finished = true;
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(REC_NOTIF); } catch (Throwable ignored) { }
        boolean success = message != null && message.startsWith("Saved");
        String resultMessage = message;
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) { }
        imageReader = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        camera = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
        if (Build.VERSION.SDK_INT >= 29 && mediaUri != null) {
            try {
                if (success) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (getContentResolver().update(mediaUri, cv, null, null) != 1)
                        throw new java.io.IOException("Could not publish capture");
                    // Publication only counts if the entry really has content.
                    if (sizeOf(mediaUri) <= 0) throw new java.io.IOException("Published capture is empty");
                } else {
                    getContentResolver().delete(mediaUri, null, null);
                }
            } catch (Exception error) {
                // Never claim success when the gallery entry didn't land.
                success = false;
                resultMessage = photoMode
                        ? "I took the photo but couldn't save it to your gallery."
                        : "I recorded it but couldn't save it to your gallery.";
                try { getContentResolver().delete(mediaUri, null, null); } catch (Exception ignored) { }
                mediaUri = null;
            }
        }
        if (!success && legacyFile != null) {
            //noinspection ResultOfMethodCallIgnored
            legacyFile.delete();
        }
        if (success && usedFallbackLens) {
            resultMessage = resultMessage + " I used the other lens \u2014 the "
                    + (front ? "front" : "back") + " camera isn't available.";
        }
        try { if (bgThread != null) bgThread.quitSafely(); } catch (Exception ignored) { }
        try {
            startService(new Intent(this, IrisListeningService.class)
                    .setAction(IrisListeningService.ACTION_CAPTURE_DONE)
                    .putExtra(IrisListeningService.EXTRA_TEXT, resultMessage));
        } catch (Exception ignored) { }
        main.post(this::finish);
    }

    /** Bytes actually stored for a MediaStore entry (0 when empty/unreadable). */
    private long sizeOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{ MediaStore.MediaColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) { }
        // Fall back to opening the stream when SIZE isn't reported.
        try (ParcelFileDescriptor d = getContentResolver().openFileDescriptor(uri, "r")) {
            return d == null ? 0 : d.getStatSize();
        } catch (Throwable ignored) { }
        return 0;
    }

    /** The requested lens, or null when the device has none at all. */
    private static String pickCamera(CameraManager cm, boolean front) throws CameraAccessException {
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : cm.getCameraIdList()) {
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == want) return id;
        }
        return null;
    }

    /** Any usable lens, used only after telling the user the requested one is missing. */
    private static String anyCamera(CameraManager cm) throws CameraAccessException {
        String[] ids = cm.getCameraIdList();
        return ids == null || ids.length == 0 ? null : ids[0];
    }

    /**
     * Correct JPEG / MediaRecorder rotation for THIS lens and THIS device.
     *
     * Two things were previously assumed and are now measured:
     *  - the device's natural orientation is portrait (false on many tablets), and
     *  - both lenses need the same hint (false: the front sensor is mirrored, so the
     *    display rotation is added rather than subtracted).
     */
    private int captureOrientation() {
        int sensor = 0;
        boolean isFront = front;
        try {
            Integer s = characteristics == null ? null
                    : characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (s != null) sensor = s;
            Integer facing = characteristics == null ? null
                    : characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) isFront = facing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (Throwable ignored) { }
        int deg = displayRotationDegrees();
        return isFront ? (sensor + deg) % 360 : (sensor - deg + 360) % 360;
    }

    /** Actual display rotation in degrees (never assumed to be 0). */
    private int displayRotationDegrees() {
        try {
            int r;
            if (Build.VERSION.SDK_INT >= 30) {
                r = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
            } else {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                r = wm == null || wm.getDefaultDisplay() == null
                        ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
            }
            switch (r) {
                case Surface.ROTATION_90:  return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default:                   return 0;
            }
        } catch (Throwable t) { return 0; }
    }

    private static android.util.Size chooseSize(android.util.Size[] sizes, long targetArea) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("No supported camera output size");
        android.util.Size best = sizes[0];
        long distance = Long.MAX_VALUE;
        for (android.util.Size s : sizes) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - targetArea);
            if (d < distance) { best = s; distance = d; }
        }
        return best;
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    @Override protected void onNewIntent(Intent intent) {
        // A second launch (e.g. from a notification) must NOT restart/kill an active recording.
        super.onNewIntent(intent);
    }

    @Override protected void onDestroy() {
        if (bg != null) bg.post(() -> {
            if (!finished) {
                if (recording) stopRecording();
                else done("The camera closed before capture completed.");
            }
        });
        else if (!finished) done("Camera closed.");
        super.onDestroy();
    }

    @Override protected void onStop() {
        super.onStop();
        // Leaving the visible lock-screen camera must not keep a hidden camera session alive.
        if (bg != null) bg.post(() -> {
            if (!finished) {
                if (recording) stopRecording(); else done("Camera capture cancelled when its screen closed.");
            }
        });
    }
}

