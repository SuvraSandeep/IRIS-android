package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises a messaging-app EMOJI REACTION (tapback) notification so the responder never replies
 * to it. Reacting with 👍 to a message is not a message and must not earn an auto-reply. Pure and
 * offline-testable.
 *
 * Detection is conservative and SHAPE-based, because the only cost of a miss is one skipped reply
 * (the safe direction), while a false positive on a real message is worse. WhatsApp posts these as
 * text that STARTS with "Reacted <emoji> to ..." (and localised equivalents); Instagram/iMessage
 * use "liked"/"reacted … to your message". We match those shapes, not a bare "reacted" anywhere.
 *
 * NOTE: reaction notification wording is locale- and app-version dependent. This covers the common
 * English forms; confirm the exact text on-device (via the Notification inspector) and extend
 * {@link #REACTED_LEAD} / {@link #LIKED} if a variant slips through.
 */
public final class ReactionText {

    private ReactionText() { }

    // Starts with "reacted <something> to" — the WhatsApp reaction shape.
    private static final Pattern REACTED_LEAD = Pattern.compile(
            "^reacted\\b.*\\bto\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Starts with one or more emoji/symbols then "to " — e.g. "😂 to \"your message\"".
    private static final Pattern EMOJI_TO_LEAD = Pattern.compile(
            "^[\\p{So}\\p{Sk}\\uFE0F\\u200D\\s]+to\\s+\\S", Pattern.CASE_INSENSITIVE);

    // Instagram / iMessage style.
    private static final Pattern LIKED = Pattern.compile(
            "\\b(liked|loved|laughed at|emphasi[sz]ed|reacted to)\\b.*\\b(your|the)\\b.*"
            + "\\b(message|photo|post|story|reel)\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static boolean looksLikeReaction(String body) {
        if (body == null) return false;
        String t = body.trim();
        if (t.isEmpty()) return false;
        String lower = t.toLowerCase(Locale.ROOT);
        if (REACTED_LEAD.matcher(lower).find()) return true;
        if (LIKED.matcher(lower).find()) return true;
        if (EMOJI_TO_LEAD.matcher(t).find()) return true;
        return false;
    }
}
