package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * A small per‑conversation rolling buffer of the most recent incoming messages, so the responder
 * can read a message split across several quick bubbles ("hey" / "are you free" / "tomorrow?") as
 * one, and understand a short follow‑up against what was just said. Pure and offline‑testable; the
 * Android layer owns persistence and passes the stored list in.
 *
 * The buffer keeps the LAST {@code capacity} messages, oldest first, newest last. Capacity is the
 * owner's "context depth" setting, so it's customizable and stored by the caller.
 */
public final class ChatHistory {

    /** Hard ceiling so a mis‑set depth can't make the buffer unbounded. */
    public static final int MAX_DEPTH = 10;

    private ChatHistory() { }

    /** Clamp a requested depth to a sane range. 0 means "no history, just this message". */
    public static int clampDepth(int depth) {
        if (depth < 0) return 0;
        return Math.min(depth, MAX_DEPTH);
    }

    /**
     * Append a new message and return the trimmed buffer (last {@code capacity}, oldest first).
     * Blank messages are ignored. A capacity of 0 returns an empty buffer.
     */
    public static List<String> append(List<String> existing, String message, int capacity) {
        int cap = clampDepth(capacity);
        List<String> out = new ArrayList<>();
        if (existing != null) {
            for (String m : existing) {
                if (m != null && !m.trim().isEmpty()) out.add(m);
            }
        }
        if (message != null && !message.trim().isEmpty()) out.add(message.trim());
        // Keep only the last `cap` entries.
        while (out.size() > cap && !out.isEmpty()) out.remove(0);
        return out;
    }

    /** The last {@code n} messages of a buffer (oldest first), or the whole buffer if smaller. */
    public static List<String> window(List<String> buffer, int n) {
        List<String> out = new ArrayList<>();
        if (buffer == null || n <= 0) return out;
        int start = Math.max(0, buffer.size() - n);
        for (int i = start; i < buffer.size(); i++) {
            String m = buffer.get(i);
            if (m != null && !m.trim().isEmpty()) out.add(m);
        }
        return out;
    }

    /** Join a buffer into one string for a single match pass, oldest first. */
    public static String combine(List<String> buffer) {
        if (buffer == null || buffer.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String m : buffer) {
            if (m == null || m.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(". ");
            b.append(m.trim());
        }
        return b.toString();
    }
}
