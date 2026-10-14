package com.iris.assistant;

/** Offline checks for the self-activating-mode decision. */
public final class AutoModePolicyTest {

    private static int checks;

    private static void eq(ResponderMode a, ResponderMode b, String what) {
        checks++;
        if (a != b) throw new AssertionError("FAILED: " + what + " expected " + b + " got " + a);
    }

    private static AutoModePolicy.Signals sig(boolean driving, boolean meeting, boolean quiet,
                                              boolean aDrive, boolean aMeet, boolean aSleep) {
        AutoModePolicy.Signals s = new AutoModePolicy.Signals();
        s.driving = driving; s.meeting = meeting; s.quietHours = quiet;
        s.autoDriving = aDrive; s.autoMeeting = aMeet; s.autoSleep = aSleep;
        return s;
    }

    public static void main(String[] args) {
        // Nothing enabled -> nothing.
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE,
                sig(true, true, true, false, false, false)).mode,
                ResponderMode.NONE, "no auto-mode enabled -> NONE even with signals");

        // A manual mode is never overridden by a signal.
        AutoModePolicy.Decision d = AutoModePolicy.resolve(ResponderMode.AWAY,
                AutoModePolicy.Source.MANUAL, sig(true, false, false, true, false, false));
        eq(d.mode, ResponderMode.AWAY, "manual mode wins over a driving signal");
        checks++;
        if (d.source != AutoModePolicy.Source.MANUAL) throw new AssertionError("source MANUAL");

        // Auto signals, precedence DRIVING > MEETING > SLEEPING.
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE,
                sig(true, true, true, true, true, true)).mode,
                ResponderMode.DRIVING, "driving beats meeting and sleep");
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE,
                sig(false, true, true, true, true, true)).mode,
                ResponderMode.MEETING, "meeting beats sleep");
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE,
                sig(false, false, true, true, true, true)).mode,
                ResponderMode.SLEEPING, "quiet hours -> sleep when enabled");

        // A signal present but its auto-mode disabled does nothing.
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE,
                sig(true, false, false, false, true, true)).mode,
                ResponderMode.NONE, "driving signal with auto-driving off -> NONE");

        // Null signals safe.
        eq(AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE, null).mode,
                ResponderMode.NONE, "null signals -> NONE");

        System.out.println("Passed " + checks + " auto mode checks");
    }
}
