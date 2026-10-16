package com.iris.assistant;

/**
 * A short, time-and-day-aware context line IRIS can prepend to a reply, so it feels aware of
 * whether it's Friday evening, the weekend, the small hours, or a Monday-morning rush \u2014 without
 * any clock API. Pure and offline-testable: the caller passes the day-of-week and hour.
 *
 * Returns "" when there's nothing special about the moment, so the caller only prepends when there
 * is real flavour to add. {owner} is left literal for the caller's fill().
 */
public final class TimeFlavor {

    private TimeFlavor() { }

    /**
     * @param dayOfWeek java.util.Calendar style: 1=Sunday .. 7=Saturday
     * @param hour      0..23
     */
    public static String of(int dayOfWeek, int hour) {
        boolean weekend = dayOfWeek == 1 || dayOfWeek == 7;      // Sun or Sat
        boolean friday = dayOfWeek == 6;
        boolean monday = dayOfWeek == 2;

        if (hour < 6) {
            return "It's the middle of the night \u2014 {owner} is almost certainly asleep, but I run "
                    + "24/7. I'm IRIS, his assistant.";
        }
        if (friday && hour >= 17) {
            return "It's Friday evening, so {owner} is officially unplugging \u2014 I'm IRIS, guarding "
                    + "the inbox.";
        }
        if (weekend) {
            return "It's the weekend \u2014 {owner} is taking a screen break, so you've got IRIS on "
                    + "duty.";
        }
        if (monday && hour < 12) {
            return "Monday-morning rush \u2014 {owner} is tied up, so I'm catching his messages. I'm "
                    + "IRIS.";
        }
        if (hour >= 22) {
            return "It's late \u2014 {owner} is winding down for the night, but I'm IRIS and I don't "
                    + "sleep.";
        }
        return "";
    }

    /** True when there is special flavour for this moment. */
    public static boolean has(int dayOfWeek, int hour) {
        return !of(dayOfWeek, hour).isEmpty();
    }
}
