package com.iris.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * Resources panel: battery, thermal, memory, storage, device and uptime.
 *
 * Honesty rules:
 *   - Battery temperature is labelled BATTERY temperature. There is no "CPU temperature" gauge,
 *     because Android exposes no such public measurement.
 *   - Thermal status is only reported where the platform supports it (API 29+).
 */
public final class ResourceTelemetryCollector {

    public static final String K_BATTERY = "battery";
    public static final String K_CHARGING = "charging";
    public static final String K_POWER_SAVE = "power_save";
    public static final String K_BATTERY_TEMP = "battery_temp";
    public static final String K_BATTERY_RATE = "battery_discharge_rate";
    public static final String K_THERMAL = "thermal";
    public static final String K_RAM_FREE = "ram_free";
    public static final String K_RAM_IRIS = "ram_iris";
    public static final String K_STORAGE_FREE = "storage_free";
    public static final String K_DEVICE = "device";
    public static final String K_ANDROID = "android";
    public static final String K_APP_VERSION = "app_version";
    public static final String K_DEVICE_UPTIME = "device_uptime";
    public static final String K_SERVICE_UPTIME = "service_uptime";

    private final Context ctx;
    private final TelemetryEventLog log;
    private String lastBattery = "";
    // Static so the rolling window survives across short-lived collector instances (a new one
    // is created per SystemTelemetryController), matching how battery % naturally changes slowly.
    private static final BatteryRateTracker rateTracker = new BatteryRateTracker();

    public ResourceTelemetryCollector(Context context, TelemetryEventLog log) {
        this.ctx = context.getApplicationContext();
        this.log = log;
    }

    public void contribute(TelemetrySnapshot.Builder b, long serviceStartElapsed) {
        long now = SystemClock.elapsedRealtime();

        // ── battery (broadcast-driven values, read on demand) ──
        Intent bat = null;
        try {
            bat = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) { }
        if (bat == null) {
            b.unsupported(K_BATTERY, "BatteryManager");
            b.unsupported(K_CHARGING, "BatteryManager");
            b.unsupported(K_BATTERY_TEMP, "BatteryManager");
            b.unsupported(K_BATTERY_RATE, "BatteryManager");
        } else {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                int pct = Math.round(level * 100f / scale);
                b.value(K_BATTERY, String.valueOf(pct), "%", "BatteryManager", now);
                if (log != null) {
                    String msg = "Battery " + pct + "%";
                    if (!msg.equals(lastBattery)) { lastBattery = msg; }
                }
                int statusForRate = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean chargingForRate = statusForRate == BatteryManager.BATTERY_STATUS_CHARGING
                        || statusForRate == BatteryManager.BATTERY_STATUS_FULL;
                rateTracker.record(pct, chargingForRate, now);
                BatteryRateTracker.Estimate rate = rateTracker.estimate();
                b.value(K_BATTERY_RATE, BatteryRateTracker.describe(rate), "", "BatteryRateTracker", now);
            } else {
                b.unsupported(K_BATTERY, "BatteryManager");
                b.unsupported(K_BATTERY_RATE, "BatteryManager");
            }
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            String charge = status < 0 || status == BatteryManager.BATTERY_STATUS_UNKNOWN ? "" : status == BatteryManager.BATTERY_STATUS_FULL ? "Full"
                    : status == BatteryManager.BATTERY_STATUS_CHARGING
                        ? ("Charging" + (plugged == BatteryManager.BATTERY_PLUGGED_USB ? " (USB)"
                            : plugged == BatteryManager.BATTERY_PLUGGED_AC ? " (AC)"
                            : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? " (wireless)" : ""))
                        : "On battery";
            b.value(K_CHARGING, charge, "", "BatteryManager", now);
            int tenthsC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenthsC != Integer.MIN_VALUE && tenthsC > -500 && tenthsC < 1000) {
                // Explicitly battery temperature — not CPU.
                b.value(K_BATTERY_TEMP, String.format(java.util.Locale.US, "%.1f", tenthsC / 10.0),
                        "\u00b0C (battery)", "BatteryManager", now);
            } else {
                b.unsupported(K_BATTERY_TEMP, "BatteryManager");
            }
        }
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) b.value(K_POWER_SAVE, pm.isPowerSaveMode() ? "On" : "Off", "", "PowerManager", now);
            else b.unsupported(K_POWER_SAVE, "PowerManager");
            if (pm != null && Build.VERSION.SDK_INT >= 29) {
                b.value(K_THERMAL, thermal(pm.getCurrentThermalStatus()), "", "PowerManager", now);
            } else {
                b.unsupported(K_THERMAL, "PowerManager (API 29+)");
            }
        } catch (Throwable t) {
            b.unsupported(K_POWER_SAVE, "PowerManager");
            b.unsupported(K_THERMAL, "PowerManager");
        }

        // ── memory ──
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                b.value(K_RAM_FREE, TelemetrySnapshot.bytes(mi.availMem), "", "ActivityManager", now);
            } else {
                b.unsupported(K_RAM_FREE, "ActivityManager");
            }
        } catch (Throwable t) { b.unsupported(K_RAM_FREE, "ActivityManager"); }
        try {
            Runtime r = Runtime.getRuntime();
            b.value(K_RAM_IRIS, TelemetrySnapshot.bytes(r.totalMemory() - r.freeMemory()), "", "Runtime", now);
        } catch (Throwable t) { b.unsupported(K_RAM_IRIS, "Runtime"); }

        // ── storage ──
        try {
            java.io.File dir = ctx.getFilesDir();
            android.os.StatFs fs = new android.os.StatFs(dir.getAbsolutePath());
            long free = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
            b.value(K_STORAGE_FREE, TelemetrySnapshot.bytes(free), "", "StatFs", now);
        } catch (Throwable t) { b.unsupported(K_STORAGE_FREE, "StatFs"); }

        // ── identity / uptime ──
        b.value(K_DEVICE, Build.MANUFACTURER + " " + Build.MODEL, "", "Build", now);
        b.value(K_ANDROID, "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                "", "Build", now);
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            b.value(K_APP_VERSION, "IRIS " + pi.versionName, "", "PackageManager", now);
        } catch (Throwable t) { b.unsupported(K_APP_VERSION, "PackageManager"); }
        b.value(K_DEVICE_UPTIME, TelemetrySnapshot.duration(SystemClock.elapsedRealtime()), "", "SystemClock", now);
        if (serviceStartElapsed > 0) {
            b.value(K_SERVICE_UPTIME, TelemetrySnapshot.duration(now - serviceStartElapsed), "", "IRIS", now);
        } else {
            b.value(K_SERVICE_UPTIME, "not running", "", "IRIS", now);
        }
    }

    private static String thermal(int status) {
        switch (status) {
            case android.os.PowerManager.THERMAL_STATUS_NONE: return "Normal";
            case android.os.PowerManager.THERMAL_STATUS_LIGHT: return "Light";
            case android.os.PowerManager.THERMAL_STATUS_MODERATE: return "Moderate";
            case android.os.PowerManager.THERMAL_STATUS_SEVERE: return "Severe";
            case android.os.PowerManager.THERMAL_STATUS_CRITICAL: return "Critical";
            case android.os.PowerManager.THERMAL_STATUS_EMERGENCY: return "Emergency";
            case android.os.PowerManager.THERMAL_STATUS_SHUTDOWN: return "Shutdown";
            default: return "Unknown";
        }
    }
}

