package com.iris.assistant;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a group message mentions the owner by WhatsApp @-handle, phone number,
 * or custom trigger word, so the "only reply when I'm named" rule still fires when someone writes
 * "@you_know__who__i__am", "@You Know Who I Am", "@+917789893369", or custom trigger keywords.
 *
 * Pure and offline-testable. Handles stripping of WhatsApp bidirectional control characters
 * (\u2066..\u2069, \u200E..\u200F, \u200B), whitespace normalization, and @-symbol flattening
 * so mention tags reliably trigger regardless of client formatting variations.
 */
public final class GroupMention {

    private GroupMention() { }

    /**
     * Cleans text by stripping invisible zero-width and directional control characters,
     * normalizing whitespace and non-breaking spaces, and lowercasing.
     */
    public static String cleanText(String s) {
        if (s == null) return "";
        // Strip zero-width, directional and bidi control chars inserted by WhatsApp
        String c = s.replaceAll("[\u200B-\u200F\u2060-\u206F\uFEFF]", "");
        // Normalize non-breaking and multiple whitespace to single space
        c = c.replaceAll("[\\s\u00A0]+", " ");
        return c.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * True if incoming body matches any of the configured WhatsApp @-handles or phone numbers.
     */
    public static boolean isMentioned(String body, Collection<String> handles,
                                      Collection<String> phones) {
        if (body == null || body.trim().isEmpty()) return false;
        String cleanIncoming = cleanText(body);
        String noAtIncoming = cleanIncoming.replace("@", "");
        String rawTrimmed = body.trim();
        String[] tokens = rawTrimmed.split("\\s+");

        if (handles != null) {
            for (String h : handles) {
                if (h == null) continue;
                String cleanH = cleanText(h).replace("@", "").trim();
                if (cleanH.isEmpty()) continue;

                // 1. Direct flattened check (e.g. "@You Know Who I Am" or "You Know Who I Am")
                if (noAtIncoming.contains(cleanH)) return true;

                // 2. If handle was configured with underscores (e.g. "you_know__who__i__am"),
                // allow explicit tag with spaces ("you know who i am")
                if (cleanH.contains("_")) {
                    String hSpaces = cleanH.replace('_', ' ').replaceAll("\\s+", " ").trim();
                    if (!hSpaces.isEmpty() && noAtIncoming.replace('_', ' ').contains(hSpaces)) {
                        return true;
                    }
                }

                // 3. If handle was configured with spaces (e.g. "You Know Who I Am"),
                // allow explicit tag with underscores ("you_know_who_i_am")
                if (cleanH.contains(" ")) {
                    String hUnder = cleanH.replace(' ', '_');
                    if (noAtIncoming.contains(hUnder)) return true;
                }

                // 4. Token-level exact match
                String want = trimHandle(h);
                if (!want.isEmpty()) {
                    for (String tok : tokens) {
                        if (trimHandle(tok).equals(want)) return true;
                    }
                }
            }
        }

        if (phones != null) {
            String allDigits = digits(body);
            for (String p : phones) {
                String want = last10(digits(p));
                if (want.isEmpty()) continue;
                if (allDigits.contains(want)) return true;
                for (String tok : tokens) {
                    String td = digits(tok);
                    if (td.length() >= 10 && last10(td).equals(want)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if incoming body contains any of the configured group trigger keywords.
     * Supports comma-separated entries, multi-word phrases, and word-boundary matching.
     */
    public static boolean hasTriggerWord(String body, Collection<String> mentionKeywords) {
        if (body == null || body.trim().isEmpty() || mentionKeywords == null) return false;
        String cleanIncoming = cleanText(body);
        String noAtIncoming = cleanIncoming.replace("@", "");
        String paddedClean = " " + cleanIncoming + " ";
        String paddedNoAt = " " + noAtIncoming + " ";

        for (String kwEntry : mentionKeywords) {
            if (kwEntry == null) continue;
            String[] parts = kwEntry.split("[\\n,]+");
            for (String part : parts) {
                String cleanKw = cleanText(part).replace("@", "").trim();
                if (cleanKw.isEmpty()) continue;

                if (cleanKw.contains(" ")) {
                    if (cleanIncoming.contains(cleanKw) || noAtIncoming.contains(cleanKw)) {
                        return true;
                    }
                } else {
                    Pattern p = Pattern.compile("(?i)(?:^|[\\s\\p{Punct}])@?" + Pattern.quote(cleanKw) + "(?:$|[\\s\\p{Punct}])");
                    if (p.matcher(paddedClean).find() || p.matcher(paddedNoAt).find()) {
                        return true;
                    }
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
        String cleanIncoming = cleanText(rawBody);
        String noAtIncoming = cleanIncoming.replace("@", "");

        // Explicitly addressed to IRIS
        if (cleanIncoming.matches(".*\\biris\\b.*") || noAtIncoming.matches(".*\\biris\\b.*")) return true;

        // Explicitly tagged/named owner (e.g. "@Suvra", "Suvra", "suvra")
        if (owner != null && !owner.trim().isEmpty()) {
            String cleanOwner = cleanText(owner).replace("@", "").trim();
            if (!cleanOwner.isEmpty()) {
                if (cleanIncoming.matches(".*\\b" + Pattern.quote(cleanOwner) + "\\b.*")
                        || noAtIncoming.matches(".*\\b" + Pattern.quote(cleanOwner) + "\\b.*")
                        || cleanIncoming.contains(cleanOwner)
                        || noAtIncoming.contains(cleanOwner)) {
                    return true;
                }
            }
        }

        // Custom trigger words / phrases (e.g. comma-separated words in 3rd place)
        if (hasTriggerWord(rawBody, mentionKeywords)) {
            return true;
        }

        // WhatsApp @-handle or phone number tag
        return isMentioned(rawBody, handles, phones);
    }

    /** Lower-case, drop any leading '@' and strip surrounding punctuation, but KEEP underscores and spaces. */
    private static String trimHandle(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        while (t.startsWith("@")) t = t.substring(1).trim();
        t = t.replaceAll("^[^\\p{L}\\p{N}_]+", "").replaceAll("[^\\p{L}\\p{N}_]+$", "");
        return t.trim();
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
