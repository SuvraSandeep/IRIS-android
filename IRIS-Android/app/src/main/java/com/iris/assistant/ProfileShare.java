package com.iris.assistant;

import java.util.Map;

/**
 * Answers a few harmless "about the owner" questions from his profile \u2014 but ONLY facts he has
 * explicitly opted into sharing. Pure and offline-testable (works over a plain map; the Android
 * side builds that map from the "share" block of iris-me.json).
 *
 * Privacy by construction: the ONLY questions that resolve to a field are role / company / city.
 * There is deliberately no question that maps to a phone number, address, email or anything
 * financial, so those can never be disclosed even if the owner mistakenly lists them. And nothing
 * is answered at all unless the owner put a value for that field under "share", so the default
 * (no share block) discloses nothing.
 */
public final class ProfileShare {

    private ProfileShare() { }

    private static final String[][] ROLE = {{"what does he do", "what does suvra do",
            "what is his job", "his job", "his profession", "what does he work as",
            "what is his work", "what is his role", "what does he do for a living",
            "what work does he do"}};
    private static final String[][] COMPANY = {{"where does he work", "which company",
            "what company", "his company", "which organisation", "which organization",
            "where is he employed", "who does he work for"}};
    private static final String[][] CITY = {{"which city", "what city", "where is he based",
            "which city is he in", "which city does he work"}};

    /** The shareable field a question asks about: "role", "company", "city", or "". */
    public static String fieldFor(String body) {
        if (body == null) return "";
        String flat = " " + MessageCategory.normalise(body) + " ";
        if (containsAny(flat, ROLE[0])) return "role";
        if (containsAny(flat, COMPANY[0])) return "company";
        if (containsAny(flat, CITY[0])) return "city";
        return "";
    }

    /**
     * A reply built from the owner's shared value for this field, or empty when he hasn't shared
     * it. Only role / company / city are ever produced.
     */
    public static String reply(String field, Map<String, String> share, String owner) {
        if (field == null || field.isEmpty() || share == null) return "";
        String v = share.get(field);
        if (v == null || v.trim().isEmpty()) return "";
        v = v.trim();
        String o = owner == null ? "" : owner;
        switch (field) {
            case "role":
                return "He's " + v + " \u2014 I'm IRIS, " + o + "'s assistant. He'll tell you more "
                        + "himself.";
            case "company":
                return "He works at " + v + ". I'm IRIS, " + o + "'s assistant \u2014 he'll fill in "
                        + "the rest when he's back.";
            case "city":
                return "He's based in " + v + ". I'm IRIS, " + o + "'s assistant.";
            default:
                return "";
        }
    }

    private static boolean containsAny(String paddedFlat, String[] phrases) {
        for (String p : phrases) {
            if (paddedFlat.contains(" " + p + " ")) return true;
        }
        return false;
    }
}
