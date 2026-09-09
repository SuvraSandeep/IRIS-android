package com.iris.assistant;

/**
 * Phase 2 — the set of things a user can actually mean (AGENT-ARCHITECTURE.md §11).
 *
 * Deliberately small and explicit: an intent only exists here if IRIS has a real tool for it.
 * A planner (rule-based today, a local LLM later) may only produce these values, so an unknown
 * intent is always rejected rather than guessed at.
 */
public enum IrisIntent {
    CALL_CONTACT,
    SEND_MESSAGE,
    SET_ALARM,
    SET_TIMER,
    SET_REMINDER,
    TAKE_SCREENSHOT,
    RECORD_SCREEN,
    RECORD_VIDEO,
    TAKE_PHOTO,
    RECORD_VOICE,
    STOP_RECORDING,
    TORCH,
    SET_VOLUME,
    OPEN_APP,
    WEB_SEARCH,
    PHONE_STATUS,
    READ_NOTIFICATIONS,
    UNKNOWN;

    /** Parse a wire/JSON value without ever throwing. */
    public static IrisIntent from(String raw) {
        if (raw == null) return UNKNOWN;
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (IrisIntent i : values()) if (i.name().equals(v)) return i;
        return UNKNOWN;
    }

    /**
     * Actions that change something the user can notice, or that reach other people.
     * These must be read back / confirmed rather than executed silently (§12).
     */
    public boolean isSensitive() {
        switch (this) {
            case CALL_CONTACT:
            case SEND_MESSAGE:
                return true;
            default:
                return false;
        }
    }
}
