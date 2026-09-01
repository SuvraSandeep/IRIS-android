package com.iris.assistant;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * A transparent, no-history activity that appears over the lock screen, asks the
 * user to authenticate (fingerprint / PIN via the system prompt), and — once the
 * keyguard is dismissed — launches the target Intent it was given.
 *
 * IRIS uses this so voice-triggered app actions (WhatsApp, Maps, email, …) can
 * complete from the lock screen after a quick unlock, which Android requires for
 * showing another app's UI. IRIS never bypasses authentication.
 */
public class UnlockActivity extends Activity {

    public static final String EXTRA_TARGET = "com.iris.assistant.UNLOCK_TARGET";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over the lock screen and turn the screen on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final Intent target = getIntent() == null ? null : getIntent().getParcelableExtra(EXTRA_TARGET);

        KeyguardManager kg = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (kg != null && kg.isKeyguardLocked()) {
            kg.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { launchAndFinish(target); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
        } else {
            launchAndFinish(target);
        }
    }

    private void launchAndFinish(Intent target) {
        if (target != null) {
            try {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(target);
            } catch (Exception ignored) { }
        }
        finish();
    }
}
