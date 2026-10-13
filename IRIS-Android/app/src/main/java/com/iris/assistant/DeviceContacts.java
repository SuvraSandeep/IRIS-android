package com.iris.assistant;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads names out of the phone's contact book, for the responder's contact picker.
 *
 * Deliberately minimal. It returns display NAMES only -- never numbers, emails, photos or anything
 * else -- because the picker's entire job is to let the owner attach a rule to a person by name,
 * and the responder matches on the resolved sender name. Pulling more would be collecting contact
 * data the feature does not use, which is exactly the kind of thing the archive work was careful
 * not to do.
 *
 * Nothing is cached and nothing is copied into app storage. Each search is a fresh, bounded query
 * against the system provider, so the address book is never duplicated here; only the handful of
 * names the owner actually sets a rule about end up persisted, and those as a rule, not a copy.
 */
public final class DeviceContacts {

    /** Hard cap on rows returned, so a 5,000-contact phone cannot stall the UI thread. */
    public static final int MAX_RESULTS = 60;

    private DeviceContacts() { }

    /**
     * Distinct display names matching a search term, newest-queried first, capped.
     *
     * @param term the owner's search text; blank returns the first {@link #MAX_RESULTS} names
     */
    public static List<String> search(Context context, String term) {
        List<String> out = new ArrayList<>();
        if (context == null) return out;

        Set<String> seen = new LinkedHashSet<>();
        String trimmed = term == null ? "" : term.trim();

        Cursor cursor = null;
        try {
            if (trimmed.isEmpty()) {
                cursor = context.getContentResolver().query(
                        ContactsContract.Contacts.CONTENT_URI,
                        new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                        null, null,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC");
            } else {
                // FILTER_URI does the name matching inside the provider, so the query returns only
                // the matches rather than every contact for us to sift.
                android.net.Uri uri = android.net.Uri.withAppendedPath(
                        ContactsContract.Contacts.CONTENT_FILTER_URI,
                        android.net.Uri.encode(trimmed));
                cursor = context.getContentResolver().query(
                        uri,
                        new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                        null, null,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC");
            }
            if (cursor == null) return out;
            int col = cursor.getColumnIndex(
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
            if (col < 0) return out;
            while (cursor.moveToNext() && out.size() < MAX_RESULTS) {
                String name = cursor.getString(col);
                if (name == null) continue;
                name = name.trim();
                // A contact saved only as a number has its number as the display name; skip it,
                // because a rule keyed by a bare number would never match a resolved contact name
                // and would just clutter the list.
                if (name.isEmpty()) continue;
                if (seen.add(name.toLowerCase(java.util.Locale.ROOT))) out.add(name);
            }
        } catch (Throwable ignored) {
            // No contacts permission, or the provider is unavailable. An empty list is the honest
            // answer; the picker tells the owner to grant access.
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    /** Whether contacts can be read at all, so the picker can explain an empty result. */
    public static boolean canRead(Context context) {
        if (context == null) return false;
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
