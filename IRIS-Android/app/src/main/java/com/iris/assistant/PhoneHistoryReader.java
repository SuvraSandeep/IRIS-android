package com.iris.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

/**
 * Reads the SYSTEM call log and SMS inbox to answer "who called me", "any missed calls" and
 * "read my last text". Thin Android layer only - all phrase classification lives in the pure,
 * offline-tested PhoneHistoryQuery.
 *
 * Privacy rules this class follows deliberately:
 *   - Every read is permission-checked first and returns a plain spoken explanation instead of
 *     throwing when the permission is absent, so a denial is never a crash or a silent no-op.
 *   - Message bodies and caller numbers are NEVER written to LogStore/VoiceHealth/telemetry.
 *     Only the fact that a query ran is loggable by the caller. Reading a message aloud happens
 *     because the owner explicitly asked for it; storing it anywhere would not.
 *   - Results are bounded (a handful of rows) and cursors are always closed.
 *   - Nothing here caches anything: each question re-reads, so revoking the permission takes
 *     effect immediately.
 */
public final class PhoneHistoryReader {

    /** Rows to scan for list-style answers. */
    private static final int LIST_LIMIT = 5;
    /** Spoken answers should stay short; long SMS bodies get trimmed. */
    private static final int BODY_CHARS = 320;

    private PhoneHistoryReader() { }

    public static boolean canReadCallLog(Context c) {
        return c != null && c.checkSelfPermission(Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canReadSms(Context c) {
        return c != null && c.checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Answer for the classified query, or a clear explanation of why it cannot be answered. */
    public static String answer(Context context, PhoneHistoryQuery query) {
        if (context == null || query == null || !query.matched()) return null;
        try {
            switch (query.kind) {
                case WHO_CALLED_ME: return whoCalledMe(context);
                case MISSED_CALLS:  return missedCalls(context);
                case RECENT_CALLS:  return recentCalls(context);
                case LAST_SMS:      return lastSms(context);
                case SMS_FROM:      return smsFrom(context, query.name);
                default:            return null;
            }
        } catch (SecurityException e) {
            // The permission was revoked between the check and the read.
            return "I lost access to that just now. Check IRIS's permissions and ask again.";
        } catch (Exception e) {
            return "I could not read that from the phone.";
        }
    }

    // ───────────────────────────── call log ─────────────────────────────

    private static String whoCalledMe(Context context) {
        if (!canReadCallLog(context)) return needCallLog();
        String[] cols = { CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
                CallLog.Calls.TYPE };
        String where = CallLog.Calls.TYPE + " IN (" + CallLog.Calls.INCOMING_TYPE + ","
                + CallLog.Calls.MISSED_TYPE + ")";
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                where, null, CallLog.Calls.DATE + " DESC")) {
            if (c == null || !c.moveToFirst()) return "I have no record of anyone calling you.";
            String who = describeCaller(context, c.getString(0), c.getString(1));
            long when = c.getLong(2);
            boolean missed = c.getInt(3) == CallLog.Calls.MISSED_TYPE;
            return (missed ? "You missed a call from " : "Your last call was from ")
                    + who + ", " + ActionLedger.ago(when) + ".";
        }
    }

    private static String missedCalls(Context context) {
        if (!canReadCallLog(context)) return needCallLog();
        String[] cols = { CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE };
        String where = CallLog.Calls.TYPE + " = " + CallLog.Calls.MISSED_TYPE + " AND "
                + CallLog.Calls.NEW + " = 1";
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                where, null, CallLog.Calls.DATE + " DESC")) {
            if (c == null || !c.moveToFirst()) return "No missed calls.";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            do {
                if (n > 0) sb.append("; ");
                sb.append(describeCaller(context, c.getString(0), c.getString(1)))
                  .append(' ').append(ActionLedger.ago(c.getLong(2)));
                n++;
            } while (n < LIST_LIMIT && c.moveToNext());
            String lead = n == 1 ? "One missed call: " : n + " missed calls: ";
            return lead + sb + ".";
        }
    }

    private static String recentCalls(Context context) {
        if (!canReadCallLog(context)) return needCallLog();
        String[] cols = { CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
                CallLog.Calls.TYPE };
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                null, null, CallLog.Calls.DATE + " DESC")) {
            if (c == null || !c.moveToFirst()) return "Your call log is empty.";
            StringBuilder sb = new StringBuilder("Recent calls: ");
            int n = 0;
            do {
                if (n > 0) sb.append("; ");
                sb.append(direction(c.getInt(3))).append(' ')
                  .append(describeCaller(context, c.getString(0), c.getString(1)))
                  .append(' ').append(ActionLedger.ago(c.getLong(2)));
                n++;
            } while (n < LIST_LIMIT && c.moveToNext());
            return sb + ".";
        }
    }

    private static String direction(int type) {
        if (type == CallLog.Calls.INCOMING_TYPE) return "in from";
        if (type == CallLog.Calls.OUTGOING_TYPE) return "out to";
        if (type == CallLog.Calls.MISSED_TYPE) return "missed from";
        return "with";
    }

    // ───────────────────────────── sms ─────────────────────────────

    private static String lastSms(Context context) {
        if (!canReadSms(context)) return needSms();
        String[] cols = { Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE };
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, cols,
                null, null, Telephony.Sms.DATE + " DESC")) {
            if (c == null || !c.moveToFirst()) return "You have no messages.";
            String who = describeCaller(context, null, c.getString(0));
            return "Message from " + who + ", " + ActionLedger.ago(c.getLong(2)) + ": "
                    + trim(c.getString(1));
        }
    }

    private static String smsFrom(Context context, String name) {
        if (!canReadSms(context)) return needSms();
        if (name == null || name.trim().isEmpty()) return lastSms(context);
        // Match on the stored address; a contact name is resolved to its number(s) first so
        // "messages from mom" works even though the SMS table only stores addresses.
        String number = lookupNumber(context, name);
        String[] cols = { Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE };
        String where = null;
        String[] args = null;
        if (number != null && number.length() >= 4) {
            // Compare on the last digits so formatting differences do not break the match.
            String tail = number.replaceAll("[^0-9]", "");
            if (tail.length() > 7) tail = tail.substring(tail.length() - 7);
            where = Telephony.Sms.ADDRESS + " LIKE ?";
            args = new String[] { "%" + tail };
        } else {
            where = Telephony.Sms.ADDRESS + " LIKE ?";
            args = new String[] { "%" + name.trim() + "%" };
        }
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, cols,
                where, args, Telephony.Sms.DATE + " DESC")) {
            if (c == null || !c.moveToFirst()) return "No messages from " + name + ".";
            return "From " + name + ", " + ActionLedger.ago(c.getLong(2)) + ": "
                    + trim(c.getString(1));
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Cached call-log name, else a contact lookup, else the raw number, else "unknown". */
    private static String describeCaller(Context context, String cachedName, String number) {
        if (cachedName != null && !cachedName.trim().isEmpty()) return cachedName.trim();
        if (number == null || number.trim().isEmpty()) return "an unknown number";
        String resolved = lookupName(context, number);
        return resolved != null ? resolved : number.trim();
    }

    /** Contact display name for a number, or null. Requires READ_CONTACTS (already granted). */
    private static String lookupName(Context context, String number) {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) return null;
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number));
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[] { ContactsContract.PhoneLookup.DISPLAY_NAME },
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.trim().isEmpty()) return n.trim();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** First phone number for a contact name, or null. */
    private static String lookupNumber(Context context, String name) {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) return null;
            try (Cursor c = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER },
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[] { "%" + name.trim() + "%" }, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String trim(String body) {
        if (body == null) return "(empty message)";
        String s = body.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "(empty message)";
        return s.length() <= BODY_CHARS ? s : s.substring(0, BODY_CHARS) + "…";
    }

    private static String needCallLog() {
        return "I need call log access for that. Grant it to IRIS in Settings, then ask again.";
    }

    private static String needSms() {
        return "I need message access for that. Grant it to IRIS in Settings, then ask again.";
    }
}
