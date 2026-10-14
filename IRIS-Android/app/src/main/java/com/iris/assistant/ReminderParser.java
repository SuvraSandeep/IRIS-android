package com.iris.assistant;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a reminder out of a message: what to be reminded of, and when.
 *
 * Pure and offline-testable. It does two separable things, kept separate so each can be tested in
 * isolation and neither needs a clock:
 *
 *   1. {@link #parse} reads the text and returns a {@link Result} -- the task, and a time
 *      SPECIFICATION (in N minutes, at a clock time, on a weekday, on a date, or a relative day).
 *   2. {@link #delayMinutes} turns a specification into a concrete delay from now, given the
 *      current clock (and, for an absolute date, the current date) passed in. The Android layer
 *      supplies those and never has to parse anything.
 *
 * The grammar is deliberately forgiving now, because the owner found the old one too strict to be
 * useful: it understands the forms people actually text -- "remind me at 5", "tomorrow 5pm",
 * "tmrw", "day after", "on monday", "25/12", "25 dec", "in 2 hours" -- plus common shortforms and
 * typos for today/tomorrow. Anything it still cannot read returns no time, which the responder
 * turns into a question back to the sender that states the formats it understands.
 */
public final class ReminderParser {

    /** How the time was expressed, or that it was not. */
    public enum When {
        /** No time found. The responder will ask the sender. */
        NONE,
        /** A fixed number of minutes from now. */
        IN_MINUTES,
        /** A clock time; {@link Result#daysAhead} days from now (0 = today/next, 1 = tomorrow). */
        AT_TIME,
        /** A weekday; the next occurrence, optionally at a clock time. */
        ON_WEEKDAY,
        /** An absolute calendar date (day + month, year optional), optionally at a clock time. */
        ON_DATE
    }

    /** The parsed reminder. */
    public static final class Result {
        public final String task;
        public final When when;
        /** Clock time as minute-of-day, or -1 when no time given. */
        public final int minuteOfDay;
        /** IN_MINUTES only: the minutes from now. */
        public final int minutes;
        /** ON_WEEKDAY only: 1=Sunday..7=Saturday, Calendar style. */
        public final int weekday;
        /** AT_TIME: the clock time is "tomorrow" (kept for compatibility; see daysAhead). */
        public final boolean tomorrow;
        /** AT_TIME: whole days ahead -- 0 today/next, 1 tomorrow, 2 day after tomorrow. */
        public final int daysAhead;
        /** ON_DATE only: day of month 1..31. */
        public final int day;
        /** ON_DATE only: month 1..12. */
        public final int month;
        /** ON_DATE only: four-digit year, or -1 when unspecified. */
        public final int year;

        Result(String task, When when, int minuteOfDay, int minutes, int weekday,
               boolean tomorrow, int daysAhead, int day, int month, int year) {
            this.task = task == null ? "" : task;
            this.when = when;
            this.minuteOfDay = minuteOfDay;
            this.minutes = minutes;
            this.weekday = weekday;
            this.tomorrow = tomorrow;
            this.daysAhead = daysAhead;
            this.day = day;
            this.month = month;
            this.year = year;
        }

        public boolean hasTime() { return when != When.NONE; }
    }

    private static Result none(String task) {
        return new Result(task, When.NONE, -1, 0, -1, false, 0, -1, -1, -1);
    }

    /**
     * Trigger phrases, broad on purpose. The owner asked that it not be limited to "remind Suvra
     * to" -- anything a person texts him that is plainly a thing to remember should be caught.
     */
    private static final List<String> TRIGGERS = Arrays.asList(
            "remind me to", "remind me", "remind him to", "remind him",
            "remind suvra to", "remind suvra", "remind stg to", "remind stg",
            "reminder to", "reminder", "set a reminder to", "set a reminder",
            "set an alarm to", "set an alarm", "set alarm",
            "don't forget to", "dont forget to", "don t forget to", "do not forget to",
            "don't let me forget to", "dont let me forget to", "don t let me forget to",
            "please remember to", "remember to", "please remember",
            "make a note to", "make a note", "note to self to", "note to self",
            "note that you need to", "ping me to", "ping me", "alert me to", "alert me",
            "wake me up to", "wake me to", "nudge me to", "nudge me",
            "make sure i", "make sure to", "remind");

    private static final String[] WEEKDAYS = {
            "", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    private static final String[] MONTHS = {
            "", "january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december"};
    private static final String[] MONTHS_SHORT = {
            "", "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug",
            "sep", "oct", "nov", "dec"};

    // "in 2 hours", "in 30 minutes", "in 1 hr", "in 15 mins", "in 2 days"
    private static final Pattern IN_PATTERN = Pattern.compile(
            "\\bin\\s+(\\d{1,3})\\s*(min|mins|minute|minutes|hr|hrs|hour|hours|day|days)\\b");
    // "at 5", "at 5pm", "at 5:30", "at 17:00", "at 5 pm"
    private static final Pattern AT_PATTERN = Pattern.compile(
            "\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b");
    // bare "5pm", "5:30pm", "11 am"
    private static final Pattern AMPM_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b");
    // bare 24-hour "17:00"
    private static final Pattern H24_PATTERN = Pattern.compile(
            "\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    // "25/12", "25-12", "25.12", optional year
    private static final Pattern NUM_DATE = Pattern.compile(
            "\\b(\\d{1,2})[/\\-.](\\d{1,2})(?:[/\\-.](\\d{2,4}))?\\b");

    private ReminderParser() { }

    /** Parse a message into a reminder. */
    public static Result parse(String raw) {
        if (raw == null) return none("");
        String lower = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty()) return none("");

        String task = extractTask(raw);

        // ─── Clock time (shared by several forms) ───
        int clock = -1;
        Matcher at = AT_PATTERN.matcher(lower);
        if (at.find()) {
            clock = clockToMinuteOfDay(at.group(1), at.group(2), at.group(3));
        }
        if (clock < 0) {
            Matcher ap = AMPM_PATTERN.matcher(lower);
            if (ap.find()) clock = clockToMinuteOfDay(ap.group(1), ap.group(2), ap.group(3));
        }
        if (clock < 0) {
            Matcher h = H24_PATTERN.matcher(lower);
            if (h.find()) clock = clockToMinuteOfDay(h.group(1), h.group(2), null);
        }

        // ─── "in N units" is the most explicit, so it wins ───
        Matcher in = IN_PATTERN.matcher(lower);
        if (in.find()) {
            int n = safeInt(in.group(1));
            String unit = in.group(2);
            int minutes;
            if (unit.startsWith("hr") || unit.startsWith("hour")) minutes = n * 60;
            else if (unit.startsWith("day")) minutes = n * 60 * 24;
            else minutes = n;
            return new Result(task, When.IN_MINUTES, -1, minutes, -1, false, 0, -1, -1, -1);
        }

        // ─── Absolute date: "25/12", "25 dec", "dec 25" ───
        int[] date = findDate(lower);
        if (date != null) {
            return new Result(task, When.ON_DATE, clock, 0, -1, false, 0,
                    date[0], date[1], date[2]);
        }

        // ─── Weekday: "on monday", "next friday" ───
        int wd = weekdayIn(lower);
        if (wd > 0) {
            return new Result(task, When.ON_WEEKDAY, clock, 0, wd, false, 0, -1, -1, -1);
        }

        // ─── Relative day words, with shortforms and typo tolerance ───
        int rel = relativeDay(lower);
        if (rel == 2) {
            return new Result(task, When.AT_TIME, clock, 0, -1, false, 2, -1, -1, -1);
        }
        if (rel == 1) {
            return new Result(task, When.AT_TIME, clock, 0, -1, true, 1, -1, -1, -1);
        }
        if (rel == 0) {
            return new Result(task, When.AT_TIME, clock, 0, -1, false, 0, -1, -1, -1);
        }

        // ─── Tonight ───
        if (containsWord(lower, "tonight")) {
            return new Result(task, When.AT_TIME, clock >= 0 ? clock : 20 * 60, 0, -1,
                    false, 0, -1, -1, -1);
        }

        // ─── A bare clock time ───
        if (clock >= 0) {
            return new Result(task, When.AT_TIME, clock, 0, -1, false, 0, -1, -1, -1);
        }

        return none(task);
    }

    /**
     * Minutes from now until the reminder is due, given only the clock. Absolute dates need the
     * date overload; here they resolve to -1 so the caller asks instead.
     */
    public static int delayMinutes(Result r, int nowMinuteOfDay, int nowWeekday) {
        return delayMinutes(r, nowMinuteOfDay, nowWeekday, -1, -1, -1);
    }

    /**
     * Minutes from now until the reminder is due.
     *
     * Pure: the Android layer passes the current clock and date, so this can be tested with any
     * "now". Never returns less than 1 for a timed reminder.
     *
     * @param nowMinuteOfDay minutes since local midnight, 0..1439
     * @param nowWeekday     1=Sunday..7=Saturday, Calendar style
     * @param nowDay         day of month 1..31, or -1 when not supplied (ON_DATE then returns -1)
     * @param nowMonth       month 1..12, or -1
     * @param nowYear        four-digit year, or -1
     */
    public static int delayMinutes(Result r, int nowMinuteOfDay, int nowWeekday,
                                   int nowDay, int nowMonth, int nowYear) {
        if (r == null || r.when == When.NONE) return -1;
        switch (r.when) {
            case IN_MINUTES:
                return Math.max(1, r.minutes);
            case AT_TIME: {
                int target = r.minuteOfDay >= 0 ? r.minuteOfDay : 9 * 60; // default 9am
                int ahead = r.daysAhead > 0 ? r.daysAhead : (r.tomorrow ? 1 : 0);
                if (ahead > 0) return ahead * 1440 - nowMinuteOfDay + target;
                int delta = target - nowMinuteOfDay;
                if (delta <= 0) delta += 1440; // already past today -> tomorrow
                return delta;
            }
            case ON_WEEKDAY: {
                int target = r.minuteOfDay >= 0 ? r.minuteOfDay : 9 * 60;
                int days = (r.weekday - nowWeekday + 7) % 7;
                if (days == 0) {
                    if (target - nowMinuteOfDay > 0) return target - nowMinuteOfDay;
                    return 7 * 1440 + (target - nowMinuteOfDay);
                }
                return days * 1440 + (target - nowMinuteOfDay);
            }
            case ON_DATE: {
                if (nowDay <= 0 || nowMonth <= 0 || nowYear <= 0) return -1;
                int target = r.minuteOfDay >= 0 ? r.minuteOfDay : 9 * 60;
                int y = r.year > 0 ? r.year : nowYear;
                long nowEpoch = daysFromCivil(nowYear, nowMonth, nowDay);
                long tgtEpoch = daysFromCivil(y, r.month, r.day);
                long delay = (tgtEpoch - nowEpoch) * 1440L + (target - nowMinuteOfDay);
                // No year given and the date already passed this year -> next year.
                if (r.year <= 0 && delay <= 0) {
                    tgtEpoch = daysFromCivil(nowYear + 1, r.month, r.day);
                    delay = (tgtEpoch - nowEpoch) * 1440L + (target - nowMinuteOfDay);
                }
                if (delay < 1) delay = 1;
                return (int) Math.min(delay, Integer.MAX_VALUE);
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
        out = out.replaceAll("(?i)\\s+\\d{1,2}(?::\\d{2})?\\s*(am|pm)\\s*$", "");
        out = out.replaceAll("(?i)\\s+(in\\s+\\d{1,3}\\s*(min|mins|minute|minutes|hr|hrs|hour|"
                + "hours|day|days))\\s*$", "");
        out = out.replaceAll("(?i)\\s+(day after tomorrow|day after|tomorrow|tonight|today|"
                + "tmrw|tmr|tmro|tomo|2moro|2morrow|tdy|2day)\\s*$", "");
        out = out.replaceAll("(?i)\\s+(on\\s+|next\\s+)?(sunday|monday|tuesday|wednesday|thursday|"
                + "friday|saturday)\\s*$", "");
        out = out.replaceAll("(?i)\\s+\\d{1,2}[/\\-.]\\d{1,2}([/\\-.]\\d{2,4})?\\s*$", "");
        out = out.replaceAll("[\\s,\\.]+$", "").trim();
        return out.isEmpty() ? task.trim() : out;
    }

    /**
     * Relative-day offset: 0 = today, 1 = tomorrow, 2 = day after tomorrow, -1 = none.
     *
     * Day-after is checked first, since its phrases contain "tomorrow". Tomorrow and today accept
     * common shortforms and are typo-tolerant (edit distance to the full word), because they are
     * the two the owner most wanted forgiving.
     */
    private static int relativeDay(String lower) {
        if (lower.contains("day after tomorrow") || lower.contains("day after")
                || containsWord(lower, "overmorrow")) {
            return 2;
        }
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(
                lower.replaceAll("[^a-z0-9]", " ").trim().split("\\s+")));
        Set<String> tomo = new LinkedHashSet<>(Arrays.asList(
                "tomorrow", "tmrw", "tmr", "tmro", "tmrrw", "tomo", "tomoro", "tomorow",
                "tommorow", "tommorrow", "2moro", "2morrow", "2mrw", "2moro", "tomorrw"));
        Set<String> tdy = new LinkedHashSet<>(Arrays.asList(
                "today", "tdy", "2day", "tday", "todai", "tody"));
        for (String t : tokens) {
            if (tomo.contains(t) || fuzzy(t, "tomorrow", 2)) return 1;
        }
        for (String t : tokens) {
            if (tdy.contains(t) || fuzzy(t, "today", 1)) return 0;
        }
        return -1;
    }

    /** Find an absolute date, returning {day, month, year(-1 if none)} or null. */
    private static int[] findDate(String lower) {
        // Numeric "25/12" or "25-12-2026".
        Matcher m = NUM_DATE.matcher(lower);
        if (m.find()) {
            int a = safeInt(m.group(1));
            int b = safeInt(m.group(2));
            int y = m.group(3) == null ? -1 : normaliseYear(safeInt(m.group(3)));
            // Day/month, India style. If the first number can't be a day but the second can, swap.
            int day = a, month = b;
            if ((day > 31 || month > 12) && a <= 12 && b <= 31) { day = b; month = a; }
            if (day >= 1 && day <= 31 && month >= 1 && month <= 12) {
                return new int[]{day, month, y};
            }
        }
        // Month name forms: "25 dec", "dec 25", "25th december".
        for (int mo = 1; mo <= 12; mo++) {
            String full = MONTHS[mo], abbr = MONTHS_SHORT[mo];
            Matcher dm = Pattern.compile(
                    "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + full + "|" + abbr + ")\\b").matcher(lower);
            if (dm.find()) {
                int d = safeInt(dm.group(1));
                if (d >= 1 && d <= 31) return new int[]{d, mo, -1};
            }
            Matcher md = Pattern.compile(
                    "\\b(" + full + "|" + abbr + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b").matcher(lower);
            if (md.find()) {
                int d = safeInt(md.group(2));
                if (d >= 1 && d <= 31) return new int[]{d, mo, -1};
            }
        }
        return null;
    }

    private static int normaliseYear(int y) {
        if (y < 0) return -1;
        if (y < 100) return 2000 + y;
        return y;
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

    /** Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm). Pure, leap-correct. */
    static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** Edit-distance match with a cap, used for typo-tolerant day words. */
    private static boolean fuzzy(String token, String word, int max) {
        if (token == null || token.length() < 3) return false;
        if (Math.abs(token.length() - word.length()) > max) return false;
        if (token.charAt(0) != word.charAt(0)) return false;
        return editDistance(token, word) <= max;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
