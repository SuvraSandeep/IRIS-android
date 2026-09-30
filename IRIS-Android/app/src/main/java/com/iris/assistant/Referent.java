package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure classifier for pronoun follow-ups that refer back to the person IRIS just dealt with:
 * "call him back", "text her", "message them".
 *
 * Kept Android-free so it can be unit-tested offline. Resolving the pronoun to an actual
 * contact is the caller's job (IrisListeningService uses ProfileStore.lastCalled()), which
 * keeps this class free of any storage or permission concerns.
 *
 * Scope boundary: the bare phrasings "redial", "call back", "call again", "ring back" are
 * already handled by IrisListeningService.REDIAL_PATTERN, so every pattern here REQUIRES an
 * explicit pronoun. That way this classifier can never take a command the redial handler
 * already answers, and it adds only what was genuinely missing - the ability to act on the
 * last contact with a DIFFERENT verb ("text him" after a call) and to use pronouns at all.
 */
public final class Referent {

    public enum Action {
        /** No pronoun follow-up recognised. */
        NONE,
        /** Call the last contact again. */
        CALL,
        /** Send a message to the last contact. */
        TEXT
    }

    public final Action action;

    private Referent(Action action) { this.action = action; }

    private static final Referent NO_MATCH = new Referent(Action.NONE);

    public boolean matched() { return action != Action.NONE; }

    /** A third-person pronoun standing in for the last contact. */
    private static final String PRONOUN = "(?:him|her|them|they|that\\s+person|the\\s+same\\s+person)";

    private static final Pattern CALL_REF = Pattern.compile(
            "^(?:(?:please|can\\s+you)\\s+)?(?:"
            + "(?:call|ring|phone|dial)\\s+" + PRONOUN + "(?:\\s+back|\\s+again)?"
            + "|give\\s+" + PRONOUN + "\\s+a\\s+(?:call|ring)"
            + "|(?:call|ring)\\s+" + PRONOUN + "\\s+(?:up|now)"
            + ")(?:\\s+(?:please|for\\s+me|now))?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TEXT_REF = Pattern.compile(
            "^(?:(?:please|can\\s+you)\\s+)?(?:"
            + "(?:text|message|sms)\\s+" + PRONOUN + "(?:\\s+back|\\s+again)?"
            + "|send\\s+" + PRONOUN + "\\s+(?:a\\s+)?(?:text|message|sms)"
            + "|(?:reply|respond|write)\\s+to\\s+" + PRONOUN
            + "|reply\\s+to\\s+(?:that|the)\\s+(?:text|message|sms)"
            + ")(?:\\s+(?:please|for\\s+me|now))?$",
            Pattern.CASE_INSENSITIVE);

    /** Classify a command. Never throws; returns NONE when there is no pronoun follow-up. */
    public static Referent parse(String raw) {
        if (raw == null) return NO_MATCH;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[?.!]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) return NO_MATCH;
        if (CALL_REF.matcher(s).matches()) return new Referent(Action.CALL);
        if (TEXT_REF.matcher(s).matches()) return new Referent(Action.TEXT);
        return NO_MATCH;
    }

    @Override public String toString() { return action.toString(); }
}
