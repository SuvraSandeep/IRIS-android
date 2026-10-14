package com.iris.assistant;

import java.util.Locale;
import java.util.Map;

/**
 * Scope keys and precedence for replies the owner has TAUGHT IRIS from the decision log.
 *
 * Pure and offline-testable; the Android store (LearnedReplyStore) only persists the strings this
 * class keys. A learned reply is the owner's explicit instruction, so it outranks everything else
 * (name rules, smart replies, shipped templates) in AutoResponder.
 *
 * Two scopes, most specific first:
 *   - sender + category : "answer THIS person's greetings like this"
 *   - sender (any)      : "always answer THIS person like this"
 */
public final class LearnedReplies {

    /** Field separator inside a scope key; control char so it can't appear in a name/category. */
    private static final String SEP = "\u0001";
    /** Stands in for "any category" in the sender-wide scope. */
    private static final String ANY = "*";

    private LearnedReplies() { }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** Scope key for a specific sender and category. */
    public static String scopeFor(String sender, String category) {
        return norm(sender) + SEP + (category == null || category.isEmpty() ? ANY : category);
    }

    /** Scope key for a sender across all categories. */
    public static String scopeAny(String sender) {
        return norm(sender) + SEP + ANY;
    }

    /**
     * The learned reply for this sender and category, or empty.
     *
     * The sender+category entry wins over the sender-wide entry, so a specific instruction beats a
     * blanket one. Empty values are treated as absent.
     */
    public static String choose(Map<String, String> store, String sender, String category) {
        if (store == null) return "";
        String specific = store.get(scopeFor(sender, category));
        if (specific != null && !specific.isEmpty()) return specific;
        String any = store.get(scopeAny(sender));
        return any == null ? "" : any;
    }
}
