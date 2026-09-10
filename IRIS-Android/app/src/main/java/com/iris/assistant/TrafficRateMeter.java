package com.iris.assistant;

/**
 * Turns raw byte counters into a rate, plus a fixed-size history for the 60-second sparkline.
 *
 * Rules from the Command Deck spec:
 *   bytes per second = (current - previous) / elapsed seconds, using MONOTONIC elapsed time.
 *   If counters reset (go backwards) or become unavailable, reset the sample instead of
 *   reporting negative traffic.
 *   A zero-traffic period must produce a flat line, not an empty one.
 *
 * Pure Java so the arithmetic is unit tested.
 */
public final class TrafficRateMeter {

    /** TrafficStats returns this when a counter isn't supported. */
    public static final long UNSUPPORTED = -1;

    private final int capacity;
    private final long[] history;
    private int count;
    private int head;

    private long lastBytes = UNSUPPORTED;
    private long lastAtMs = 0;
    private long rate = -1;
    private boolean supported = true;
    private long sessionStartBytes = UNSUPPORTED;
    private long sessionBytes;

    public TrafficRateMeter() { this(60); }

    public TrafficRateMeter(int capacity) {
        this.capacity = Math.max(2, capacity);
        this.history = new long[this.capacity];
    }

    /**
     * Feed a counter reading.
     *
     * @param bytes   cumulative counter, or {@link #UNSUPPORTED}
     * @param nowMs   monotonic elapsed time in millis
     * @return current bytes/second, or -1 when not yet known/unsupported
     */
    public long sample(long bytes, long nowMs) {
        if (bytes == UNSUPPORTED || bytes < 0) {
            // Unavailable: forget the baseline so we never emit a bogus spike later.
            supported = false;
            lastBytes = UNSUPPORTED;
            lastAtMs = 0;
            rate = -1;
            return -1;
        }
        supported = true;
        if (sessionStartBytes == UNSUPPORTED) sessionStartBytes = bytes;
        // A counter that went backwards means it reset (or the UID changed) — rebase.
        if (bytes < sessionStartBytes) sessionStartBytes = bytes;
        sessionBytes = bytes - sessionStartBytes;

        if (lastBytes == UNSUPPORTED || nowMs <= lastAtMs) {
            lastBytes = bytes;
            lastAtMs = nowMs;
            rate = -1;                 // no interval yet: unknown, not zero
            return -1;
        }
        long deltaBytes = bytes - lastBytes;
        long deltaMs = nowMs - lastAtMs;
        lastBytes = bytes;
        lastAtMs = nowMs;
        if (deltaBytes < 0) {          // counter reset mid-run
            rate = -1;
            return -1;
        }
        rate = deltaMs <= 0 ? 0 : Math.round(deltaBytes * 1000.0 / deltaMs);
        push(rate);
        return rate;
    }

    private void push(long v) {
        history[head] = v;
        head = (head + 1) % capacity;
        if (count < capacity) count++;
    }

    /** Latest rate, or -1 when unknown. */
    public long rate() { return rate; }

    /** True once at least one interval has been measured. */
    public boolean hasRate() { return rate >= 0; }

    public boolean supported() { return supported; }

    /** Bytes counted since monitoring began (0 when nothing yet). */
    public long sessionBytes() { return Math.max(0, sessionBytes); }

    /** Oldest-to-newest samples for the sparkline. */
    public long[] history() {
        long[] out = new long[count];
        int start = (head - count + capacity) % capacity;
        for (int i = 0; i < count; i++) out[i] = history[(start + i) % capacity];
        return out;
    }

    public int sampleCount() { return count; }

    /** Largest sample, used to scale the chart (never 0, so a flat line still renders). */
    public long peak() {
        long max = 0;
        for (long v : history()) max = Math.max(max, v);
        return max;
    }

    /** Forget everything (e.g. panel closed, or UID/counters changed). */
    public void reset() {
        count = 0;
        head = 0;
        lastBytes = UNSUPPORTED;
        lastAtMs = 0;
        rate = -1;
        supported = true;
        sessionStartBytes = UNSUPPORTED;
        sessionBytes = 0;
    }

    /** Display string honouring availability: never prints "0.0 KB/s" for unknown. */
    public String displayRate() {
        if (!supported) return "Not available";
        if (rate < 0) return "measuring\u2026";
        return TelemetrySnapshot.rate(rate);
    }
}

