package com.iris.assistant;

import java.util.Arrays;

/** Offline checks for the rapid-fire debounce/coalesce helper. */
public final class DebounceTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        long now = 1_700_000_000_000L;
        t(Debounce.withinWindow(now - 1000, now, 3000), "1s ago is within 3s window");
        t(!Debounce.withinWindow(now - 5000, now, 3000), "5s ago is outside 3s window");
        t(!Debounce.withinWindow(0, now, 3000), "no previous reply -> not within");
        t(!Debounce.withinWindow(now - 1000, now, 0), "zero window disables");
        t(!Debounce.withinWindow(now + 1000, now, 3000), "future last-reply is not within");

        t(Debounce.combine(Arrays.asList("hi", "are you there", "")).equals("hi are you there"),
                "combine joins non-empty messages");
        t(Debounce.combine(null).isEmpty(), "combine null -> empty");

        System.out.println("Passed " + checks + " debounce checks");
    }
}
