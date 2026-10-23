package com.iris.assistant;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.util.Log;

import java.util.Locale;

/**
 * On-device sensor and system event detector for situational auto-modes:
 * - Driving: detects Bluetooth car head unit / car kit / car audio connections
 * - Meeting: queries CalendarContract.Instances for events marked BUSY during current time
 */
public final class AutoModeDetector {

    private static final String TAG = "IRIS-AutoMode";
    private static volatile Boolean cachedCarBluetoothConnected = null;
    private static volatile long lastBluetoothCheckMs = 0;

    private AutoModeDetector() { }

    /**
     * Handles Bluetooth ACL connection events (e.g. ACTION_ACL_CONNECTED / ACTION_ACL_DISCONNECTED)
     * to immediately update vehicle connection state without latency.
     */
    public static void onBluetoothAclEvent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            android.bluetooth.BluetoothDevice device =
                    intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
            if (device != null && isCarDevice(device)) {
                cachedCarBluetoothConnected = true;
                lastBluetoothCheckMs = System.currentTimeMillis();
                Log.i(TAG, "Car Bluetooth connected: " + safeDeviceName(device));
            }
        } else if (android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            android.bluetooth.BluetoothDevice device =
                    intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
            if (device != null && isCarDevice(device)) {
                cachedCarBluetoothConnected = false;
                lastBluetoothCheckMs = System.currentTimeMillis();
                Log.i(TAG, "Car Bluetooth disconnected: " + safeDeviceName(device));
            }
        }
    }

    private static String safeDeviceName(android.bluetooth.BluetoothDevice device) {
        if (device == null) return "unknown";
        try {
            String name = device.getName();
            return name != null ? name : "unnamed";
        } catch (Throwable ignored) {
            return "unnamed";
        }
    }

    /**
     * Checks if the device is currently connected to a vehicle Bluetooth head unit or car kit.
     */
    public static boolean isDrivingActive(Context context) {
        if (context == null) return false;
        long now = System.currentTimeMillis();
        if (cachedCarBluetoothConnected != null && (now - lastBluetoothCheckMs < 120_000L)) {
            return cachedCarBluetoothConnected;
        }
        boolean driving = false;
        try {
            // 1. Check AudioManager audio routes (fast, no permissions needed)
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= 23) {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (devices != null) {
                    for (AudioDeviceInfo d : devices) {
                        if (d == null) continue;
                        CharSequence name = d.getProductName();
                        if (name != null && isCarName(name.toString())) {
                            driving = true;
                            break;
                        }
                    }
                }
            }

            if (!driving) {
                // 2. Check BluetoothAdapter connected devices (if permission granted)
                if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(
                        android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {

                    android.bluetooth.BluetoothManager bm =
                            (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                    if (adapter != null && adapter.isEnabled()) {
                        // Check A2DP and HEADSET connected devices
                        int[] profiles = {
                                android.bluetooth.BluetoothProfile.A2DP,
                                android.bluetooth.BluetoothProfile.HEADSET
                        };
                        for (int p : profiles) {
                            try {
                                for (android.bluetooth.BluetoothDevice d : bm.getConnectedDevices(p)) {
                                    if (d == null) continue;
                                    if (isCarDevice(d)) { driving = true; break; }
                                }
                            } catch (Throwable ignored) { }
                            if (driving) break;
                        }

                        // Check bonded devices using reflection isConnected()
                        if (!driving) {
                            try {
                                for (android.bluetooth.BluetoothDevice d : adapter.getBondedDevices()) {
                                    if (d == null) continue;
                                    try {
                                        java.lang.reflect.Method m = d.getClass().getMethod("isConnected");
                                        if (Boolean.TRUE.equals(m.invoke(d))) {
                                            if (isCarDevice(d)) { driving = true; break; }
                                        }
                                    } catch (Throwable ignored) { }
                                    if (driving) break;
                                }
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Driving detection error: " + t.getMessage());
        }
        cachedCarBluetoothConnected = driving;
        lastBluetoothCheckMs = now;
        return driving;
    }

    private static boolean isCarDevice(android.bluetooth.BluetoothDevice d) {
        if (d == null) return false;
        try {
            // Check device class
            android.bluetooth.BluetoothClass c = d.getBluetoothClass();
            if (c != null) {
                int devClass = c.getDeviceClass();
                // 1056 = AUDIO_VIDEO_CAR_AUDIO, 1032 = AUDIO_VIDEO_HANDSFREE
                if (devClass == 1056 || devClass == 1032) return true;
            }
            // Check device name
            String name = d.getName();
            if (name != null && isCarName(name)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isCarName(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(Locale.ROOT);
        return low.contains("car") || low.contains("auto") || low.contains("infotainment")
                || low.contains("uconnect") || low.contains("sync") || low.contains("mylink")
                || low.contains("bmw") || low.contains("audi") || low.contains("toyota")
                || low.contains("honda") || low.contains("ford") || low.contains("hyundai")
                || low.contains("mazda") || low.contains("nissan") || low.contains("carplay")
                || low.contains("handsfree") || low.contains("head unit");
    }

    /**
     * Checks if the owner currently has a calendar event marked BUSY in progress.
     */
    public static boolean isMeetingActive(Context context) {
        if (context == null) return false;
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, now - 60_000L);
            ContentUris.appendId(builder, now + 60_000L);

            String[] projection = new String[] {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.AVAILABILITY,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END
            };

            try (Cursor cursor = context.getContentResolver().query(
                    builder.build(), projection, null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        int avail = cursor.getInt(1);
                        long start = cursor.getLong(2);
                        long end = cursor.getLong(3);
                        // AVAILABILITY_BUSY is 0; AVAILABILITY_FREE is 1
                        if (avail == CalendarContract.Instances.AVAILABILITY_BUSY
                                && now >= start && now <= end) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Meeting detection error: " + t.getMessage());
        }
        return false;
    }

    /**
     * Evaluates all situational signals and returns an AutoModePolicy.Signals snapshot.
     */
    public static AutoModePolicy.Signals evaluateSignals(Context context, ResponderSettings settings, int minuteOfDay) {
        AutoModePolicy.Signals sig = new AutoModePolicy.Signals();
        if (settings == null) return sig;

        sig.autoSleep = settings.autoSleepInQuietHours();
        sig.quietHours = ResponderWindow.isQuiet(settings.quietStart(), settings.quietEnd(), minuteOfDay);

        sig.autoDriving = settings.autoDriving();
        sig.driving = sig.autoDriving && isDrivingActive(context);

        sig.autoMeeting = settings.autoMeeting();
        sig.meeting = sig.autoMeeting && isMeetingActive(context);

        return sig;
    }
}
