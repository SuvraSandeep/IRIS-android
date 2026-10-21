package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tracks read timestamps per conversation thread in the notification vault.
 *
 * Persisted in SharedPreferences so unread badges are accurately displayed
 * when new receipts arrive after the user last viewed a thread.
 */
public final class ConversationReadTracker {

    private static final String PREF_NAME = "iris_archive_conversation_reads";

    private ConversationReadTracker() { }

    /** Returns the epoch timestamp when the user last opened this conversation, or 0. */
    public static long getLastRead(Context context, String pkg, String threadTitle) {
        if (context == null) return 0L;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getLong(key(pkg, threadTitle), 0L);
    }

    /** Marks this conversation as read up to the current timestamp. */
    public static void markRead(Context context, String pkg, String threadTitle) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(key(pkg, threadTitle), System.currentTimeMillis())
                .apply();
    }

    /** Clears all read timestamps (e.g. during vault erase). */
    public static void clearAll(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static String key(String pkg, String threadTitle) {
        return (pkg != null ? pkg.trim() : "") + "::" + (threadTitle != null ? threadTitle.trim() : "");
    }
}
