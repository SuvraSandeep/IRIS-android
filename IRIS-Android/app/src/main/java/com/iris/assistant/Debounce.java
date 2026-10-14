package com.iris.assistant;

import java.util.List;

/**
 * Rapid-fire message handling. When several messages land within a short window (a human sending
 * three quick bubbles), IRIS should treat them as one so it doesn't fire three near-identical
 * introductions back-to-back. Pure and offline-testable.
 */
public final class Debounce {

    /** Default coalesce window: messages within this of the last reply are treated as one. */
    public static final long DEFAULT_WINDOW_MS = 3000L;

    private Debounce() { }

    /**
     * True when {@code nowMs} is within {@code windowMs} of {@code lastReplyMs} -- i.e. a reply was
     * just sent to this conversation and this new message is part of the same rapid-fire burst.
     * A zero/negative last-reply time or window means "no debounce".
     */
    public static boolean withinWindow(long lastReplyMs, long nowMs, long windowMs) {
        if (lastReplyMs <= 0 || windowMs <= 0) return false;
        long delta = nowMs - lastReplyMs;
        return delta >= 0 && delta < windowMs;
    }

    /** Join several quick messages into one string for a single match pass. */
    public static String combine(List<String> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String m : messages) {
            if (m == null) continue;
            String t = m.trim();
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(t);
        }
        return b.toString();
    }
}
