package com.iris.assistant;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Loose, synonym-aware, typo-tolerant phrase matching, kept pure so it is fully offline-testable.
 *
 * It exists to make the responder understand paraphrases: "send me the pics" should hit the same
 * rule as "send the photos". The design is deliberately conservative about precision, because the
 * cost of a wrong match is a reply sent from the owner's account:
 *
 *   - A phrase is reduced to its CONTENT tokens -- stopwords dropped, each remaining token mapped
 *     through a small synonym table to a canonical form (pic/pics/picture -> photo).
 *   - A loose match requires EVERY content token of the phrase to be present in the message
 *     (order-free), each either exactly or within a small edit distance. All-must-match, not
 *     any-match, is what keeps it from firing on a single coincidental word.
 *   - The caller only uses loose matching for phrases with at least TWO content tokens, so a
 *     one-word phrase like "who" can never loosely claim an unrelated message.
 *   - Negation ("don't send the photos") is detected so the caller can suppress a request match.
 *
 * The synonym table is small and curated on purpose; a large table invites false positives.
 */
public final class PhraseMatcher {

    private PhraseMatcher() { }

    /** Function words dropped before matching. Deliberately excludes negators and content words. */
    private static final Set<String> STOPWORDS = set(
            "the", "a", "an", "is", "are", "am", "was", "were", "be", "been", "being",
            "to", "of", "in", "on", "at", "for", "and", "or", "but", "with", "from", "by",
            "about", "as", "so", "just", "now", "right", "up", "out", "this", "that", "these",
            "those", "here", "there", "please", "pls", "kindly", "can", "could", "would",
            "shall", "should", "may", "might", "do", "does", "did", "done", "will",
            "i", "me", "my", "mine", "we", "us", "our", "ours", "you", "your", "yours", "u",
            "ur", "he", "him", "his", "she", "her", "hers", "it", "its", "they", "them", "their",
            "hey", "hi", "hello", "ok", "okay", "yeah", "yep", "some", "any", "get", "got",
            "gonna", "the", "if", "then", "than", "too", "also", "very", "much", "really");

    /** Token -> canonical token. Curated and small. */
    private static final String[][] SYN = {
            {"photo", "pic", "pics", "picture", "pictures", "image", "images", "snap", "snaps",
                    "pix", "photos", "photograph", "photographs"},
            {"file", "files", "doc", "docs", "document", "documents", "pdf", "attachment",
                    "attachments"},
            {"call", "ring", "dial", "phone", "calling"},
            {"free", "available", "avail", "freetime"},
            {"send", "share", "forward", "drop", "sharing"},
            {"message", "messages", "msg", "msgs", "text", "texts", "txt", "msging"},
            {"meet", "meetup", "meeting"},
            {"location", "loc", "pin", "address", "locations"},
            {"pay", "upi", "gpay", "phonepe", "paytm", "payment", "money", "cash"},
            {"coffee", "cofee"},
            {"borrow", "lend"},
            {"reminder", "remind", "remember"}};

    private static java.util.Map<String, String> buildSyn() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String[] group : SYN) {
            String canonical = group[0];
            for (String word : group) m.put(word, canonical);
        }
        return m;
    }

    private static final java.util.Map<String, String> SYNONYMS = buildSyn();

    /** Negation phrases, matched on the padded normalised string. */
    private static final String[] NEGATORS = {
            "not", "no", "never", "dont", "don t", "do not", "cant", "can t", "cannot", "wont",
            "wo nt", "nope", "nevermind", "never mind", "nvm", "no need", "dun"};

    /** Canonical content-token set of a normalised string: stopwords dropped, synonyms applied. */
    public static Set<String> canonicalContent(String flat) {
        Set<String> out = new LinkedHashSet<>();
        if (flat == null) return out;
        for (String token : flat.trim().split("\\s+")) {
            if (token.isEmpty() || STOPWORDS.contains(token)) continue;
            out.add(SYNONYMS.getOrDefault(token, token));
        }
        return out;
    }

    /** Canonical content tokens of a rule phrase, as an array. */
    public static String[] contentTokens(String phrase) {
        return canonicalContent(phrase).toArray(new String[0]);
    }

    /** Whether every required (canonical) token is present in the message set, exact or fuzzy. */
    public static boolean containsAll(Set<String> messageCanon, String[] required) {
        if (messageCanon == null || required == null) return false;
        for (String req : required) {
            if (messageCanon.contains(req)) continue;
            boolean found = false;
            for (String token : messageCanon) {
                if (fuzzyEquals(token, req)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Whether the message contains a negation, so a request match should be suppressed. */
    public static boolean hasNegation(String paddedFlat) {
        if (paddedFlat == null) return false;
        for (String neg : NEGATORS) {
            if (paddedFlat.contains(" " + neg + " ")) return true;
        }
        return false;
    }

    /**
     * Typo tolerance scaled to length: short words must match exactly (too easy to collide),
     * longer words allow one or two edits, and the first letter must agree.
     */
    public static boolean fuzzyEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        int budget = b.length() <= 4 ? 0 : (b.length() <= 6 ? 1 : 2);
        if (budget == 0) return false;
        if (Math.abs(a.length() - b.length()) > budget) return false;
        if (a.isEmpty() || b.isEmpty() || a.charAt(0) != b.charAt(0)) return false;
        return editDistance(a, b) <= budget;
    }

    /** Standard Levenshtein edit distance, two-row variant. */
    public static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static Set<String> set(String... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }
}
