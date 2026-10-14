package com.iris.assistant;

/**
 * Decides which situational mode should be active, given the owner's manual choice and whatever
 * on-device signals are available. Pure and offline-testable; the Android layer only gathers the
 * signals and applies the decision.
 *
 * Rules:
 *   - A mode the owner set BY HAND is never overridden by a signal. Auto only acts when the owner
 *     hasn't taken manual control.
 *   - Among auto signals, precedence is DRIVING > MEETING > SLEEPING, because being in a vehicle
 *     is the most safety-relevant and sleeping the most time-bound.
 *   - Each signal only counts when the owner has enabled that auto-mode; all are off by default,
 *     so nothing activates itself unless asked.
 */
public final class AutoModePolicy {

    public enum Source { NONE, MANUAL, AUTO }

    public static final class Decision {
        public final ResponderMode mode;
        public final Source source;

        Decision(ResponderMode mode, Source source) {
            this.mode = mode == null ? ResponderMode.NONE : mode;
            this.source = source == null ? Source.NONE : source;
        }
    }

    /** The live signals and which auto-modes the owner has enabled. */
    public static final class Signals {
        public boolean driving;      // a driving signal is active (car Bluetooth, in-vehicle)
        public boolean meeting;      // a calendar event is in progress
        public boolean quietHours;   // the clock is inside quiet hours
        public boolean autoDriving;  // owner enabled auto-driving
        public boolean autoMeeting;  // owner enabled auto-meeting
        public boolean autoSleep;    // owner enabled auto-sleep in quiet hours
    }

    private AutoModePolicy() { }

    /**
     * Resolve the effective mode.
     *
     * @param manualMode   the mode the owner currently has set (may be NONE)
     * @param manualSource whether that mode was set manually or by a previous auto decision
     * @param s            the live signals and enables
     */
    public static Decision resolve(ResponderMode manualMode, Source manualSource, Signals s) {
        // A hand-set mode wins and is never stomped by a signal.
        if (manualSource == Source.MANUAL && manualMode != null
                && manualMode != ResponderMode.NONE) {
            return new Decision(manualMode, Source.MANUAL);
        }

        if (s != null) {
            if (s.autoDriving && s.driving) return new Decision(ResponderMode.DRIVING, Source.AUTO);
            if (s.autoMeeting && s.meeting) return new Decision(ResponderMode.MEETING, Source.AUTO);
            if (s.autoSleep && s.quietHours) {
                return new Decision(ResponderMode.SLEEPING, Source.AUTO);
            }
        }

        // No signal applies. Keep a previously-set mode (even an AUTO one the caller is holding),
        // otherwise nothing.
        if (manualMode != null && manualMode != ResponderMode.NONE) {
            return new Decision(manualMode, manualSource == null ? Source.MANUAL : manualSource);
        }
        return new Decision(ResponderMode.NONE, Source.NONE);
    }
}
