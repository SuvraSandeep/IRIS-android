package com.iris.assistant;

/**
 * Quiet-hours policy: is this an hour when a person is likely asleep or not yet up?
 *
 * SCOPE - read before reusing. The CALL CONFIRMATION path does NOT use this class, because
 * IrisListeningService.callTimingWarning() already implements smart call timing (§1.3) with a
 * richer rule set: a late-night window of 23:00-06:00, a Do Not Disturb check, an owner
 * "no calls after X" memory preference, and a repeated-calls check. That logic predates this
 * class and is deliberately left alone, so calling behaviour is unchanged.
 *
 * What this class is actually for: deciding when IRIS should stay QUIET rather than volunteer
 * something unprompted (see ProactiveSuggestion). Its default window is intentionally a little
 * wider than the call warning's - 22:00 to 08:00 - because the bar for speaking up uninvited is
 * higher than the bar for warning about a call the owner explicitly asked for.
 *
 * Pure and Android-free so the policy is unit-testable offline; the caller supplies the hour,
 * which also means any time can be checked without a clock.
 */
public final class CallTiming {

    public enum Advice {
        /** A reasonable hour - no warning. */
        FINE,
        /** Late evening / night - they may be asleep. */
        LATE_NIGHT,
        /** Very early - they may not be up yet. */
        EARLY_MORNING
    }

    /** Default quiet window: calls from 22:00 up to (not including) 08:00 get a warning. */
    public static final int DEFAULT_NIGHT_FROM = 22;
    public static final int DEFAULT_MORNING_UNTIL = 8;

    private CallTiming() { }

    /** Advice for an hour-of-day (0-23) using the default window. */
    public static Advice advise(int hourOfDay) {
        return advise(hourOfDay, DEFAULT_NIGHT_FROM, DEFAULT_MORNING_UNTIL);
    }

    /**
     * Advice for an hour-of-day against a custom quiet window.
     *
     * @param hourOfDay    0-23; anything outside that range is treated as FINE rather than
     *                     throwing, because a bad clock reading must never break calling.
     * @param nightFrom    first quiet hour in the evening, e.g. 22
     * @param morningUntil first NON-quiet hour in the morning, e.g. 8
     */
    public static Advice advise(int hourOfDay, int nightFrom, int morningUntil) {
        if (hourOfDay < 0 || hourOfDay > 23) return Advice.FINE;
        if (nightFrom < 0 || nightFrom > 23 || morningUntil < 0 || morningUntil > 23) return Advice.FINE;
        // A window that covers everything would warn on every single call; treat it as disabled.
        if (nightFrom == morningUntil) return Advice.FINE;
        if (hourOfDay >= nightFrom) return Advice.LATE_NIGHT;
        if (hourOfDay < morningUntil) {
            // Hours after midnight still read as "night" to a person; only the tail of the
            // window (from 5am) is better described as early morning.
            return hourOfDay >= 5 ? Advice.EARLY_MORNING : Advice.LATE_NIGHT;
        }
        return Advice.FINE;
    }

    public static boolean isQuietHour(int hourOfDay) { return advise(hourOfDay) != Advice.FINE; }

    /** Short spoken warning to append to the existing call confirmation, or null when fine. */
    public static String warning(Advice advice, String who) {
        if (advice == null || advice == Advice.FINE) return null;
        String name = who == null || who.trim().isEmpty() ? "them" : who.trim();
        if (advice == Advice.EARLY_MORNING) {
            return "It is quite early \u2014 " + name + " may not be up yet.";
        }
        return "It is late \u2014 " + name + " may be asleep.";
    }

    /** Convenience: warning for an hour directly, or null when the hour is fine. */
    public static String warningFor(int hourOfDay, String who) {
        return warning(advise(hourOfDay), who);
    }
}
