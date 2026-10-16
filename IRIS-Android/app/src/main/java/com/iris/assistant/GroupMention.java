package com.iris.assistant;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a group message mentions the owner by WhatsApp @-handle or phone number, so the
 * "only reply when I'm named" rule still fires when someone writes "@you_know__who__i__am" or
 * "@+917789893369" rather than the owner's saved name. Pure and offline-testable.
 *
 * This works on the RAW message, deliberately NOT the normalised text, because normalisation turns
 * underscores into spaces -- which would both miss the real handle and turn a handle like
 * "you_know__who__i__am" into the everyday phrase "you know who i am", a false-match waiting to
 * happen. Handles are matched whole (underscores kept); numbers are matched on their last ten
 * digits, so "7789893369", "+917789893369" and "@917789893369" all count as the same person.
 */
public final class GroupMention {

    private GroupMention() { }

    public static boolean isMentioned(String body, Collection<String> handles,
                                      Collection<String> phones) {
        if (body == null || body.trim().isEmpty()) return false;
        String[] tokens = body.trim().split("\\s+");

        if (handles != null) {
            for (String h : handles) {
                if (h == null) continue;
                String want = trimHandle(h);
                if (want.isEmpty()) continue;
                for (String tok : tokens) {
                    if (trimHandle(tok).equals(want)) return true;
                }
            }
        }

        if (phones != null) {
            for (String p : phones) {
                String want = last10(digits(p));
                if (want.isEmpty()) continue;
                for (String tok : tokens) {
                    String td = digits(tok);
                    if (td.length() >= 10 && last10(td).equals(want)) return true;
                }
            }
        }
        return false;
    }

    /**
     * In group chats, IRIS must stay completely silent unless explicitly addressed:
     * someone explicitly tags the owner (@Suvra, configured keywords, handles, numbers) or IRIS.
     */
    public static boolean isExplicitlyAddressed(String rawBody, String owner,
                                                Collection<String> mentionKeywords,
                                                Collection<String> handles,
                                                Collection<String> phones) {
        if (rawBody == null || rawBody.trim().isEmpty()) return false;
        String raw = rawBody.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        // Explicitly addressed to IRIS
        if (lower.matches(".*\\biris\\b.*")) return true;

        // Explicitly tagged/named owner (e.g. "@suvra", "suvra")
        if (owner != null && !owner.trim().isEmpty()) {
            String o = owner.trim().toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\b" + Pattern.quote(o) + "\\b.*")
                    || lower.contains("@" + o)) {
                return true;
            }
        }

        if (mentionKeywords != null) {
            for (String kw : mentionKeywords) {
                if (kw == null) continue;
                String k = kw.trim().toLowerCase(Locale.ROOT);
                if (k.isEmpty()) continue;
                if (lower.matches(".*\\b" + Pattern.quote(k) + "\\b.*")
                        || lower.contains("@" + k)) {
                    return true;
                }
            }
        }

        // WhatsApp @-handle or phone number tag
        return isMentioned(raw, handles, phones);
    }

    /** Lower-case, drop any leading '@' and strip surrounding punctuation, but KEEP underscores. */
    private static String trimHandle(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        while (t.startsWith("@")) t = t.substring(1);
        t = t.replaceAll("^[^\\p{L}\\p{N}_]+", "").replaceAll("[^\\p{L}\\p{N}_]+$", "");
        return t;
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    /** The last ten digits (the national number), so a country code is ignored. */
    private static String last10(String d) {
        if (d == null || d.length() < 10) return "";
        return d.substring(d.length() - 10);
    }
}
