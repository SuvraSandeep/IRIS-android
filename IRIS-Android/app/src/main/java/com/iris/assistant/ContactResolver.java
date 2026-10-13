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

    /**
     * Whether this sender is genuinely in the contact store.
     *
     * Added after a flaw found while planning the auto-responder. The obvious implementation is
     * "if it does not look like a phone number it must be a saved contact, because the app
     * substituted a name" -- and that is backwards. SMS senders are frequently alphanumeric
     * shortcodes such as VM-HDFCBK or AX-AMAZON. Those contain letters, so looksLikePhoneNumber
     * returns false, and the naive conclusion would mark a bank's spam gateway as a saved contact.
     * The saved-contacts-only guard, the one thing meant to prevent auto-replying to spam, would
     * have waved it straight through.
     *
     * So this is a POSITIVE lookup: the sender is saved only when found. Anything unconfirmed --
     * no permission, no provider, a query failure -- counts as NOT saved. The direction of that
     * uncertainty is deliberate: treating an unconfirmed sender as unsaved costs one silence,
     * while the reverse sends a stranger a message.
     */
    public static boolean isSaved(Context context, String sender) {
        if (context == null || sender == null) return false;
        String trimmed = sender.trim();
        if (trimmed.isEmpty()) return false;

        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                // Cannot verify, so cannot claim it is saved.
                return false;
            }
            if (ContactNumber.looksLikePhoneNumber(trimmed)) {
                return lookup(context, ContactNumber.normalise(trimmed)) != null;
            }
            return matchesDisplayName(context, trimmed);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether a contact with this display name exists.
     *
     * Used for senders that arrive as text rather than digits. An exact-ish match is required: a
     * substring search would let "Amazon" match a contact called "Amazonia" and reinstate the
     * problem this method exists to solve.
     */
    private static boolean matchesDisplayName(Context context, String name) {
        Uri uri = ContactsContract.Contacts.CONTENT_URI;
        String selection = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " = ?";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts._ID},
                selection, new String[]{name}, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Clears the cache so a newly saved contact appears without restarting the app. */
    public static void forget() {
        synchronized (CACHE) { CACHE.clear(); }
    }
}
