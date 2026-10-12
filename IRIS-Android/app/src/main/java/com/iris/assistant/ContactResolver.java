package com.iris.assistant;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a bare phone number into a saved contact name.
 *
 * Messaging apps put the contact name in the notification title when the number is in your address
 * book, and the raw number when it is not. Archive rows therefore show a mix of "Mum" and
 * "+91 98765 43210", and the number form is close to useless when scanning a list months later.
 *
 * Resolution happens at READ time and the resolved name is never stored. Two reasons. The archive
 * table is append-only by design -- SQLite triggers reject updates to captured content, so writing
 * a name back would be refused anyway -- and reading late means a contact saved tomorrow applies
 * retroactively to rows captured today. The stored row keeps exactly what the notification said,
 * which is what the archive's integrity story rests on.
 *
 * Number recognition lives in ContactNumber so it can be checked offline; only the lookup needs
 * Android.
 */
public final class ContactResolver {

    /**
     * Bounded cache. A notification burst repeats the same sender many times, and every miss is a
     * content-provider query on whatever thread is drawing the list.
     */
    private static final int CACHE_MAX = 200;

    private static final Map<String, String> CACHE =
            new LinkedHashMap<String, String>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private ContactResolver() { }

    /**
     * The saved contact name for this sender, or the sender unchanged.
     *
     * Returns the input untouched when it is already a name, when the number is not in contacts,
     * or when READ_CONTACTS is not granted. The archive stays fully usable without contacts
     * access; it simply shows numbers.
     */
    public static String display(Context context, String sender) {
        if (sender == null) return "";
        if (context == null) return sender;
        if (!ContactNumber.looksLikePhoneNumber(sender)) return sender;

        String key = ContactNumber.normalise(sender);
        if (key.isEmpty()) return sender;

        synchronized (CACHE) {
            String hit = CACHE.get(key);
            // An empty cached value means "looked up, not in contacts", so an unknown number is
            // not re-queried on every redraw of the list.
            if (hit != null) return hit.isEmpty() ? sender : hit;
        }

        String found = lookup(context, key);
        synchronized (CACHE) { CACHE.put(key, found == null ? "" : found); }
        return found == null || found.isEmpty() ? sender : found;
    }

    private static String lookup(Context context, String number) {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                String name = cursor.getString(0);
                return name == null ? null : name.trim();
            }
        } catch (Throwable ignored) {
            // A missing, restricted or slow contacts provider must never break the archive view.
            return null;
        }
    }

    /** Clears the cache so a newly saved contact appears without restarting the app. */
    public static void forget() {
        synchronized (CACHE) { CACHE.clear(); }
    }
}
