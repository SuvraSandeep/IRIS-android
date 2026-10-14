package com.iris.assistant;

/** Offline checks for reminder parsing and the pure delay resolution. */
public final class ReminderParserTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + b + "\n  actual:   " + a);
        }
    }

    public static void main(String[] args) {
        relativeTimes();
        clockTimes();
        tomorrowAndTonight();
        shortformsAndTypos();
        bareTimes();
        absoluteDates();
        broaderTriggers();
        weekdays();
        noTime();
        taskExtraction();
        delayNeverInThePast();
        System.out.println("Passed " + checks + " reminder parser checks");
    }

    private static void relativeTimes() {
        ReminderParser.Result r = ReminderParser.parse("remind me to call in 2 hours");
        equal(r.when, ReminderParser.When.IN_MINUTES, "in 2 hours is relative");
        equal(r.minutes, 120, "two hours is 120 minutes");
        equal(ReminderParser.delayMinutes(r, 600, 4), 120, "delay is 120 regardless of now");

        ReminderParser.Result m = ReminderParser.parse("don't forget to stretch in 30 minutes");
        equal(m.minutes, 30, "30 minutes");
        ReminderParser.Result d = ReminderParser.parse("remind me to renew in 3 days");
        equal(d.minutes, 3 * 24 * 60, "3 days in minutes");
    }

    private static void clockTimes() {
        ReminderParser.Result five = ReminderParser.parse("remind me to call at 5");
        equal(five.when, ReminderParser.When.AT_TIME, "at 5 is a clock time");
        equal(five.minuteOfDay, 5 * 60, "5 is 05:00 without am/pm");
        ReminderParser.Result fivePm = ReminderParser.parse("remind me to call at 5pm");
        equal(fivePm.minuteOfDay, 17 * 60, "5pm is 17:00");
        ReminderParser.Result half = ReminderParser.parse("remind me at 5:30pm to leave");
        equal(half.minuteOfDay, 17 * 60 + 30, "5:30pm is 17:30");
        ReminderParser.Result mil = ReminderParser.parse("remind me at 17:00 to leave");
        equal(mil.minuteOfDay, 17 * 60, "17:00 is 17:00");
        ReminderParser.Result noon = ReminderParser.parse("remind me at 12pm to eat");
        equal(noon.minuteOfDay, 12 * 60, "12pm is noon");
        ReminderParser.Result mid = ReminderParser.parse("remind me at 12am to sleep");
        equal(mid.minuteOfDay, 0, "12am is midnight");

        // Next occurrence: 17:00 when it's 10:00 is 7 hours; when it's 18:00 it's tomorrow.
        equal(ReminderParser.delayMinutes(fivePm, 10 * 60, 4), 7 * 60, "17:00 later today");
        equal(ReminderParser.delayMinutes(fivePm, 18 * 60, 4), (1440 - 18 * 60) + 17 * 60,
                "17:00 already past rolls to tomorrow");
    }

    private static void tomorrowAndTonight() {
        ReminderParser.Result t = ReminderParser.parse("remind me to pay rent tomorrow");
        equal(t.when, ReminderParser.When.AT_TIME, "tomorrow is a timed reminder");
        check(t.tomorrow, "tomorrow flag set");
        // No time given -> default 9am tomorrow.
        equal(ReminderParser.delayMinutes(t, 10 * 60, 4), (1440 - 10 * 60) + 9 * 60,
                "tomorrow with no time defaults to 9am tomorrow");
        ReminderParser.Result tt = ReminderParser.parse("remind me to call tomorrow at 8am");
        check(tt.tomorrow, "tomorrow at 8am keeps the flag");
        equal(tt.minuteOfDay, 8 * 60, "8am parsed");

        ReminderParser.Result night = ReminderParser.parse("remind me to lock up tonight");
        equal(night.minuteOfDay, 20 * 60, "tonight defaults to 8pm");
    }

    private static void shortformsAndTypos() {
        check(ReminderParser.parse("remind me to call tmrw").tomorrow, "tmrw is tomorrow");
        check(ReminderParser.parse("remind me to call tomorow").tomorrow, "typo tomorow");
        check(ReminderParser.parse("remind me to call 2moro").tomorrow, "2moro is tomorrow");
        ReminderParser.Result today = ReminderParser.parse("remind me to call tdy at 5pm");
        equal(today.when, ReminderParser.When.AT_TIME, "tdy is today, a timed reminder");
        equal(today.daysAhead, 0, "today is zero days ahead");
        equal(today.minuteOfDay, 17 * 60, "5pm on tdy");
        ReminderParser.Result da = ReminderParser.parse("remind me to call day after tomorrow");
        equal(da.daysAhead, 2, "day after tomorrow is two days ahead");
        equal(ReminderParser.delayMinutes(da, 10 * 60, 4), 2 * 1440 - 10 * 60 + 9 * 60,
                "day after tomorrow defaults to 9am two days out");
    }

    private static void bareTimes() {
        ReminderParser.Result r = ReminderParser.parse("remind me to call 5pm");
        equal(r.when, ReminderParser.When.AT_TIME, "bare 5pm is a time");
        equal(r.minuteOfDay, 17 * 60, "bare 5pm is 17:00");
        ReminderParser.Result tm = ReminderParser.parse("remind me to call tomorrow 8am");
        check(tm.tomorrow, "tomorrow 8am without 'at'");
        equal(tm.minuteOfDay, 8 * 60, "bare 8am parsed");
    }

    private static void absoluteDates() {
        ReminderParser.Result slash = ReminderParser.parse("remind me to pay on 25/12 at 6pm");
        equal(slash.when, ReminderParser.When.ON_DATE, "25/12 is a date");
        equal(slash.day, 25, "day 25");
        equal(slash.month, 12, "month 12");
        equal(slash.minuteOfDay, 18 * 60, "6pm on the date");
        // From 20 Dec 2026 09:00 to 25 Dec 2026 18:00.
        equal(ReminderParser.delayMinutes(slash, 9 * 60, 1, 20, 12, 2026),
                5 * 1440 + (18 * 60 - 9 * 60), "five days and nine hours to 25 Dec 6pm");

        ReminderParser.Result dm = ReminderParser.parse("remind me to wish him 25 dec");
        equal(dm.when, ReminderParser.When.ON_DATE, "25 dec is a date");
        equal(dm.day, 25, "25 dec day");
        equal(dm.month, 12, "25 dec month");
        ReminderParser.Result md = ReminderParser.parse("remind me to wish him dec 25");
        equal(md.day, 25, "dec 25 day");
        equal(md.month, 12, "dec 25 month");

        // Without the date overload, ON_DATE cannot resolve, so the responder asks.
        equal(ReminderParser.delayMinutes(slash, 9 * 60, 1), -1,
                "a date needs the date overload to resolve");

        // A date already past this year rolls to next year.
        ReminderParser.Result jan = ReminderParser.parse("remind me on 1/1");
        check(ReminderParser.delayMinutes(jan, 9 * 60, 1, 25, 12, 2026) > 0,
                "1 Jan from 25 Dec rolls into next year, positive");
    }

    private static void broaderTriggers() {
        equal(ReminderParser.parse("set an alarm to take meds at 8am").when,
                ReminderParser.When.AT_TIME, "set an alarm is a reminder time");
        check(!ReminderParser.parse("ping me to review the doc tomorrow").task.isEmpty(),
                "ping me extracts a task");
        check(ReminderParser.parse("reminder submit form tomorrow").tomorrow,
                "reminder ... tomorrow");
    }

    private static void weekdays() {
        ReminderParser.Result mon = ReminderParser.parse("remind me to email on monday");
        equal(mon.when, ReminderParser.When.ON_WEEKDAY, "monday is a weekday reminder");
        equal(mon.weekday, 2, "monday is Calendar day 2");
        // If today is Sunday (1), Monday is 1 day away at 9am default.
        equal(ReminderParser.delayMinutes(mon, 8 * 60, 1), 1440 + (9 * 60 - 8 * 60),
                "monday from sunday morning");
        ReminderParser.Result monAt = ReminderParser.parse("remind me on monday at 10am to pay");
        equal(monAt.minuteOfDay, 10 * 60, "monday at 10am");

        // Same weekday, time still ahead -> today.
        ReminderParser.Result fri = ReminderParser.parse("remind me friday at 6pm to call");
        equal(ReminderParser.delayMinutes(fri, 9 * 60, 6), 18 * 60 - 9 * 60,
                "friday 6pm when it's friday 9am is today");
        // Same weekday, time passed -> a week away.
        equal(ReminderParser.delayMinutes(fri, 19 * 60, 6), 7 * 1440 + (18 * 60 - 19 * 60),
                "friday 6pm when it's friday 7pm is next week");
    }

    private static void noTime() {
        ReminderParser.Result r = ReminderParser.parse("remind me to call the plumber");
        equal(r.when, ReminderParser.When.NONE, "no time found");
        check(!r.hasTime(), "hasTime is false");
        equal(ReminderParser.delayMinutes(r, 600, 4), -1, "no delay without a time");
    }

    private static void taskExtraction() {
        equal(ReminderParser.parse("remind me to call the plumber").task, "call the plumber",
                "task after 'remind me to'");
        equal(ReminderParser.parse("don't forget to send the report at 5pm").task,
                "send the report", "trailing time stripped from task");
        equal(ReminderParser.parse("remind me to pay rent tomorrow").task, "pay rent",
                "trailing 'tomorrow' stripped");
        equal(ReminderParser.parse("remind me to email on monday").task, "email",
                "trailing weekday stripped");
        // No recognisable trigger split still yields a usable task rather than empty.
        check(!ReminderParser.parse("set a reminder to water plants in 2 hours").task.isEmpty(),
                "task is never empty when there is content");
    }

    private static void delayNeverInThePast() {
        // A clock time already gone today, no day word, rolls forward, never negative or zero.
        ReminderParser.Result r = ReminderParser.parse("remind me to call at 9am");
        int delay = ReminderParser.delayMinutes(r, 10 * 60, 4);
        check(delay > 0, "a past clock time rolls to tomorrow, never non-positive");
        equal(delay, (1440 - 10 * 60) + 9 * 60, "9am when it's 10am is 23 hours away");
    }
}
