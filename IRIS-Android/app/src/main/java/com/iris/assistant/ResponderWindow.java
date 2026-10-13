package com.iris.assistant;

import java.util.List;
import java.util.Locale;

/**
 * Time windows for the auto-responder: quiet hours, work hours, and any the owner adds.
 *
 * Pure, with no Android imports and no date classes at all. Everything is expressed as minutes
 * since local midnight, 0 to 1439, and the Android caller converts a timestamp once before calling
 * in. That split is deliberate: it means this class cannot be wrong about time zones or daylight
 * saving, because it never sees them, and every boundary decision is checkable offline.
 *
 * The case that drove the design is the owner's default quiet hours of 23:00 to 07:00. That window
 * **wraps past midnight**, so the naive test of start <= now && now <= end is false for the entire
 * window and would silently never fire.
 */
public final class ResponderWindow {

    public static final int MINUTES_PER_DAY = 24 * 60;

    /** Quiet hours default, 23:00 to 07:00. Customisable. */
    public static final int QUIET_START_DEFAULT = 23 * 60;
    public static final int QUIET_END_DEFAULT = 7 * 60;

    /** Work hours default, 09:00 to 18:00. Customisable. */
    public static final int WORK_START_DEFAULT = 9 * 60;
    public static final int WORK_END_DEFAULT = 18 * 60;

    /** A named window, optionally forcing a tone while it is active. */
    public static final class Window {
        public final String name;
        public final int startMinute;
        public final int endMinute;
        /** Tone to force while active, or null to leave the tone alone. */
        public final ReplyTone.Tone tone;

        public Window(String name, int startMinute, int endMinute, ReplyTone.Tone tone) {
            this.name = name == null ? "" : name;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.tone = tone;
        }
    }

    private ResponderWindow() { }

    /** Whether a minute-of-day value is a real time. */
    public static boolean isValidMinute(int minute) {
        return minute >= 0 && minute < MINUTES_PER_DAY;
    }

    /**
     * Whether a window is usable.
     *
     * A window whose start equals its end is rejected rather than treated as covering the whole
     * day. "Always quiet" would mean every single message received the sleeping reply, which is a
     * far worse failure than a window that never fires -- and a start equal to an end is much more
     * likely to be a half-finished edit than a deliberate 24-hour window.
     */
    public static boolean isValidWindow(int startMinute, int endMinute) {
        return isValidMinute(startMinute) && isValidMinute(endMinute) && startMinute != endMinute;
    }

    /**
     * Whether now falls inside the window, handling the wrap past midnight.
     *
     * The start is inclusive and the end is exclusive, so 23:00-07:00 includes 23:00 and excludes
     * 07:00 exactly. Without that, two adjacent windows would both claim the shared boundary
     * minute.
     *
     * Any invalid input means not active. A broken clock reading or a half-edited window produces
     * ordinary replies rather than wrong ones.
     */
    public static boolean contains(int startMinute, int endMinute, int nowMinute) {
        if (!isValidWindow(startMinute, endMinute)) return false;
        if (!isValidMinute(nowMinute)) return false;

        if (startMinute < endMinute) {
            // Ordinary window inside one day, such as 09:00-18:00.
            return nowMinute >= startMinute && nowMinute < endMinute;
        }
        // Wrapping window such as 23:00-07:00: active late in the evening OR early next morning.
        return nowMinute >= startMinute || nowMinute < endMinute;
    }

    /** Whether quiet hours are active. */
    public static boolean isQuiet(int quietStart, int quietEnd, int nowMinute) {
        return contains(quietStart, quietEnd, nowMinute);
    }

    /**
     * Minutes from now until a target time of day, crossing midnight when needed.
     *
     * At 23:30 with a target of 07:00 the answer is 450, not -990. Returning a negative number
     * here is the specific bug this exists to prevent, because it would reach a reply template as
     * "he'll be up in -16 hours".
     *
     * @return minutes, or -1 if either input is not a real time
     */
    public static int minutesUntil(int targetMinute, int nowMinute) {
        if (!isValidMinute(targetMinute) || !isValidMinute(nowMinute)) return -1;
        if (targetMinute > nowMinute) return targetMinute - nowMinute;
        // Already past today, so the target is tomorrow. Equal values mean a full day away.
        return MINUTES_PER_DAY - nowMinute + targetMinute;
    }

    /**
     * Whole hours until a target time, floored, never below one.
     *
     * Floored so IRIS never overstates the wait, and floored to a minimum of one so a target
     * forty minutes away reads as "about an hour" rather than "0 hours".
     *
     * @return hours, or -1 if either input is not a real time
     */
    public static int hoursUntil(int targetMinute, int nowMinute) {
        int minutes = minutesUntil(targetMinute, nowMinute);
        if (minutes < 0) return -1;
        return Math.max(1, minutes / 60);
    }

    /**
     * The first active window in the list, or null.
     *
     * First rather than best match, so ordering is meaningful and the owner can put a specific
     * window above a broad one.
     */
    public static Window activeWindow(List<Window> windows, int nowMinute) {
        if (windows == null) return null;
        for (Window window : windows) {
            if (window == null) continue;
            if (contains(window.startMinute, window.endMinute, nowMinute)) return window;
        }
        return null;
    }

    /**
     * Tone forced by the first active window, or null.
     *
     * Null rather than a default, so the caller's resolution order can fall through to the global
     * default instead of this quietly winning.
     */
    public static ReplyTone.Tone toneFor(List<Window> windows, int nowMinute) {
        Window active = activeWindow(windows, nowMinute);
        return active == null ? null : active.tone;
    }

    /**
     * Part of day, for the {part_of_day} placeholder in a reply.
     *
     * Night deliberately spans the midnight wrap, so 23:30 and 02:00 both read as night.
     */
    public static String partOfDay(int nowMinute) {
        if (!isValidMinute(nowMinute)) return "day";
        if (nowMinute >= 5 * 60 && nowMinute < 12 * 60) return "morning";
        if (nowMinute >= 12 * 60 && nowMinute < 17 * 60) return "afternoon";
        if (nowMinute >= 17 * 60 && nowMinute < 21 * 60) return "evening";
        return "night";
    }

    /** Format a minute-of-day as 24-hour HH:MM, for display and for reply text. */
    public static String formatMinute(int minute) {
        if (!isValidMinute(minute)) return "--:--";
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    /**
     * Parse "HH:MM" into minutes since midnight.
     *
     * @return minutes, or -1 if the text is not a valid time. Never throws, because this reads
     *         owner-edited settings and a typo must not crash the responder.
     */
    public static int parseMinute(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) return -1;
        try {
            int hours = Integer.parseInt(trimmed.substring(0, colon).trim());
            int minutes = Integer.parseInt(trimmed.substring(colon + 1).trim());
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return -1;
            return hours * 60 + minutes;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
