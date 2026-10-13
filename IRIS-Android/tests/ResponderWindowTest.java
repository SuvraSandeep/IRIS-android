package com.iris.assistant;

import java.util.Arrays;

/**
 * Offline checks for the responder's time windows.
 *
 * The owner's default quiet hours are 23:00 to 07:00, which wrap past midnight. A naive
 * start <= now && now <= end test is false for every minute of that window, so quiet hours would
 * silently never fire and every 3am message would get a normal reply. That single case is why this
 * class exists and most of these checks guard it.
 *
 * The second guarded failure is a negative duration. "He'll be up in -16 hours" is what reaches a
 * recipient if minutesUntil does not cross midnight properly.
 */
public class ResponderWindowTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    private static int at(int hour, int minute) { return hour * 60 + minute; }

    public static void main(String[] args) {
        quietHoursWrapPastMidnight();
        ordinaryWindowsDoNotWrap();
        boundariesAreStartInclusiveEndExclusive();
        zeroLengthWindowsNeverFire();
        brokenInputMeansNoWindow();
        minutesUntilCrossesMidnight();
        hoursUntilIsFlooredButNeverZero();
        activeWindowPicksTheFirstMatch();
        windowToneFallsThroughWhenInactive();
        partOfDayCoversTheWholeClock();
        formattingAndParsing();
        System.out.println("Passed " + checks + " responder window checks");
    }

    /** The owner's real default, and the whole reason this class is not two comparisons. */
    private static void quietHoursWrapPastMidnight() {
        int start = ResponderWindow.QUIET_START_DEFAULT;
        int end = ResponderWindow.QUIET_END_DEFAULT;

        check(ResponderWindow.isQuiet(start, end, at(23, 0)), "23:00 is quiet, at the boundary");
        check(ResponderWindow.isQuiet(start, end, at(23, 30)), "23:30 is quiet");
        check(ResponderWindow.isQuiet(start, end, at(0, 0)), "midnight exactly is quiet");
        check(ResponderWindow.isQuiet(start, end, at(2, 15)), "2:15am is quiet");
        check(ResponderWindow.isQuiet(start, end, at(6, 59)), "6:59am is still quiet");

        check(!ResponderWindow.isQuiet(start, end, at(7, 0)),
                "7:00 exactly is NOT quiet, since the end is exclusive");
        check(!ResponderWindow.isQuiet(start, end, at(12, 0)), "midday is not quiet");
        check(!ResponderWindow.isQuiet(start, end, at(18, 30)), "evening is not quiet");
        check(!ResponderWindow.isQuiet(start, end, at(22, 59)),
                "22:59 is not yet quiet, one minute before the start");
    }

    private static void ordinaryWindowsDoNotWrap() {
        int start = ResponderWindow.WORK_START_DEFAULT;
        int end = ResponderWindow.WORK_END_DEFAULT;
        check(ResponderWindow.contains(start, end, at(9, 0)), "09:00 is work time");
        check(ResponderWindow.contains(start, end, at(13, 30)), "early afternoon is work time");
        check(ResponderWindow.contains(start, end, at(17, 59)), "17:59 is work time");
        check(!ResponderWindow.contains(start, end, at(18, 0)), "18:00 exactly is not work time");
        check(!ResponderWindow.contains(start, end, at(8, 59)), "08:59 is not yet work time");
        check(!ResponderWindow.contains(start, end, at(2, 0)),
                "2am is not work time, and a non-wrapping window must not accidentally wrap");
    }

    private static void boundariesAreStartInclusiveEndExclusive() {
        // Two adjacent windows must not both claim the shared minute.
        check(ResponderWindow.contains(at(9, 0), at(12, 0), at(9, 0)), "start is inclusive");
        check(!ResponderWindow.contains(at(9, 0), at(12, 0), at(12, 0)), "end is exclusive");
        check(ResponderWindow.contains(at(12, 0), at(15, 0), at(12, 0)),
                "the next window owns the boundary minute, with no overlap");
    }

    /**
     * A start equal to an end is refused rather than treated as all day. "Always quiet" would send
     * the sleeping reply to every message, which is far worse than a window that never fires, and
     * it is much more likely to be a half-finished edit than a deliberate 24-hour window.
     */
    private static void zeroLengthWindowsNeverFire() {
        check(!ResponderWindow.isValidWindow(at(9, 0), at(9, 0)),
                "a zero-length window is invalid");
        check(!ResponderWindow.contains(at(9, 0), at(9, 0), at(9, 0)),
                "a zero-length window does not contain its own start");
        check(!ResponderWindow.contains(at(23, 0), at(23, 0), at(3, 0)),
                "a zero-length window is not treated as covering the whole day");
    }

    private static void brokenInputMeansNoWindow() {
        check(!ResponderWindow.contains(-1, at(7, 0), at(2, 0)), "a negative start is inactive");
        check(!ResponderWindow.contains(at(23, 0), 9999, at(2, 0)), "an absurd end is inactive");
        check(!ResponderWindow.contains(at(23, 0), at(7, 0), -5),
                "a negative clock reading is inactive rather than guessed");
        check(!ResponderWindow.contains(at(23, 0), at(7, 0), ResponderWindow.MINUTES_PER_DAY),
                "a clock reading past the end of the day is inactive");
        check(!ResponderWindow.isValidMinute(ResponderWindow.MINUTES_PER_DAY),
                "1440 is not a valid minute of day");
        check(ResponderWindow.isValidMinute(0) && ResponderWindow.isValidMinute(1439),
                "the real range is 0 to 1439 inclusive");
    }

    /** A negative duration is the bug that would reach a recipient as text. */
    private static void minutesUntilCrossesMidnight() {
        equal(ResponderWindow.minutesUntil(at(7, 0), at(23, 30)), 450,
                "23:30 to 07:00 is 450 minutes forward, not negative");
        equal(ResponderWindow.minutesUntil(at(7, 0), at(2, 0)), 300,
                "2am to 07:00 is 300 minutes, same day");
        equal(ResponderWindow.minutesUntil(at(9, 0), at(8, 30)), 30, "a near target is simple");
        equal(ResponderWindow.minutesUntil(at(7, 0), at(7, 0)),
                ResponderWindow.MINUTES_PER_DAY, "an identical time is a full day away");
        equal(ResponderWindow.minutesUntil(-1, at(2, 0)), -1, "a bad target reports -1");
        equal(ResponderWindow.minutesUntil(at(7, 0), 5000), -1, "a bad clock reports -1");

        // The property that matters, swept across the whole day.
        for (int now = 0; now < ResponderWindow.MINUTES_PER_DAY; now += 7) {
            int minutes = ResponderWindow.minutesUntil(at(7, 0), now);
            check(minutes > 0 && minutes <= ResponderWindow.MINUTES_PER_DAY,
                    "minutes until 07:00 from minute " + now + " is positive and within a day");
        }
    }

    private static void hoursUntilIsFlooredButNeverZero() {
        equal(ResponderWindow.hoursUntil(at(7, 0), at(23, 30)), 7,
                "23:30 to 07:00 reports 7 hours, floored from 7.5 so IRIS never overstates");
        equal(ResponderWindow.hoursUntil(at(7, 0), at(0, 0)), 7, "midnight to 07:00 is 7 hours");
        equal(ResponderWindow.hoursUntil(at(7, 0), at(6, 20)), 1,
                "forty minutes reads as about an hour, never as zero");
        equal(ResponderWindow.hoursUntil(at(7, 0), at(6, 59)), 1,
                "one minute away still reads as an hour rather than nothing");
        equal(ResponderWindow.hoursUntil(-1, at(2, 0)), -1, "bad input reports -1");

        for (int now = 0; now < ResponderWindow.MINUTES_PER_DAY; now += 11) {
            int hours = ResponderWindow.hoursUntil(at(7, 0), now);
            check(hours >= 1 && hours <= 24,
                    "hours until 07:00 from minute " + now + " is between 1 and 24");
        }
    }

    private static void activeWindowPicksTheFirstMatch() {
        java.util.List<ResponderWindow.Window> windows = Arrays.asList(
                new ResponderWindow.Window("work", at(9, 0), at(18, 0),
                        ReplyTone.Tone.PROFESSIONAL),
                new ResponderWindow.Window("evening", at(18, 0), at(23, 0),
                        ReplyTone.Tone.CASUAL));

        ResponderWindow.Window atNoon = ResponderWindow.activeWindow(windows, at(12, 0));
        check(atNoon != null && atNoon.name.equals("work"), "midday finds the work window");

        ResponderWindow.Window atEight = ResponderWindow.activeWindow(windows, at(20, 0));
        check(atEight != null && atEight.name.equals("evening"), "8pm finds the evening window");

        check(ResponderWindow.activeWindow(windows, at(3, 0)) == null,
                "3am matches no configured window");
        check(ResponderWindow.activeWindow(null, at(12, 0)) == null, "a null list is safe");
        check(ResponderWindow.activeWindow(Arrays.asList((ResponderWindow.Window) null),
                        at(12, 0)) == null,
                "a null entry in the list is skipped rather than crashing");
    }

    /**
     * Null when inactive, not a default. The caller's resolution order has to be able to fall
     * through to the global default rather than having a window win silently.
     */
    private static void windowToneFallsThroughWhenInactive() {
        java.util.List<ResponderWindow.Window> windows = Arrays.asList(
                new ResponderWindow.Window("work", at(9, 0), at(18, 0),
                        ReplyTone.Tone.PROFESSIONAL));
        equal(ResponderWindow.toneFor(windows, at(10, 0)), ReplyTone.Tone.PROFESSIONAL,
                "an active window forces its tone");
        equal(ResponderWindow.toneFor(windows, at(22, 0)), null,
                "an inactive window forces nothing");
        equal(ResponderWindow.toneFor(Arrays.asList(
                        new ResponderWindow.Window("any", at(9, 0), at(18, 0), null)), at(10, 0)),
                null, "a window with no tone set forces nothing");
    }

    private static void partOfDayCoversTheWholeClock() {
        equal(ResponderWindow.partOfDay(at(7, 0)), "morning", "7am is morning");
        equal(ResponderWindow.partOfDay(at(11, 59)), "morning", "11:59 is morning");
        equal(ResponderWindow.partOfDay(at(12, 0)), "afternoon", "midday is afternoon");
        equal(ResponderWindow.partOfDay(at(16, 59)), "afternoon", "16:59 is afternoon");
        equal(ResponderWindow.partOfDay(at(17, 0)), "evening", "5pm is evening");
        equal(ResponderWindow.partOfDay(at(20, 59)), "evening", "20:59 is evening");
        equal(ResponderWindow.partOfDay(at(21, 0)), "night", "9pm is night");
        equal(ResponderWindow.partOfDay(at(23, 30)), "night", "23:30 is night");
        equal(ResponderWindow.partOfDay(at(2, 0)), "night",
                "2am is night, so night spans the midnight wrap");
        equal(ResponderWindow.partOfDay(at(4, 59)), "night", "4:59 is still night");
        equal(ResponderWindow.partOfDay(-1), "day", "a broken clock reads as a neutral 'day'");

        // Every minute of the day must produce a usable word.
        for (int now = 0; now < ResponderWindow.MINUTES_PER_DAY; now++) {
            String part = ResponderWindow.partOfDay(now);
            check(part != null && !part.isEmpty(), "minute " + now + " has a part of day");
        }
    }

    private static void formattingAndParsing() {
        equal(ResponderWindow.formatMinute(at(23, 0)), "23:00", "late evening formats");
        equal(ResponderWindow.formatMinute(at(7, 5)), "07:05", "single digits are padded");
        equal(ResponderWindow.formatMinute(0), "00:00", "midnight formats");
        equal(ResponderWindow.formatMinute(-1), "--:--", "an invalid minute formats safely");

        equal(ResponderWindow.parseMinute("23:00"), at(23, 0), "a normal time parses");
        equal(ResponderWindow.parseMinute("07:05"), at(7, 5), "a padded time parses");
        equal(ResponderWindow.parseMinute("7:5"), at(7, 5), "an unpadded time parses");
        equal(ResponderWindow.parseMinute(" 9:30 "), at(9, 30), "padding is tolerated");

        // Owner-edited text, so none of these may throw.
        equal(ResponderWindow.parseMinute("24:00"), -1, "an out-of-range hour is refused");
        equal(ResponderWindow.parseMinute("12:60"), -1, "an out-of-range minute is refused");
        equal(ResponderWindow.parseMinute("-1:00"), -1, "a negative hour is refused");
        equal(ResponderWindow.parseMinute("nonsense"), -1, "free text is refused");
        equal(ResponderWindow.parseMinute("12"), -1, "a time with no colon is refused");
        equal(ResponderWindow.parseMinute(":30"), -1, "a missing hour is refused");
        equal(ResponderWindow.parseMinute("12:"), -1, "a missing minute is refused");
        equal(ResponderWindow.parseMinute(""), -1, "empty is refused");
        equal(ResponderWindow.parseMinute(null), -1, "null is refused");

        // Round trip across the whole day.
        for (int minute = 0; minute < ResponderWindow.MINUTES_PER_DAY; minute++) {
            int parsed = ResponderWindow.parseMinute(ResponderWindow.formatMinute(minute));
            check(parsed == minute, "minute " + minute + " survives a format and parse round trip");
        }
    }
}
