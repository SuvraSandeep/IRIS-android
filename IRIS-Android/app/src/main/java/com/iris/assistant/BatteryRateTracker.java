package com.iris.assistant;

import java.util.ArrayDeque;

/**
 * Estimates battery discharge/charge rate in %/hour from a rolling window of
 * (percent, elapsedRealtime) samples. Pure and Android-free except for the caller
 * supplying the samples, so this stays unit-testable like WakePolicy/TelemetrySnapshot.
 *
 * Honesty rules:
 *   - Needs at least two samples spanning a minimum time gap before it will report a rate;
 *     a single reading can't imply a rate, so it says "measuring" rather than guessing.
 *   - A charging-state change resets the window rather than blending charge and discharge
 *     into one misleading number.
 *   - The oldest sample within MAX_WINDOW_MS is used for the estimate, not just the last
 *     two points, so small percent-reporting jitter doesn't produce a wildly noisy rate.
 */
public final class BatteryRateTracker {

    private static final int MAX_SAMPLES = 40;
    private static final long MAX_WINDOW_MS = 30 * 60 * 1000L;   // look back at most 30 minutes
    private static final long MIN_SPAN_MS = 90 * 1000L;          // need >=90s of real spread

    private static final class Sample {
        final int percent;
        final long atMs;
        final boolean charging;
        Sample(int percent, long atMs, boolean charging) { this.percent = percent; this.atMs = atMs; this.charging = charging; }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** Record a new (percent, charging) observation at the given elapsed-time timestamp. */
    public synchronized void record(int percent, boolean charging, long nowMs) {
        if (percent < 0 || percent > 100) return;
        if (!samples.isEmpty() && samples.getLast().charging != charging) {
            // Charging state flipped — old samples would blend two different regimes.
            samples.clear();
        }
        samples.addLast(new Sample(percent, nowMs, charging));
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
        // Prune samples older than the window, but always keep at least 2 so a slow tick rate
        // (e.g. Read Once mode, or resuming after the app was backgrounded) doesn't leave the
        // tracker permanently unable to report a rate just because the gap between calls was wide.
        while (samples.size() > 2 && nowMs - samples.getFirst().atMs > MAX_WINDOW_MS) samples.removeFirst();
    }

    /** Result of a rate estimate. rate is %/hour (positive), never negative — direction is
     *  carried separately by charging/discharging so "10%/hour" always reads unambiguously. */
    public static final class Estimate {
        public final boolean available;
        public final boolean charging;
        public final double percentPerHour;
        private Estimate(boolean available, boolean charging, double percentPerHour) {
            this.available = available; this.charging = charging; this.percentPerHour = percentPerHour;
        }
        static Estimate none() { return new Estimate(false, false, 0); }
        static Estimate of(boolean charging, double rate) { return new Estimate(true, charging, Math.abs(rate)); }
    }

    /** Current estimate from the rolling window, or "not available yet" if too little history. */
    public synchronized Estimate estimate() {
        if (samples.size() < 2) return Estimate.none();
        Sample first = samples.getFirst();
        Sample last = samples.getLast();
        long spanMs = last.atMs - first.atMs;
        if (spanMs < MIN_SPAN_MS) return Estimate.none();
        int deltaPct = last.percent - first.percent;
        if (deltaPct == 0) return Estimate.of(last.charging, 0);
        double hours = spanMs / 3_600_000.0;
        double ratePerHour = deltaPct / hours;
        return Estimate.of(last.charging, ratePerHour);
    }

    /** Human-readable line, e.g. "Draining at about 8%/hour" or "Charging at about 22%/hour". */
    public static String describe(Estimate e) {
        if (!e.available) return "still measuring \u2014 check again in a minute or two";
        if (e.percentPerHour < 0.5) return e.charging ? "holding steady while charging" : "barely draining right now";
        String verb = e.charging ? "Charging" : "Draining";
        return verb + " at about " + Math.round(e.percentPerHour) + "%/hour";
    }
}
