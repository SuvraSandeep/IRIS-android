package com.iris.assistant;

/**
 * A situation the owner can put the responder into: driving, asleep, in a meeting, away.
 *
 * A mode is a single switch that changes what every reply says, the same way quiet hours do, but
 * turned on deliberately rather than by the clock. When one is active it overrides both the
 * category content and the time windows, because the owner setting "I'm driving" at two in the
 * afternoon means exactly that and should beat whatever the hour would otherwise imply.
 *
 * Pure and self-contained: no Android imports, no storage, no clock. ResponderSettings stores
 * which mode is active; AutoResponder consults it; ReplyTemplates supplies the words. Keeping the
 * enum free of all three means the set of modes and their meaning can be reasoned about and tested
 * in isolation.
 */
public enum ResponderMode {

    /** No mode. The ordinary category/time-window behaviour applies. */
    NONE("Off", false),

    /**
     * Driving. A reply is fine; a redirect to a phone call is NOT, because the one thing you must
     * not tell a driver to do is take a call. Urgent is answered with the same "I'll see it when I
     * stop" message as everything else.
     */
    DRIVING("Driving", false),

    /**
     * Asleep. Behaves like quiet hours regardless of the clock: urgency is redirected to a phone
     * call, because a call wakes a sleeping person and a message does not. This is the one mode
     * where urgency changes the reply.
     */
    SLEEPING("Sleeping", true),

    /** In a meeting. A reply is fine; redirecting to a call is not, so urgent is answered plainly. */
    MEETING("In a meeting", false),

    /** Away or on holiday. As driving and meeting: answered, never redirected to a call. */
    AWAY("Away", false);

    private final String label;
    private final boolean redirectUrgentToCall;

    ResponderMode(String label, boolean redirectUrgentToCall) {
        this.label = label;
        this.redirectUrgentToCall = redirectUrgentToCall;
    }

    /** Human-readable name for the UI. */
    public String label() { return label; }

    /** Whether this mode is actually in force (anything but NONE). */
    public boolean isActive() { return this != NONE; }

    /**
     * Whether an urgent message in this mode should be redirected to a phone call.
     *
     * True only for SLEEPING. Telling a driver or someone in a meeting to call is worse than
     * saying nothing special; telling a sleeper to call is the only thing that reaches them.
     */
    public boolean redirectsUrgentToCall() { return redirectUrgentToCall; }

    /** Parse a stored name, falling back to NONE so a corrupt value can never silence replies. */
    public static ResponderMode parse(String raw) {
        if (raw == null) return NONE;
        for (ResponderMode mode : values()) {
            if (mode.name().equalsIgnoreCase(raw.trim())) return mode;
        }
        return NONE;
    }
}
