package com.iris.assistant;

/**
 * Offline checks for the notification-listener watchdog.
 *
 * The watchdog exists to notice a binding killed by an OEM battery manager, which Android never
 * reports. Its decisions are entirely about time and state, so they are all checkable here rather
 * than needing a device that happens to kill the service.
 */
public class ListenerWatchdogTest {

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

    public static void main(String[] args) {
        connectedNeverRequests();
        missingAccessNeverRequests();
        firstDisconnectRequestsImmediately();
        backoffIsHonoured();
        backoffGrowsAndIsCapped();
        clockGoingBackwardsCannotFreezeTheWatchdog();
        shortBlipsAreNotReported();
        blindMessageReadsAsADuration();
        intervalsAreSane();
        System.out.println("Passed " + checks + " listener watchdog checks");
    }

    /** The whole point is to do nothing while healthy. */
    private static void connectedNeverRequests() {
        equal(ListenerWatchdog.decide(true, true, 1_000_000L, 0L, 0),
                ListenerWatchdog.Action.NOTHING, "connected does nothing");
        equal(ListenerWatchdog.decide(true, true, 1_000_000L, 1L, 99),
                ListenerWatchdog.Action.NOTHING,
                "connected does nothing even after many past failures");
        equal(ListenerWatchdog.decide(true, false, 1_000_000L, 0L, 0),
                ListenerWatchdog.Action.NOTHING,
                "connected without the grant still does nothing");
    }

    /**
     * A rebind request is ignored by the system without the grant. Pinned because requesting
     * anyway would be pointless work, and would read as nagging for a permission the owner
     * deliberately removed.
     */
    private static void missingAccessNeverRequests() {
        equal(ListenerWatchdog.decide(false, false, 1_000_000L, 0L, 0),
                ListenerWatchdog.Action.NOTHING, "no grant, no request");
        equal(ListenerWatchdog.decide(false, false, 9_000_000L, 1L, 50),
                ListenerWatchdog.Action.NOTHING, "no grant, no request however long it has been");
    }

    private static void firstDisconnectRequestsImmediately() {
        equal(ListenerWatchdog.decide(false, true, 1_000_000L, 0L, 0),
                ListenerWatchdog.Action.REQUEST_REBIND, "never requested before, so request now");
        equal(ListenerWatchdog.decide(false, true, 1_000_000L, -5L, 0),
                ListenerWatchdog.Action.REQUEST_REBIND, "negative last-request time counts as never");
    }

    private static void backoffIsHonoured() {
        long now = 10_000_000L;
        long justRequested = now - 1_000L;
        equal(ListenerWatchdog.decide(false, true, now, justRequested, 1),
                ListenerWatchdog.Action.NOTHING, "a request one second ago is too recent");

        long aMinuteAgo = now - ListenerWatchdog.FIRST_RETRY_MS;
        equal(ListenerWatchdog.decide(false, true, now, aMinuteAgo, 1),
                ListenerWatchdog.Action.REQUEST_REBIND, "exactly one interval later is due");

        long wellPast = now - ListenerWatchdog.MAX_RETRY_MS * 4;
        equal(ListenerWatchdog.decide(false, true, now, wellPast, 20),
                ListenerWatchdog.Action.REQUEST_REBIND,
                "long past the ceiling is always due, so recovery never stops being attempted");
    }

    private static void backoffGrowsAndIsCapped() {
        check(ListenerWatchdog.backoffMs(1) == ListenerWatchdog.FIRST_RETRY_MS,
                "first failure waits the shortest interval");
        check(ListenerWatchdog.backoffMs(2) > ListenerWatchdog.backoffMs(1),
                "backoff grows with failures");
        check(ListenerWatchdog.backoffMs(3) > ListenerWatchdog.backoffMs(2),
                "backoff keeps growing");
        check(ListenerWatchdog.backoffMs(500) == ListenerWatchdog.MAX_RETRY_MS,
                "backoff is capped, so the watchdog never waits absurdly long");
        check(ListenerWatchdog.backoffMs(Integer.MAX_VALUE) == ListenerWatchdog.MAX_RETRY_MS,
                "an overflowing failure count still caps rather than wrapping negative");

        // Nonsense counts must clamp, not produce a nonsense interval: the same rule the archive
        // size policy follows, where bad input must never disable the safety behaviour.
        check(ListenerWatchdog.backoffMs(0) == ListenerWatchdog.FIRST_RETRY_MS,
                "zero failures clamps to the shortest interval");
        check(ListenerWatchdog.backoffMs(-7) == ListenerWatchdog.FIRST_RETRY_MS,
                "a negative failure count clamps rather than misbehaving");
    }

    /**
     * A timezone change or NTP correction can move the clock backwards. If that made the watchdog
     * wait forever, an OEM kill would become permanent -- the exact failure it exists to prevent.
     */
    private static void clockGoingBackwardsCannotFreezeTheWatchdog() {
        long now = 5_000_000L;
        long future = now + 60 * 60 * 1000L;
        equal(ListenerWatchdog.decide(false, true, now, future, 3),
                ListenerWatchdog.Action.REQUEST_REBIND,
                "a last-request time in the future is treated as due, not as an infinite wait");
    }

    private static void shortBlipsAreNotReported() {
        long now = 100_000_000L;
        check(!ListenerWatchdog.shouldReport(now - 1000L, now),
                "a one-second blip is not worth reporting");
        check(!ListenerWatchdog.shouldReport(0L, now), "never-blind is not reported");
        check(!ListenerWatchdog.shouldReport(-1L, now), "nonsense blind time is not reported");
        check(ListenerWatchdog.shouldReport(now - ListenerWatchdog.REPORT_AFTER_MS, now),
                "at the threshold it is reported");
        check(ListenerWatchdog.shouldReport(now - 86_400_000L, now), "a day blind is reported");
        equal(ListenerWatchdog.blindMessage(now - 1000L, now), "",
                "a blip produces no message at all");
    }

    private static void blindMessageReadsAsADuration() {
        long now = 1_000_000_000L;
        String half = ListenerWatchdog.blindMessage(now - 30 * 60 * 1000L, now);
        check(half.contains("30 minutes"), "half an hour reads in minutes");
        check(half.contains("battery"), "the message names the likely cause");

        String fiveHours = ListenerWatchdog.blindMessage(now - 5 * 60 * 60 * 1000L, now);
        check(fiveHours.contains("5 hours"), "five hours reads in hours");

        String threeDays = ListenerWatchdog.blindMessage(now - 3L * 24 * 60 * 60 * 1000L, now);
        check(threeDays.contains("3 days"), "three days reads in days");
    }

    /** Guards against a careless edit making the watchdog either useless or a battery drain. */
    private static void intervalsAreSane() {
        check(ListenerWatchdog.CHECK_INTERVAL_MS >= 60_000L,
                "checking no more than once a minute, so the watchdog is not the battery problem");
        check(ListenerWatchdog.FIRST_RETRY_MS <= ListenerWatchdog.MAX_RETRY_MS,
                "the first retry is not longer than the ceiling");
        check(ListenerWatchdog.REPORT_AFTER_MS > 0, "the report threshold is positive");
    }
}
