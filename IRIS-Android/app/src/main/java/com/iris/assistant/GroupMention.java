package com.iris.assistant;

import java.util.Collection;
import java.util.Locale;

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
