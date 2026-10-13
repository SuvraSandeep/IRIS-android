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
