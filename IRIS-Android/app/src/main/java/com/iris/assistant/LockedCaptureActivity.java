package com.iris.assistant;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
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
 * NOTE: background/locked camera behaviour varies by OEM and Android version — this is the
 * best-effort path and may require the CAMERA permission to be pre-granted and battery
 * optimisation disabled for IRIS on some devices.
 */
public final class LockedCaptureActivity extends Activity {
    public static final String EXTRA_SECONDS = "seconds";
    public static final String EXTRA_FRONT = "front";

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private HandlerThread bgThread;
    private Handler bg;
    private final Handler main = new Handler();

    private ParcelFileDescriptor pfd;
    private Uri mediaUri;
    private String location = "your videos";
    private int seconds = 15;
    private boolean front;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        seconds = Math.max(1, Math.min(600, getIntent().getIntExtra(EXTRA_SECONDS, 15)));
        front = getIntent().getBooleanExtra(EXTRA_FRONT, false);

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
        label.setText("● Recording…");
        label.setTextColor(Color.RED);
        label.setTextSize(20);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(label, lp);
        setContentView(root);

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            done("I don't have camera permission, so I couldn't record.");
            return;
        }
        bgThread = new HandlerThread("IRIS-Camera");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        bg.post(this::openCamera);
    }

    private void openCamera() {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cameraManager, front);
            if (id == null) { done("I couldn't find that camera."); return; }
            //noinspection MissingPermission
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { camera = c; startRecording(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); }
                @Override public void onError(CameraDevice c, int error) { c.close(); done("The camera failed to open."); }
            }, bg);
        } catch (CameraAccessException | SecurityException e) {
            done("I couldn't open the camera.");
        }
    }

    private void startRecording() {
        try {
            setupRecorder();
            Surface recorderSurface = recorder.getSurface();
            camera.createCaptureSession(Collections.singletonList(recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(recorderSurface);
                                b.set(CaptureRequest.CONTROL_MODE,
                                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
                                s.setRepeatingRequest(b.build(), null, bg);
                                recorder.start();
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
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        openOutput("IRIS_VID_" + timestamp() + ".mp4");
        recorder.setVideoEncodingBitRate(8_000_000);
        recorder.setVideoFrameRate(30);
        recorder.setVideoSize(1280, 720);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOrientationHint(front ? 270 : 90);
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
            recorder.setOutputFile(pfd.getFileDescriptor());
            location = "Movies/IRIS";
        } else {
            java.io.File dir = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "IRIS");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            recorder.setOutputFile(new java.io.File(dir, name).getAbsolutePath());
            location = "the IRIS folder";
        }
    }

    private void stopRecording() {
        boolean ok = true;
        try { if (session != null) session.stopRepeating(); } catch (Exception ignored) { }
        try { if (recorder != null) recorder.stop(); } catch (Exception e) { ok = false; }
        done(ok ? ("Saved a " + seconds + " second video to " + location + ".")
                : "The video was too short to save.");
    }

    /** Release everything, finalise/cancel the MediaStore entry, tell the service, and finish. */
    private void done(String message) {
        if (finished) return;
        finished = true;
        boolean success = message != null && message.startsWith("Saved");
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        camera = null;
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;
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
        }
        try { if (bgThread != null) bgThread.quitSafely(); } catch (Exception ignored) { }
        try {
            startService(new Intent(this, IrisListeningService.class)
                    .setAction(IrisListeningService.ACTION_CAPTURE_DONE)
                    .putExtra(IrisListeningService.EXTRA_TEXT, message));
        } catch (Exception ignored) { }
        main.post(this::finish);
    }

    private static String pickCamera(CameraManager cm, boolean front) throws CameraAccessException {
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == want) return id;
        }
        return fallback;
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    @Override protected void onDestroy() {
        done(null);
        super.onDestroy();
    }
}
