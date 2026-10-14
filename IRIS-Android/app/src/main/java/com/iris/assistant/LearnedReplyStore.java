package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists replies the owner has taught IRIS from the decision log. Its own preferences file, so
 * clearing it never touches the responder's settings. Keyed by {@link LearnedReplies} scopes.
 *
 * Single-user and bounded only loosely; the owner teaches a handful of these by hand, so no large
 * growth is expected. Excluded from the responder's own state deliberately.
 */
public final class LearnedReplyStore {

    private static final String PREFS = "iris_learned_replies";
    private static final String P = "scope_";

    private final SharedPreferences prefs;

    public LearnedReplyStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Teach a reply for a specific sender and category. */
    public void teach(String sender, String category, String reply) {
        if (reply == null || reply.trim().isEmpty()) return;
        prefs.edit().putString(P + LearnedReplies.scopeFor(sender, category), reply.trim()).apply();
    }

    /** Teach a reply for a sender across all categories. */
    public void teachAny(String sender, String reply) {
        if (reply == null || reply.trim().isEmpty()) return;
        prefs.edit().putString(P + LearnedReplies.scopeAny(sender), reply.trim()).apply();
    }

    /** The learned reply for this sender and category, or empty. Specific beats sender-wide. */
    public String lookup(String sender, String category) {
        String specific = prefs.getString(P + LearnedReplies.scopeFor(sender, category), "");
        if (!specific.isEmpty()) return specific;
        return prefs.getString(P + LearnedReplies.scopeAny(sender), "");
    }

    /** Forget everything taught about one sender (both scopes, every category). */
    public void forget(String sender) {
        String prefix = P + LearnedReplies.scopeAny(sender);
        // scopeAny ends with the sender + separator + "*"; the per-category keys share the
        // sender + separator prefix, so match on that.
        String senderPrefix = prefix.substring(0, prefix.length() - 1);
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(senderPrefix)) e.remove(key);
        }
        e.apply();
    }

    /** Remove every learned reply. */
    public void clear() {
        prefs.edit().clear().apply();
    }
}
