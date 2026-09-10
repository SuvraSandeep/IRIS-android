package com.iris.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The Command Deck activity stream.
 *
 * Rules from the spec:
 *   - keep ~200 recent events in memory (bounded, oldest dropped);
 *   - repeated unchanged readings must NOT generate new lines;
 *   - never fabricate "scanning" / "decrypting" / "secure connection" theatre —
 *     callers may only log something that actually happened.
 *
 * Pure Java so the dedupe and bounding are unit tested.
 */
public final class TelemetryEventLog {

    public static final int DEFAULT_CAPACITY = 200;

    /** Filter categories shown in the console. */
    public enum Category {
        NET("NET"), AUDIO("AUDIO"), BT("BT"), WAKE("WAKE"), SENSOR("SENSOR"),
        ACTION("ACTION"), VOICE("VOICE"), SYS("SYS");

        public final String tag;
        Category(String tag) { this.tag = tag; }
    }

    public static final class Event {
        public final long atWallClock;      // for the HH:mm:ss stamp
        public final Category category;
        public final String message;
        public final String detail;         // shown when the row is expanded

        Event(long atWallClock, Category category, String message, String detail) {
            this.atWallClock = atWallClock;
            this.category = category;
            this.message = message == null ? "" : message;
            this.detail = detail == null ? "" : detail;
        }

        /** "12:24:01  NET    Default connection changed to Wi-Fi" */
        public String line() {
            return stamp() + "  " + pad(category.tag) + " " + message;
        }

        public String stamp() {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(atWallClock);
            return String.format(Locale.US, "%02d:%02d:%02d",
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE),
                    c.get(java.util.Calendar.SECOND));
        }

        private static String pad(String s) {
            StringBuilder sb = new StringBuilder(s == null ? "" : s);
            while (sb.length() < 6) sb.append(' ');
            return sb.toString();
        }
    }

    private final int capacity;
    private final Deque<Event> events = new ArrayDeque<>();
    /** Last message per category, so an unchanged reading doesn't spam the console. */
    private final java.util.Map<Category, String> lastPerCategory = new java.util.EnumMap<>(Category.class);

    public TelemetryEventLog() { this(DEFAULT_CAPACITY); }

    public TelemetryEventLog(int capacity) { this.capacity = Math.max(1, capacity); }

    /**
     * Record a real state change.
     * @return true if it was added, false if it duplicated the category's previous message
     */
    public synchronized boolean add(Category category, String message, String detail, long wallClock) {
        if (category == null || message == null || message.trim().isEmpty()) return false;
        String msg = message.trim();
        String previous = lastPerCategory.get(category);
        if (msg.equals(previous)) return false;            // unchanged reading → no new line
        lastPerCategory.put(category, msg);
        events.addLast(new Event(wallClock, category, msg, detail));
        while (events.size() > capacity) events.removeFirst();
        return true;
    }

    public boolean add(Category category, String message) {
        return add(category, message, "", System.currentTimeMillis());
    }

    /** Newest first, optionally filtered. */
    public synchronized List<Event> recent(int limit, Category filter) {
        List<Event> out = new ArrayList<>();
        java.util.Iterator<Event> it = events.descendingIterator();
        while (it.hasNext() && out.size() < Math.max(1, limit)) {
            Event e = it.next();
            if (filter == null || e.category == filter) out.add(e);
        }
        return out;
    }

    public List<Event> recent(int limit) { return recent(limit, null); }

    public synchronized int size() { return events.size(); }

    /** Clear the visible session (keeps dedupe state so old readings don't re-announce). */
    public synchronized void clear() { events.clear(); }

    /** Rendered console text, newest last (reads like a terminal). */
    public synchronized String render(int limit, Category filter) {
        List<Event> newestFirst = recent(limit, filter);
        StringBuilder sb = new StringBuilder();
        for (int i = newestFirst.size() - 1; i >= 0; i--) sb.append(newestFirst.get(i).line()).append('\n');
        if (sb.length() == 0) return "No events yet.";
        return sb.toString().trim();
    }
}
