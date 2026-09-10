package com.iris.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks the hardware IRIS itself is using, centrally.
 *
 * Android gives no public dashboard of what every other app is doing with sensors, so this
 * registry only ever reports IRIS's own registrations. Anything else is shown as
 * "activity unavailable" rather than guessed at.
 *
 * Critically: nothing here activates hardware. Indicators reflect real usage only — a sensor is
 * never switched on just to make its row animate.
 */
public final class IrisSensorUsageRegistry {

    /** Hardware IRIS may use. */
    public enum Hardware { MICROPHONE, ACCELEROMETER, CAMERA, PROXIMITY, LOCATION }

    /** What IRIS is doing with it right now. */
    public static final class Usage {
        public final String purpose;        // "wake listening", "shake trigger", …
        public final long sinceElapsed;
        public final int samplingHintHz;    // 0 when not applicable/unknown

        Usage(String purpose, long sinceElapsed, int samplingHintHz) {
            this.purpose = purpose == null ? "" : purpose;
            this.sinceElapsed = sinceElapsed;
            this.samplingHintHz = samplingHintHz;
        }
    }

    private static final Map<Hardware, Usage> ACTIVE = new LinkedHashMap<>();
    private static volatile long lastChangeElapsed;

    private IrisSensorUsageRegistry() { }

    /** Called when IRIS genuinely starts using a piece of hardware. */
    public static synchronized void begin(Hardware hw, String purpose, int samplingHintHz) {
        if (hw == null) return;
        ACTIVE.put(hw, new Usage(purpose, android.os.SystemClock.elapsedRealtime(), samplingHintHz));
        lastChangeElapsed = android.os.SystemClock.elapsedRealtime();
    }

    public static void begin(Hardware hw, String purpose) { begin(hw, purpose, 0); }

    /** Called when IRIS stops using it. */
    public static synchronized void end(Hardware hw) {
        if (hw == null) return;
        if (ACTIVE.remove(hw) != null) lastChangeElapsed = android.os.SystemClock.elapsedRealtime();
    }

    public static synchronized void clear() {
        ACTIVE.clear();
        lastChangeElapsed = android.os.SystemClock.elapsedRealtime();
    }

    public static synchronized boolean isActive(Hardware hw) { return ACTIVE.containsKey(hw); }

    public static synchronized Usage usage(Hardware hw) { return ACTIVE.get(hw); }

    public static synchronized Map<Hardware, Usage> active() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ACTIVE));
    }

    public static long lastChange() { return lastChangeElapsed; }

    /** Row text for the Sensors panel: real state, or "Idle" — never a fake "Listening". */
    public static synchronized String status(Hardware hw) {
        Usage u = ACTIVE.get(hw);
        if (u == null) return "Idle";
        return u.purpose.isEmpty() ? "In use" : u.purpose;
    }

    /** Which hardware this device actually has, reported by the platform. */
    public static String availability(android.content.Context ctx, Hardware hw) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            switch (hw) {
                case MICROPHONE:
                    return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
                            ? "present" : "absent";
                case CAMERA:
                    return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
                            ? "present" : "absent";
                case ACCELEROMETER:
                    return hasSensor(ctx, android.hardware.Sensor.TYPE_ACCELEROMETER) ? "present" : "absent";
                case PROXIMITY:
                    return hasSensor(ctx, android.hardware.Sensor.TYPE_PROXIMITY) ? "present" : "absent";
                case LOCATION:
                    return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION)
                            ? "present" : "absent";
                default:
                    return "unknown";
            }
        } catch (Throwable t) { return "unknown"; }
    }

    private static boolean hasSensor(android.content.Context ctx, int type) {
        try {
            android.hardware.SensorManager sm =
                    (android.hardware.SensorManager) ctx.getSystemService(android.content.Context.SENSOR_SERVICE);
            return sm != null && sm.getDefaultSensor(type) != null;
        } catch (Throwable t) { return false; }
    }

    /** Human label for a row. */
    public static String label(Hardware hw) {
        switch (hw) {
            case MICROPHONE:    return "Microphone";
            case ACCELEROMETER: return "Accelerometer";
            case CAMERA:        return "Camera";
            case PROXIMITY:     return "Proximity";
            case LOCATION:      return "Location";
            default:            return hw.name();
        }
    }
}
