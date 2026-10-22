package com.iris.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import androidx.core.content.ContextCompat;

import java.util.Set;

/**
 * Coordinates shake detection, headset/media button triggers, and Bluetooth device state.
 */
public class HeadsetSensorCoordinator {

    public interface TriggerCallback {
        void onTrigger(String source);
        boolean canTriggerShake();
    }

    private final Context context;
    private final Handler handler;
    private final TriggerCallback callback;
    private final AppSettings settings;

    private SensorManager sensorManager;
    private SensorEventListener shakeListener;
    private long lastShakeAt = 0;

    private MediaSession mediaSession;
    private int triggerPressRunCount = 0;
    private long lastTriggerPressAt = 0;
    private Runnable headsetHookPassThrough;

    public HeadsetSensorCoordinator(Context context, Handler handler, AppSettings settings, TriggerCallback callback) {
        this.context = context.getApplicationContext();
        this.handler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.settings = settings != null ? settings : new AppSettings(context);
        this.callback = callback;
    }

    public synchronized void setupTriggers() {
        teardownTriggers();
        try {
            if (settings.shakeToWake()) {
                sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
                Sensor accel = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accel != null) {
                    shakeListener = new SensorEventListener() {
                        @Override
                        public void onSensorChanged(SensorEvent e) {
                            float x = e.values[0] / 9.81f, y = e.values[1] / 9.81f, z = e.values[2] / 9.81f;
                            double g = Math.sqrt(x * x + y * y + z * z);
                            if (g > 2.7) {
                                long now = System.currentTimeMillis();
                                if (now - lastShakeAt > 3000 && (callback == null || callback.canTriggerShake())) {
                                    lastShakeAt = now;
                                    handler.post(() -> {
                                        if (callback != null) callback.onTrigger("shake");
                                    });
                                }
                            }
                        }

                        @Override
                        public void onAccuracyChanged(Sensor s, int a) {}
                    };
                    sensorManager.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI);
                    IrisSensorUsageRegistry.begin(IrisSensorUsageRegistry.Hardware.ACCELEROMETER, "Shake trigger");
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (settings.headsetTrigger()) {
                final int wantKeyCode = triggerKeyCodeFor(settings.triggerButton());
                final int wantPresses = settings.triggerPressCount();
                mediaSession = new MediaSession(context, "IRIS");
                mediaSession.setCallback(new MediaSession.Callback() {
                    @Override
                    public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                        KeyEvent ke = mediaButtonIntent != null ? mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) : null;
                        if (ke != null && ke.getAction() == KeyEvent.ACTION_UP && ke.getKeyCode() == wantKeyCode) {
                            long now = System.currentTimeMillis();
                            if (now - lastTriggerPressAt < 700) {
                                triggerPressRunCount++;
                            } else {
                                triggerPressRunCount = 1;
                            }
                            lastTriggerPressAt = now;
                            if (headsetHookPassThrough != null) {
                                handler.removeCallbacks(headsetHookPassThrough);
                            }
                            if (triggerPressRunCount >= wantPresses) {
                                triggerPressRunCount = 0;
                                handler.post(() -> {
                                    if (callback != null) callback.onTrigger("headset");
                                });
                            } else {
                                final long pressTime = now;
                                headsetHookPassThrough = () -> {
                                    if (lastTriggerPressAt == pressTime) {
                                        triggerPressRunCount = 0;
                                        super.onMediaButtonEvent(mediaButtonIntent);
                                    }
                                };
                                handler.postDelayed(headsetHookPassThrough, 700);
                            }
                            return true;
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent);
                    }
                });
                reassertMediaSessionPriority();
            }
        } catch (Throwable ignored) {}
    }

    public static int triggerKeyCodeFor(String triggerButton) {
        if ("next".equals(triggerButton)) return KeyEvent.KEYCODE_MEDIA_NEXT;
        if ("previous".equals(triggerButton)) return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        return KeyEvent.KEYCODE_HEADSETHOOK;
    }

    public synchronized void reassertMediaSessionPriority() {
        if (mediaSession == null) return;
        try {
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PAUSED, 0, 0f)
                    .build();
            mediaSession.setPlaybackState(ps);
            mediaSession.setActive(true);
        } catch (Throwable ignored) {}
    }

    public synchronized void teardownTriggers() {
        try {
            if (sensorManager != null && shakeListener != null) {
                sensorManager.unregisterListener(shakeListener);
            }
        } catch (Throwable ignored) {}
        IrisSensorUsageRegistry.end(IrisSensorUsageRegistry.Hardware.ACCELEROMETER);
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Throwable ignored) {}
        if (headsetHookPassThrough != null) {
            handler.removeCallbacks(headsetHookPassThrough);
        }
        triggerPressRunCount = 0;
        lastTriggerPressAt = 0;
    }

    public String connectedBluetoothDeviceName() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ba = bm != null ? bm.getAdapter() : android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba == null || !ba.isEnabled()) return null;
            return connectedBluetoothDeviceName(ba);
        } catch (Throwable t) {
            return null;
        }
    }

    public String connectedBluetoothDeviceName(android.bluetooth.BluetoothAdapter ba) {
        if (ba == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            int a2dp = ba.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP);
            int headset = ba.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET);
            if (a2dp != android.bluetooth.BluetoothProfile.STATE_CONNECTED && headset != android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                return null;
            }
            Set<android.bluetooth.BluetoothDevice> bonded = ba.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return null;
            for (android.bluetooth.BluetoothDevice d : bonded) {
                try {
                    java.lang.reflect.Method m = d.getClass().getMethod("isConnected");
                    Boolean connected = (Boolean) m.invoke(d);
                    if (connected != null && connected) return d.getName();
                } catch (Throwable ignored) {}
            }
            for (android.bluetooth.BluetoothDevice d : bonded) return d.getName();
        } catch (SecurityException se) {
            return null;
        } catch (Throwable ignored) {}
        return null;
    }

    public String bluetoothSummary() {
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ba = bm != null ? bm.getAdapter() : android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba == null) return "Bluetooth unavailable. ";
            if (!ba.isEnabled()) return "Bluetooth is off. ";
            String name = connectedBluetoothDeviceName(ba);
            return name != null ? "Connected to " + name + " over Bluetooth. " : "Bluetooth is on, nothing connected. ";
        } catch (Throwable t) {
            return "";
        }
    }
}
