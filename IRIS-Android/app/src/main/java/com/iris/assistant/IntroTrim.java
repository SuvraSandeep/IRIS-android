package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Drops the "I'm IRIS, {owner}'s assistant" introduction from a reply once IRIS has already
 * introduced itself in this conversation (or when the sender clearly already knows they're
 * talking to IRIS), so a continuing chat stops sounding like a broken record. Pure and
 * offline-testable.
 *
 * It only trims when {@code alreadyIntroduced} is true, removes the intro as a leading clause or a
 * trailing sign-off, tidies up the leftover punctuation, and re-capitalises. If trimming would
 * leave nothing meaningful, the original reply is returned unchanged -- a reply that never names
 * IRIS is never produced by accident.
 */
public final class IntroTrim {

    private IntroTrim() { }

    public static String trim(String reply, boolean alreadyIntroduced, String owner) {
        if (reply == null || reply.isEmpty() || !alreadyIntroduced) return reply;
        String o = owner == null ? "" : owner.trim();
        String oq = o.isEmpty() ? "[A-Za-z]+" : Pattern.quote(o);

        String dash = "[\\u2014\\-:,]";
        String intro = "i'?m iris(?:,?\\s+(?:" + oq + "'?s|his)\\s+assistant)?";
        String out = reply;

        // Leading intro clause: "I'm IRIS, Suvra's assistant — ..." / "IRIS here! ..."
        out = out.replaceAll("(?i)^\\s*" + intro + "\\s*" + dash + "+\\s*", "");
        out = out.replaceAll("(?i)^\\s*iris here[!,.]*\\s*" + dash + "*\\s*", "");

        // Trailing sign-off: "... — I'm IRIS, his assistant." / "... I'm IRIS." / "... IRIS."
        out = out.replaceAll("(?i)\\s*" + dash + "*\\s*" + intro + "\\s*[.!]*\\s*$", "");
        out = out.replaceAll("(?i)\\s*" + dash + "+\\s*iris\\s*[.!]*\\s*$", "");

        out = out.trim();
        // Clean a leftover leading separator/space from a removed clause.
        out = out.replaceAll("^[\\u2014\\-:,\\s]+", "").trim();
        if (out.isEmpty()) return reply.trim();

        // Re-capitalise the first letter.
        out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
        // Make sure it still ends with terminal punctuation.
        char last = out.charAt(out.length() - 1);
        if (last != '.' && last != '!' && last != '?') out = out + ".";
        return out;
    }

    /** True when the sender's own message shows they already know it's IRIS (contains "iris"). */
    public static boolean senderKnowsIris(String body) {
        if (body == null) return false;
        return Pattern.compile("(?i)\\biris\\b").matcher(body).find();
    }
}
