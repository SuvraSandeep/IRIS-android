package com.iris.assistant;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a reminder out of a message: what to be reminded of, and when.
 *
 * Pure and offline-testable. It does two separable things, kept separate so each can be tested in
 * isolation and neither needs a clock:
 *
 *   1. {@link #parse} reads the text and returns a {@link Result} -- the task, and a time
 *      SPECIFICATION (in N minutes, at a clock time, on a weekday) or none.
 *   2. {@link #delayMinutes} turns a specification into a concrete delay from now, given the
 *      current minute-of-day and weekday passed in. The Android layer supplies those and never
 *      has to parse anything.
 *
 * No date classes appear here at all, which is the same discipline ResponderWindow uses: a pure
 * function of injected clock components cannot be wrong about timezones.
 *
 * The grammar is deliberately modest -- the forms people actually text ("remind me at 5",
 * "tomorrow", "in 2 hours", "on monday") -- because a reminder that silently mis-parses a time is
 * worse than one that admits it couldn't and asks. Anything it cannot read returns no time, which
 * the responder turns into a question back to the sender.
 */
public final class ReminderParser {

    /** How the time was expressed, or that it was not. */
    public enum When {
        /** No time found. The responder will ask the sender. */
        NONE,
        /** A fixed number of minutes from now. */
        IN_MINUTES,
        /** A clock time; the next occurrence of it, unless {@link Result#tomorrow}. */
        AT_TIME,
        /** A weekday; the next occurrence, optionally at a clock time. */
        ON_WEEKDAY
    }

    /** The parsed reminder. */
    public static final class Result {
        public final String task;
        public final When when;
        /** IN_MINUTES: the minutes. AT_TIME/ON_WEEKDAY: minute-of-day, or -1 when no time given. */
        public final int minuteOfDay;
        /** IN_MINUTES only: the minutes from now. */
        public final int minutes;
        /** ON_WEEKDAY only: 1=Sunday..7=Saturday, Calendar style. */
        public final int weekday;
        /** AT_TIME only: the clock time is explicitly "tomorrow". */
        public final boolean tomorrow;

        Result(String task, When when, int minuteOfDay, int minutes, int weekday,
               boolean tomorrow) {
            this.task = task == null ? "" : task;
            this.when = when;
            this.minuteOfDay = minuteOfDay;
            this.minutes = minutes;
            this.weekday = weekday;
            this.tomorrow = tomorrow;
        }

        public boolean hasTime() { return when != When.NONE; }
    }

    private static final List<String> TRIGGERS = Arrays.asList(
            "remind me to", "remind me", "remind him to", "remind him",
            "remind suvra to", "remind suvra", "remind stg to", "remind stg",
            "don't forget to", "dont forget to", "don t forget to", "do not forget to",
            "please remember to", "remember to", "please remember",
            "set a reminder to", "set a reminder", "make a note to", "note to self to",
            "note to self");

    private static final String[] WEEKDAYS = {
            "", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    // "in 2 hours", "in 30 minutes", "in 1 hr", "in 15 mins"
    private static final Pattern IN_PATTERN = Pattern.compile(
            "\\bin\\s+(\\d{1,3})\\s*(min|mins|minute|minutes|hr|hrs|hour|hours|day|days)\\b");
    // "at 5", "at 5pm", "at 5:30", "at 17:00", "at 5 pm"
    private static final Pattern AT_PATTERN = Pattern.compile(
            "\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b");

    private ReminderParser() { }

    /** Parse a message into a reminder. */
    public static Result parse(String raw) {
        if (raw == null) return new Result("", When.NONE, -1, 0, -1, false);
        String lower = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty()) return new Result("", When.NONE, -1, 0, -1, false);

        // ─── Time ───
        When when = When.NONE;
        int minutes = 0;
        int minuteOfDay = -1;
        int weekday = -1;
        boolean tomorrow = false;

        Matcher in = IN_PATTERN.matcher(lower);
        if (in.find()) {
            int n = safeInt(in.group(1));
            String unit = in.group(2);
            if (unit.startsWith("hr") || unit.startsWith("hour")) {
                minutes = n * 60;
            } else if (unit.startsWith("day")) {
                minutes = n * 60 * 24;
            } else {
                minutes = n;
            }
            when = When.IN_MINUTES;
        }

        // Clock time, used by AT_TIME and ON_WEEKDAY alike.
        int clock = -1;
        Matcher at = AT_PATTERN.matcher(lower);
        if (at.find()) {
            clock = clockToMinuteOfDay(at.group(1), at.group(2), at.group(3));
        }

        int foundWeekday = weekdayIn(lower);

        if (when != When.IN_MINUTES) {
            if (foundWeekday > 0) {
                when = When.ON_WEEKDAY;
                weekday = foundWeekday;
                minuteOfDay = clock; // may be -1, meaning "that day, default time"
            } else if (lower.contains("tomorrow")) {
                when = When.AT_TIME;
                tomorrow = true;
                minuteOfDay = clock; // -1 means "tomorrow, default time"
            } else if (lower.contains("tonight")) {
                when = When.AT_TIME;
                minuteOfDay = clock >= 0 ? clock : 20 * 60; // tonight = 8pm unless a time given
            } else if (clock >= 0) {
                when = When.AT_TIME;
                minuteOfDay = clock;
            }
        }

        // ─── Task ───
        String task = extractTask(raw);

        return new Result(task, when, minuteOfDay, minutes, weekday, tomorrow);
    }

    /**
     * Minutes from now until the reminder is due, given the current clock.
     *
     * Pure: the Android layer passes the current minute-of-day and weekday, so this can be tested
     * with any "now". Never returns less than 1 for a timed reminder -- a reminder due in the past
     * (a clock time already gone today, with no day qualifier) rolls to the next day rather than
     * firing instantly or in the past.
     *
     * @param nowMinuteOfDay minutes since local midnight, 0..1439
     * @param nowWeekday     1=Sunday..7=Saturday, Calendar style
     * @return minutes from now, or -1 when the result has no time
     */
    public static int delayMinutes(Result r, int nowMinuteOfDay, int nowWeekday) {
        if (r == null || r.when == When.NONE) return -1;
        switch (r.when) {
            case IN_MINUTES:
                return Math.max(1, r.minutes);
            case AT_TIME: {
                int target = r.minuteOfDay >= 0 ? r.minuteOfDay : 9 * 60; // default 9am
                if (r.tomorrow) {
                    return (1440 - nowMinuteOfDay) + target;
                }
                int delta = target - nowMinuteOfDay;
                if (delta <= 0) delta += 1440; // already past today -> tomorrow
                return delta;
            }
            case ON_WEEKDAY: {
                int target = r.minuteOfDay >= 0 ? r.minuteOfDay : 9 * 60;
                int days = (r.weekday - nowWeekday + 7) % 7;
                if (days == 0) {
                    // Same weekday: today if the time is still ahead, else a week away.
                    if (target - nowMinuteOfDay > 0) return target - nowMinuteOfDay;
                    return 7 * 1440 + (target - nowMinuteOfDay);
                }
                return days * 1440 + (target - nowMinuteOfDay);
            }
            default:
                return -1;
        }
    }

    /** Strip the trigger phrase and leading filler, leaving the task. Falls back to the whole text. */
    static String extractTask(String raw) {
        String text = raw == null ? "" : raw.trim();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String trigger : TRIGGERS) {
            int idx = lower.indexOf(trigger);
            if (idx >= 0) {
                String after = text.substring(idx + trigger.length()).trim();
                if (!after.isEmpty()) return trimTrailingTime(after);
                break;
            }
        }
        return trimTrailingTime(text);
    }

    /** Remove a trailing time clause so the task reads cleanly ("call mum", not "call mum at 5"). */
    private static String trimTrailingTime(String task) {
        String out = task;
        out = out.replaceAll("(?i)\\s+(at\\s+\\d{1,2}(?::\\d{2})?\\s*(am|pm)?)\\s*$", "");
        out = out.replaceAll("(?i)\\s+(in\\s+\\d{1,3}\\s*(min|mins|minute|minutes|hr|hrs|hour|"
                + "hours|day|days))\\s*$", "");
        out = out.replaceAll("(?i)\\s+(tomorrow|tonight|today)\\s*$", "");
        out = out.replaceAll("(?i)\\s+(on\\s+)?(sunday|monday|tuesday|wednesday|thursday|friday|"
                + "saturday)\\s*$", "");
        out = out.replaceAll("[\\s,\\.]+$", "").trim();
        return out.isEmpty() ? task.trim() : out;
    }

    private static int weekdayIn(String lower) {
        for (int i = 1; i < WEEKDAYS.length; i++) {
            if (containsWord(lower, WEEKDAYS[i])) return i;
        }
        return -1;
    }

    private static boolean containsWord(String haystack, String word) {
        String padded = " " + haystack.replaceAll("[^a-z0-9]", " ") + " ";
        return padded.contains(" " + word + " ");
    }

    private static int clockToMinuteOfDay(String hour, String minute, String ampm) {
        int h = safeInt(hour);
        int m = minute == null ? 0 : safeInt(minute);
        if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
        if (ampm != null) {
            ampm = ampm.toLowerCase(java.util.Locale.ROOT);
            if (ampm.equals("pm") && h < 12) h += 12;
            if (ampm.equals("am") && h == 12) h = 0;
        }
        if (h > 23) return -1;
        return h * 60 + m;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
