package com.iris.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Command Deck telemetry model.
 *
 * The whole point of this class is honesty. Every metric carries its availability, so the UI can
 * never show:
 *   - "unknown" rendered as "off",
 *   - "no permission" rendered as a fabricated value,
 *   - "counter unavailable" rendered as 0.
 *
 * Pure Java (no Android) so the display rules are unit tested.
 */
public final class TelemetrySnapshot {

    /** Why a value may not be showable. */
    public enum Availability {
        AVAILABLE,
        PERMISSION_REQUIRED,
        UNSUPPORTED,
        STALE
    }

    /** One observed fact. Immutable. */
    public static final class Metric {
        public final String key;
        public final String value;
        public final String unit;
        public final String source;
        public final long observedAt;      // elapsed-realtime millis (monotonic)
        public final Availability availability;

        private Metric(String key, String value, String unit, String source,
                       long observedAt, Availability availability) {
            this.key = key == null ? "" : key;
            this.value = value == null ? "" : value;
            this.unit = unit == null ? "" : unit;
            this.source = source == null ? "" : source;
            this.observedAt = observedAt;
            this.availability = availability == null ? Availability.UNSUPPORTED : availability;
        }

        public static Metric of(String key, String value, String unit, String source, long observedAt) {
            // An empty value is not "available" — it is unknown.
            if (value == null || value.trim().isEmpty()) {
                return new Metric(key, "", unit, source, observedAt, Availability.UNSUPPORTED);
            }
            return new Metric(key, value, unit, source, observedAt, Availability.AVAILABLE);
        }

        public static Metric permissionRequired(String key, String source) {
            return new Metric(key, "", "", source, 0, Availability.PERMISSION_REQUIRED);
        }

        public static Metric unsupported(String key, String source) {
            return new Metric(key, "", "", source, 0, Availability.UNSUPPORTED);
        }

        /** Same reading, but flagged as too old to trust. */
        public Metric stale() {
            return new Metric(key, value, unit, source, observedAt, Availability.STALE);
        }

        public boolean isAvailable() { return availability == Availability.AVAILABLE; }

        /** What the UI should print. Never invents a number. */
        public String display() {
            switch (availability) {
                case AVAILABLE:
                    return unit.isEmpty() ? value : value + " " + unit;
                case PERMISSION_REQUIRED:
                    return "Permission required";
                case STALE:
                    return (unit.isEmpty() ? value : value + " " + unit) + " (stale)";
                case UNSUPPORTED:
                default:
                    return "Not available";
            }
        }

        @Override public String toString() { return key + "=" + display(); }
    }

    private final Map<String, Metric> metrics;
    public final long createdAt;

    private TelemetrySnapshot(Map<String, Metric> metrics, long createdAt) {
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        this.createdAt = createdAt;
    }

    public static Builder builder(long createdAt) { return new Builder(createdAt); }

    public static TelemetrySnapshot empty() {
        return new TelemetrySnapshot(new LinkedHashMap<>(), 0);
    }

    /** Never returns null: an absent key is UNSUPPORTED, not a fake zero. */
    public Metric get(String key) {
        Metric m = metrics.get(key);
        return m != null ? m : Metric.unsupported(key, "absent");
    }

    public String display(String key) { return get(key).display(); }

    public Map<String, Metric> all() { return metrics; }

    public int size() { return metrics.size(); }

    /**
     * A view of this snapshot where anything older than {@code maxAgeMs} is marked STALE, so a
     * frozen collector can't keep presenting old readings as current.
     */
    public TelemetrySnapshot agedAt(long nowElapsed, long maxAgeMs) {
        Builder b = builder(createdAt);
        for (Metric m : metrics.values()) {
            boolean tooOld = m.isAvailable() && m.observedAt > 0 && (nowElapsed - m.observedAt) > maxAgeMs;
            b.put(tooOld ? m.stale() : m);
        }
        return b.build();
    }

    /** How fresh this snapshot is, for the header's "telemetry freshness" indicator. */
    public String freshness(long nowElapsed) {
        if (createdAt <= 0) return "no data";
        long age = Math.max(0, nowElapsed - createdAt) / 1000;
        if (age < 2) return "live";
        if (age < 60) return age + "s ago";
        return (age / 60) + "m ago";
    }

    public static final class Builder {
        private final Map<String, Metric> map = new LinkedHashMap<>();
        private final long createdAt;

        Builder(long createdAt) { this.createdAt = createdAt; }

        public Builder put(Metric m) {
            if (m != null && !m.key.isEmpty()) map.put(m.key, m);
            return this;
        }

        public Builder value(String key, String value, String unit, String source, long observedAt) {
            return put(Metric.of(key, value, unit, source, observedAt));
        }

        public Builder permission(String key, String source) {
            return put(Metric.permissionRequired(key, source));
        }

        public Builder unsupported(String key, String source) {
            return put(Metric.unsupported(key, source));
        }

        public TelemetrySnapshot build() { return new TelemetrySnapshot(map, createdAt); }
    }

    // ─────────────────── shared formatting helpers ───────────────────

    /** Bytes/second in a compact, honest unit. */
    public static String rate(long bytesPerSecond) {
        if (bytesPerSecond < 0) return "";
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        double kb = bytesPerSecond / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb);
        return String.format(Locale.US, "%.2f MB/s", kb / 1024.0);
    }

    public static String bytes(long value) {
        if (value < 0) return "";
        if (value < 1024) return value + " B";
        double kb = value / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Uptime as "3d 4h" / "4h 12m" / "12m". */
    public static String duration(long millis) {
        if (millis < 0) return "";
        long s = millis / 1000, m = s / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }
}
