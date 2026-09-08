package com.iris.assistant;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Transparent trampoline that asks the user for the one-time MediaProjection consent, then hands
 * the token to ScreenCaptureService to do the actual screenshot / screen recording.
 */
public final class ScreenCaptureActivity extends Activity {
    public static final String EXTRA_OP = "op";           // "shot" or "rec"
    public static final String EXTRA_SECONDS = "seconds";
    private static final int REQ = 4210;

    private String op;
    private int seconds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        op = getIntent().getStringExtra(EXTRA_OP);
        seconds = getIntent().getIntExtra(EXTRA_SECONDS, 30);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            report("I can't capture the screen on this device.");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            boolean shot = !"rec".equals(op);
            Intent svc = new Intent(this, ScreenCaptureService.class)
                    .setAction(shot ? ScreenCaptureService.ACTION_SHOT : ScreenCaptureService.ACTION_REC)
                    .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, (Intent) data)
                    .putExtra(ScreenCaptureService.EXTRA_SECONDS, seconds);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            } catch (Exception e) {
                report("I couldn't start screen capture.");
            }
        } else {
            report("Screen capture was cancelled.");
        }
        finish();
    }

    private void report(String message) {
        try {
            startService(new Intent(this, IrisListeningService.class)
                    .setAction(IrisListeningService.ACTION_CAPTURE_DONE)
                    .putExtra(IrisListeningService.EXTRA_TEXT, message));
        } catch (Exception ignored) { }
    }
}
