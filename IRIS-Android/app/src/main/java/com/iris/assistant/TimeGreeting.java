package com.iris.assistant;

import java.util.Locale;

/**
 * Spots a greeting that doesn't match the clock -- "good morning" at 11pm, "good night" at 9am --
 * so IRIS can answer with a gentle, aware reply instead of echoing the wrong time of day. Pure and
 * offline-testable. Part-of-day strings are the ones {@link ResponderWindow#partOfDay(int)}
 * produces: "morning", "afternoon", "evening", "night".
 */
public final class TimeGreeting {

    private TimeGreeting() { }

    /** True only for clear conflicts, so a borderline afternoon/evening wish is left alone. */
    public static boolean mistimed(String wished, String actual) {
        if (wished == null || actual == null) return false;
        String w = wished.trim().toLowerCase(Locale.ROOT);
        String a = actual.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty() || a.isEmpty() || w.equals(a)) return false;
        switch (w) {
            case "morning":   return a.equals("evening") || a.equals("night");
            case "night":     return a.equals("morning") || a.equals("afternoon");
            case "evening":   return a.equals("morning");
            case "afternoon": return a.equals("night");
            default:          return false;
        }
    }

    /**
     * A reply that acknowledges the wrong-time greeting, per tone. {owner} is left literal for the
     * caller's fill(). Every variant names IRIS.
     */
    public static String reply(String wished, String actual, ReplyTone.Tone tone) {
        String w = wished == null ? "" : wished.trim().toLowerCase(Locale.ROOT);
        String a = actual == null ? "" : actual.trim().toLowerCase(Locale.ROOT);
        String greet = "good " + w;
        String[] byTone = {
                "A \"" + greet + "\" in the " + a + "? \u263A I'll take it \u2014 I'm IRIS, {owner}'s "
                        + "assistant, and I've saved your message for him.",
                "Haha, \"" + greet + "\" but it's " + a + " here! IRIS here \u2014 saved for {owner} "
                        + "either way. \uD83D\uDE42",
                "It's " + a + ", not quite \"" + greet + "\" weather \u2014 but I'm a clockless "
                        + "script, so who am I to judge. I'm IRIS, {owner}'s assistant; saved for "
                        + "him.",
                "It's " + a + " here, but noted! I'm IRIS \u2014 saved for {owner}."};
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }
}
