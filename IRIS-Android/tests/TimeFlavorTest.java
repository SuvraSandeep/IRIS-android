package com.iris.assistant;

import java.util.Locale;

/** Offline checks for time/day flavour. Calendar day-of-week: 1=Sun..7=Sat. */
public final class TimeFlavorTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Middle of the night (any day).
        t(TimeFlavor.of(4, 3).toLowerCase(Locale.ROOT).contains("night"), "3am -> night flavour");
        // Friday evening (Fri = 6).
        t(TimeFlavor.of(6, 18).toLowerCase(Locale.ROOT).contains("friday"), "Fri 6pm -> friday");
        // Weekend (Sat=7, Sun=1).
        t(TimeFlavor.of(7, 14).toLowerCase(Locale.ROOT).contains("weekend"), "Sat afternoon -> weekend");
        t(TimeFlavor.of(1, 14).toLowerCase(Locale.ROOT).contains("weekend"), "Sun afternoon -> weekend");
        // Monday morning (Mon=2).
        t(TimeFlavor.of(2, 9).toLowerCase(Locale.ROOT).contains("monday"), "Mon 9am -> monday rush");
        // Late night.
        t(TimeFlavor.of(3, 23).toLowerCase(Locale.ROOT).contains("late"), "11pm -> late");
        // Ordinary weekday midday -> no flavour.
        t(TimeFlavor.of(4, 13).isEmpty(), "Wed 1pm -> no flavour");
        t(!TimeFlavor.has(4, 13), "has() false for ordinary midday");
        // Every non-empty flavour names IRIS.
        for (int d = 1; d <= 7; d++) {
            for (int h = 0; h < 24; h++) {
                String f = TimeFlavor.of(d, h);
                if (!f.isEmpty()) {
                    t(f.toLowerCase(Locale.ROOT).contains("iris"), "flavour names IRIS d=" + d + " h=" + h);
                }
            }
        }
        System.out.println("Passed " + checks + " time flavour checks");
    }
}
