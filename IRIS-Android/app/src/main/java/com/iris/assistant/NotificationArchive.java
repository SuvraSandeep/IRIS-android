package com.iris.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only store of captured notifications, able to flag messages whose sender later deleted or
 * unsent them.
 *
 * STORAGE GUARANTEE: the append-only rule is enforced by SQLite triggers defined in
 * {@link ArchiveSchema}, not by this class. Every content column rejects an UPDATE and every DELETE
 * is refused unless an owner-authorisation flag is set in the same transaction. That means a future
 * refactor cannot silently purge the archive; the engine itself refuses. The triggers are verified
 * against a real SQLite engine by tests/check-archive-schema.py.
 *
 * WHAT THIS IS NOT: the database is NOT separately encrypted. It lives in app-private storage, so
 * it is protected by the Android application sandbox and by the device's own full-disk encryption,
 * and the backup rules exclude it so it never leaves the device. But unlike SecureStore (which
 * wraps JSON in AES-GCM via AndroidKeyStore) there is no app-level cipher here, because that would
 * require adding SQLCipher as a native dependency. Do not describe this archive as encrypted.
 *
 * FAILURE POLICY: every public method swallows storage errors and returns a safe value. A missing,
 * cleared or corrupted database must never crash the notification listener, because the listener is
 * a system-bound service and a crash loop there would take out notification capture entirely. A
 * corrupted file is deleted and recreated empty rather than retried forever.
 */
public final class NotificationArchive {

    /** Guards writes. The delete-authorisation flag is global, so deletes must not interleave. */
    private static final Object LOCK = new Object();

    private static Helper helper;

    /**
     * Last storage failure, kept so the UI can show WHY nothing is being captured.
     *
     * Every method here swallows exceptions to keep the notification listener alive, which is
     * correct but was also making a broken database indistinguishable from "no notifications have
     * arrived yet". Recording the reason turns an invisible failure into a reportable one.
     */
    private static volatile String lastError = "";
    private static volatile long lastRecordedAt;
    private static volatile int recordedThisSession;

    private NotificationArchive() { }

    /** Human-readable reason capture is failing, or empty when healthy. */
    public static String lastError() { return lastError; }

    /** When a row was last successfully written, or 0 if never in this process. */
    public static long lastRecordedAt() { return lastRecordedAt; }

    /** Rows written since the process started, to distinguish "never ran" from "nothing arrived". */
    public static int recordedThisSession() { return recordedThisSession; }

    private static void noteError(String where, Throwable error) {
        String reason = error == null ? "unknown" : error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        lastError = where + " \u2014 " + reason;
    }

    // ─── Value types ───

    /** A notification to archive. Assembled by the listener; every field is already extracted. */
    public static final class Capture {
        public String pkg = "";
        public String appLabel = "";
        public String accountLabel = "";
        public int userHandle;
        public String notifKey = "";
        public String rawTitle = "";
        public String sender = "";
        public String group = "";
        public String body = "";
        public long postTime;
    }

    /**
     * An archived row.
     *
     * The value type itself lives in {@link ArchiveRow}, outside this class, so that reading rows
     * does not require the Android SQLite imports above. That is what lets ArchiveExport and its
     * CSV escaping be tested offline.
     */

    /** Search criteria. Any field left null or zero is simply not applied. */
    public static final class Filter {
        public static final int QUICK_ALL = 0;
        public static final int QUICK_FLAGGED = 1;
        public static final int QUICK_PHOTOS = 2;
        public static final int QUICK_VIDEOS = 3;
        public static final int QUICK_AUDIO = 4;
        public static final int QUICK_DOCS = 5;
        public static final int QUICK_WHATSAPP = 6;
        public static final int QUICK_SMS = 7;

        public int quickFilter = QUICK_ALL;

        /** Matched against package and app label. */
        public String app;
        /** Matched against sender and raw title. */
        public String sender;
        /** Free text, matched against the message body. */
        public String text;
        /** Unified search matched against sender, raw title, or message body. */
        public String search;
        public long fromMs;
        public long toMs;
        public boolean flaggedOnly;
        /** Include listener-gap and capture-paused markers alongside messages. */
        public boolean includeMarkers;
        public int limit = 200;
        public int offset = 0;
    }

    // ─── Writing ───

    /** Outcome of {@link #record}. */
    public static final long RESULT_DUPLICATE = -1L;
    public static final long RESULT_FULL = -2L;
    public static final long RESULT_ERROR = -3L;

    /**
     * Archive one notification.
     *
     * Duplicates are rejected by the unique fingerprint index rather than by a pre-read, so two
     * threads racing on the same notification cannot both insert it.
     *
     * @return the new row id, or one of the RESULT_ constants
     */
    public static long record(Context context, Capture capture) {
        if (context == null || capture == null) return RESULT_ERROR;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return RESULT_ERROR;

                if (!ArchiveSchema.allowsMessageInsert(usage(context))) {
                    notePausedOnce(db);
                    return RESULT_FULL;
                }

                ContentValues values = new ContentValues();
                values.put(ArchiveSchema.COL_PKG, safe(capture.pkg));
                values.put(ArchiveSchema.COL_APP_LABEL, safe(capture.appLabel));
                values.put(ArchiveSchema.COL_ACCOUNT_LABEL, safe(capture.accountLabel));
                values.put(ArchiveSchema.COL_USER_HANDLE, capture.userHandle);
                values.put(ArchiveSchema.COL_NOTIF_KEY, safe(capture.notifKey));
                values.put(ArchiveSchema.COL_RAW_TITLE, safe(capture.rawTitle));
                values.put(ArchiveSchema.COL_SENDER, safe(capture.sender));
                values.put(ArchiveSchema.COL_GROUP, safe(capture.group));
                values.put(ArchiveSchema.COL_BODY, safe(capture.body));
                values.put(ArchiveSchema.COL_POST_TIME, capture.postTime);
                values.put(ArchiveSchema.COL_CAPTURED_TIME, System.currentTimeMillis());
                values.put(ArchiveSchema.COL_ROW_KIND, ArchiveSchema.KIND_MESSAGE);
                values.put(ArchiveSchema.COL_FINGERPRINT, DeletedMessageRules.fingerprint(
                        capture.pkg, capture.notifKey, capture.rawTitle, capture.body, capture.postTime));

                long id = db.insertWithOnConflict(ArchiveSchema.TABLE, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (id > 0) {
                    lastRecordedAt = System.currentTimeMillis();
                    recordedThisSession++;
                    lastError = "";
                }
                return id <= 0 ? RESULT_DUPLICATE : id;
            } catch (Throwable t) {
                noteError("saving a notification", t);
                return RESULT_ERROR;
            }
        }
    }

    /**
     * Record a marker row: listener connected, listener lost, capture paused, or a deletion whose
     * original message was never archived.
     *
     * Markers are always accepted regardless of archive size. Suppressing them when full would
     * hide the very fact that capture had stopped.
     */
    public static long mark(Context context, int rowKind, String note) {
        if (context == null) return RESULT_ERROR;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return RESULT_ERROR;
                return insertMarker(db, rowKind, note);
            } catch (Throwable t) {
                return RESULT_ERROR;
            }
        }
    }

    private static long insertMarker(SQLiteDatabase db, int rowKind, String note) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put(ArchiveSchema.COL_ROW_KIND, rowKind);
        values.put(ArchiveSchema.COL_BODY, safe(note));
        values.put(ArchiveSchema.COL_POST_TIME, now);
        values.put(ArchiveSchema.COL_CAPTURED_TIME, now);
        // A marker's fingerprint includes the timestamp, so repeated connect/disconnect cycles are
        // all recorded instead of the unique index collapsing them into one.
        values.put(ArchiveSchema.COL_FINGERPRINT,
                DeletedMessageRules.fingerprint("marker", String.valueOf(rowKind), "", safe(note), now));
        long id = db.insertWithOnConflict(ArchiveSchema.TABLE, null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
        return id <= 0 ? RESULT_DUPLICATE : id;
    }

    /** Record that capture stopped, but only once per full episode, so the archive is not spammed. */
    private static void notePausedOnce(SQLiteDatabase db) {
        try {
            Cursor cursor = db.rawQuery("SELECT " + ArchiveSchema.COL_ROW_KIND + " FROM "
                    + ArchiveSchema.TABLE + " ORDER BY " + ArchiveSchema.COL_ID + " DESC LIMIT 1", null);
            try {
                boolean alreadyNoted = cursor != null && cursor.moveToFirst()
                        && cursor.getInt(0) == ArchiveSchema.KIND_CAPTURE_PAUSED_FULL;
                if (!alreadyNoted) {
                    insertMarker(db, ArchiveSchema.KIND_CAPTURE_PAUSED_FULL,
                            "Archive full. New messages are no longer being saved.");
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Throwable ignored) { }
    }

    // ─── Flagging ───

    /**
     * Flag the most recent unflagged message from a sender as taken back.
     *
     * The platform does not say WHICH message a deletion placeholder refers to, so only the latest
     * can be flagged -- the UI states this rather than implying precision the platform never gave.
     *
     * Choosing the most recent **unflagged** row (rather than simply the most recent) means two
     * deletions in a row flag two different messages instead of both landing on the same one.
     *
     * Flagging is permitted at every archive size: it updates three small columns, adds no rows,
     * and is the entire point of the feature.
     *
     * @return the flagged row id, or {@link #RESULT_ERROR} when there was nothing to flag
     */
    public static long flagMostRecentFromSender(Context context, String pkg, String sender,
                                                DeletedMessageRules.Confidence confidence, long whenMs) {
        if (context == null || confidence == null
                || confidence == DeletedMessageRules.Confidence.NONE) return RESULT_ERROR;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return RESULT_ERROR;

                List<String> args = new ArrayList<>();
                StringBuilder where = new StringBuilder(ArchiveSchema.COL_ROW_KIND + " = "
                        + ArchiveSchema.KIND_MESSAGE
                        + " AND " + ArchiveSchema.COL_IS_DELETED + " = 0"
                        + " AND " + ArchiveSchema.COL_IS_SUSPECTED + " = 0");
                if (pkg != null && !pkg.isEmpty()) {
                    where.append(" AND ").append(ArchiveSchema.COL_PKG).append(" = ?");
                    args.add(pkg);
                }
                if (sender != null && !sender.trim().isEmpty()) {
                    // Match the parsed sender, falling back to the raw title ONLY for one-to-one
                    // rows, where the title IS the person. In a group the title is the group name,
                    // so matching it unconditionally would flag a message from the wrong person
                    // whenever a group happens to be named after someone. A test caught exactly
                    // that: a message from Bob in a chat titled "Ana" was selected for Ana.
                    where.append(" AND (").append(ArchiveSchema.COL_SENDER).append(" = ?")
                            .append(" OR (").append(ArchiveSchema.COL_GROUP).append(" = ''")
                            .append(" AND ").append(ArchiveSchema.COL_RAW_TITLE).append(" = ?))");
                    args.add(sender.trim());
                    args.add(sender.trim());
                }

                long id = -1;
                Cursor cursor = db.query(ArchiveSchema.TABLE, new String[]{ArchiveSchema.COL_ID},
                        where.toString(), args.toArray(new String[0]), null, null,
                        ArchiveSchema.COL_POST_TIME + " DESC, " + ArchiveSchema.COL_ID + " DESC", "1");
                try {
                    if (cursor != null && cursor.moveToFirst()) id = cursor.getLong(0);
                } finally {
                    if (cursor != null) cursor.close();
                }
                if (id < 0) return RESULT_ERROR;

                ContentValues values = new ContentValues();
                if (confidence == DeletedMessageRules.Confidence.CONFIRMED) {
                    values.put(ArchiveSchema.COL_IS_DELETED, 1);
                } else {
                    values.put(ArchiveSchema.COL_IS_SUSPECTED, 1);
                }
                values.put(ArchiveSchema.COL_DELETION_TIME, whenMs);
                int updated = db.update(ArchiveSchema.TABLE, values,
                        ArchiveSchema.COL_ID + " = ?", new String[]{String.valueOf(id)});
                return updated > 0 ? id : RESULT_ERROR;
            } catch (Throwable t) {
                return RESULT_ERROR;
            }
        }
    }

    /**
     * Record a deletion whose original message is not in the archive.
     *
     * Called when flagging finds nothing: the message predates capture, or it arrived while the
     * listener was disconnected. Storing the event keeps the archive honest -- silently dropping it
     * would make a real deletion invisible.
     */
    public static long recordOrphanDeletion(Context context, String appLabel, String sender, long whenMs) {
        String who = sender == null || sender.trim().isEmpty() ? "an unknown sender" : sender.trim();
        String note = "A message from " + who + " was taken back, but the original was not in the "
                + "archive (it arrived before capture started, or while capture was interrupted).";
        long id = mark(context, ArchiveSchema.KIND_ORPHAN_DELETION, note);
        if (id > 0) stampOrphan(context, id, appLabel, sender, whenMs);
        return id;
    }

    /** Fill in the orphan's attribution. Separate because only the flag columns stay writable. */
    private static void stampOrphan(Context context, long id, String appLabel, String sender, long whenMs) {
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return;
                ContentValues values = new ContentValues();
                values.put(ArchiveSchema.COL_IS_DELETED, 1);
                values.put(ArchiveSchema.COL_DELETION_TIME, whenMs);
                db.update(ArchiveSchema.TABLE, values, ArchiveSchema.COL_ID + " = ?",
                        new String[]{String.valueOf(id)});
            } catch (Throwable ignored) { }
        }
    }

    // ─── Reading ───

    /** Search the archive, newest first. */
    public static List<ArchiveRow> query(Context context, Filter filter) {
        List<ArchiveRow> out = new ArrayList<>();
        if (context == null) return out;
        Filter f = filter == null ? new Filter() : filter;
        try {
            SQLiteDatabase db = database(context);
            if (db == null) return out;

            List<String> args = new ArrayList<>();
            StringBuilder where = new StringBuilder("1 = 1");

            if (!f.includeMarkers) {
                where.append(" AND ").append(ArchiveSchema.COL_ROW_KIND).append(" = ")
                        .append(ArchiveSchema.KIND_MESSAGE);
            }
            if (f.flaggedOnly || f.quickFilter == Filter.QUICK_FLAGGED) {
                where.append(" AND (").append(ArchiveSchema.COL_IS_DELETED).append(" = 1 OR ")
                        .append(ArchiveSchema.COL_IS_SUSPECTED).append(" = 1)");
            } else if (f.quickFilter == Filter.QUICK_PHOTOS) {
                where.append(" AND ").append(ArchiveSchema.COL_IMAGE_PATH).append(" IS NOT NULL AND ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" != '' AND (")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.jpg' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.jpeg' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.png' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.webp' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.gif')");
            } else if (f.quickFilter == Filter.QUICK_VIDEOS) {
                where.append(" AND ").append(ArchiveSchema.COL_IMAGE_PATH).append(" IS NOT NULL AND ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" != '' AND (")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.mp4' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.3gp')");
            } else if (f.quickFilter == Filter.QUICK_AUDIO) {
                where.append(" AND ").append(ArchiveSchema.COL_IMAGE_PATH).append(" IS NOT NULL AND ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" != '' AND (")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.ogg' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.opus' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.m4a' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.aac' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.amr' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.mp3')");
            } else if (f.quickFilter == Filter.QUICK_DOCS) {
                where.append(" AND ").append(ArchiveSchema.COL_IMAGE_PATH).append(" IS NOT NULL AND ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" != '' AND (")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.pdf' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.docx' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.doc' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.xlsx' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.xls' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.pptx' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.ppt' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.txt' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.csv' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.rtf' OR ")
                        .append(ArchiveSchema.COL_IMAGE_PATH).append(" LIKE '%.zip')");
            } else if (f.quickFilter == Filter.QUICK_WHATSAPP) {
                where.append(" AND (").append(ArchiveSchema.COL_PKG).append(" LIKE '%whatsapp%' OR ")
                        .append(ArchiveSchema.COL_APP_LABEL).append(" LIKE '%whatsapp%')");
            } else if (f.quickFilter == Filter.QUICK_SMS) {
                where.append(" AND (").append(ArchiveSchema.COL_PKG).append(" LIKE '%.messaging%' OR ")
                        .append(ArchiveSchema.COL_PKG).append(" LIKE '%.mms%' OR ")
                        .append(ArchiveSchema.COL_PKG).append(" LIKE '%.sms%' OR ")
                        .append(ArchiveSchema.COL_APP_LABEL).append(" LIKE '%message%' OR ")
                        .append(ArchiveSchema.COL_APP_LABEL).append(" LIKE '%sms%')");
            }
            if (f.app != null && !f.app.trim().isEmpty()) {
                where.append(" AND (").append(ArchiveSchema.COL_PKG).append(" LIKE ? ESCAPE '\\'")
                        .append(" OR ").append(ArchiveSchema.COL_APP_LABEL).append(" LIKE ? ESCAPE '\\')");
                String like = contains(f.app);
                args.add(like);
                args.add(like);
            }
            if (f.sender != null && !f.sender.trim().isEmpty()) {
                where.append(" AND (").append(ArchiveSchema.COL_SENDER).append(" LIKE ? ESCAPE '\\'")
                        .append(" OR ").append(ArchiveSchema.COL_RAW_TITLE).append(" LIKE ? ESCAPE '\\')");
                String like = contains(f.sender);
                args.add(like);
                args.add(like);
            }
            if (f.text != null && !f.text.trim().isEmpty()) {
                where.append(" AND ").append(ArchiveSchema.COL_BODY).append(" LIKE ? ESCAPE '\\'");
                args.add(contains(f.text));
            }
            if (f.search != null && !f.search.trim().isEmpty()) {
                where.append(" AND (").append(ArchiveSchema.COL_SENDER).append(" LIKE ? ESCAPE '\\'")
                        .append(" OR ").append(ArchiveSchema.COL_RAW_TITLE).append(" LIKE ? ESCAPE '\\'")
                        .append(" OR ").append(ArchiveSchema.COL_BODY).append(" LIKE ? ESCAPE '\\')");
                String like = contains(f.search);
                args.add(like);
                args.add(like);
                args.add(like);
            }
            if (f.fromMs > 0) {
                where.append(" AND ").append(ArchiveSchema.COL_POST_TIME).append(" >= ?");
                args.add(String.valueOf(f.fromMs));
            }
            if (f.toMs > 0) {
                where.append(" AND ").append(ArchiveSchema.COL_POST_TIME).append(" <= ?");
                args.add(String.valueOf(f.toMs));
            }

            int limit = f.limit <= 0 ? 200 : f.limit;
            String limitClause = f.offset > 0 ? (limit + " OFFSET " + f.offset) : String.valueOf(limit);
            Cursor cursor = db.query(ArchiveSchema.TABLE, null, where.toString(),
                    args.toArray(new String[0]), null, null,
                    ArchiveSchema.COL_POST_TIME + " DESC, " + ArchiveSchema.COL_ID + " DESC",
                    limitClause);
            try {
                while (cursor != null && cursor.moveToNext()) out.add(readRow(cursor));
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Flagged messages discovered since a point in time, newest first. */
    public static List<ArchiveRow> flaggedSince(Context context, long sinceMs) {
        List<ArchiveRow> out = new ArrayList<>();
        if (context == null) return out;
        try {
            SQLiteDatabase db = database(context);
            if (db == null) return out;
            Cursor cursor = db.query(ArchiveSchema.TABLE, null,
                    "(" + ArchiveSchema.COL_IS_DELETED + " = 1 OR " + ArchiveSchema.COL_IS_SUSPECTED
                            + " = 1) AND " + ArchiveSchema.COL_DELETION_TIME + " > ?",
                    new String[]{String.valueOf(Math.max(0, sinceMs))}, null, null,
                    ArchiveSchema.COL_DELETION_TIME + " DESC", "50");
            try {
                while (cursor != null && cursor.moveToNext()) out.add(readRow(cursor));
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Count of message rows, markers excluded. */
    public static int totalMessages(Context context) {
        return countWhere(context, ArchiveSchema.COL_ROW_KIND + " = " + ArchiveSchema.KIND_MESSAGE);
    }

    /** Count of flagged rows. */
    public static int totalFlagged(Context context) {
        return countWhere(context, ArchiveSchema.COL_IS_DELETED + " = 1 OR "
                + ArchiveSchema.COL_IS_SUSPECTED + " = 1");
    }

    private static int countWhere(Context context, String where) {
        if (context == null) return 0;
        try {
            SQLiteDatabase db = database(context);
            if (db == null) return 0;
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + ArchiveSchema.TABLE
                    + " WHERE " + where, null);
            try {
                return cursor != null && cursor.moveToFirst() ? cursor.getInt(0) : 0;
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    // ─── Size ───

    /**
     * On-disk size, including the write-ahead log and shared-memory sidecars, plus stored images.
     *
     * Images are counted here because they are by far the largest part of the archive once photo
     * capture is on, and the 1GB ceiling would otherwise only govern the text. A vault reporting
     * 4MB while holding 600MB of photos would be actively misleading.
     */
    public static long sizeBytes(Context context) {
        if (context == null) return 0L;
        try {
            File base = context.getDatabasePath(ArchiveSchema.DB_NAME);
            long total = length(base);
            total += length(new File(base.getPath() + "-wal"));
            total += length(new File(base.getPath() + "-shm"));
            long images = ArchiveImages.bytesUsed(context);
            // -1 means the image measurement failed. Adding it would shrink the reported total
            // and could let capture continue past the ceiling, so it is treated as zero here and
            // handled by the image policy, which refuses on a failed measurement.
            if (images > 0) total += images;
            return total;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long length(File file) {
        try { return file != null && file.exists() ? file.length() : 0L; }
        catch (Throwable t) { return 0L; }
    }

    public static ArchiveSchema.Usage usage(Context context) {
        return ArchiveSchema.usageFor(sizeBytes(context), ArchiveSchema.MAX_BYTES);
    }

    public static String usageMessage(Context context) {
        long bytes = sizeBytes(context);
        return ArchiveSchema.usageMessage(ArchiveSchema.usageFor(bytes, ArchiveSchema.MAX_BYTES),
                bytes, ArchiveSchema.MAX_BYTES);
    }

    // ─── Deletion: only ever from an explicit owner action ───

    /**
     * Delete one row. The caller must be acting on a direct owner instruction (the long-press
     * action in the archive list).
     *
     * The authorisation flag is cleared in a finally block, so a failure mid-delete cannot leave
     * the archive unguarded.
     */
    public static boolean deleteRow(Context context, long id) {
        return authorisedDelete(context, ArchiveSchema.COL_ID + " = ?",
                new String[]{String.valueOf(id)}) > 0;
    }

    /**
     * Delete a date range, used when the archive is full and the owner picks what to clear.
     *
     * @param keepFlagged when true, messages flagged as taken back are preserved even inside the
     *                    range. They are the reason the archive exists, so clearing space should
     *                    not be the thing that destroys them.
     */
    public static int deleteRange(Context context, long fromMs, long toMs, boolean keepFlagged) {
        StringBuilder where = new StringBuilder(ArchiveSchema.COL_POST_TIME + " >= ? AND "
                + ArchiveSchema.COL_POST_TIME + " <= ?");
        if (keepFlagged) {
            where.append(" AND ").append(ArchiveSchema.COL_IS_DELETED).append(" = 0 AND ")
                    .append(ArchiveSchema.COL_IS_SUSPECTED).append(" = 0");
        }
        return authorisedDelete(context, where.toString(),
                new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
    }

    /** Delete everything, including flagged rows. Only from the explicit confirmed-dialog action. */
    public static int deleteEverything(Context context) {
        return authorisedDelete(context, "1 = 1", new String[0]);
    }

    /**
     * Delete image files no row points at any more.
     *
     * Run after every authorised delete, which covers a single row, a date range and a full wipe
     * from one place rather than needing each to remember. Without it, image files would outlive
     * their rows and occupy storage permanently with nothing referencing them -- and they would
     * still count toward the ceiling, so the archive would slowly refuse new captures over space
     * held by photos the owner had already deleted.
     */
    private static void sweepOrphanImages(Context context) {
        try {
            ArchiveImages.deleteOrphans(context, referencedImageNames(context));
        } catch (Throwable ignored) { }
    }

    /**
     * Delete rows, then clean up any image files left without a row.
     *
     * The sweep deliberately runs AFTER the transaction has committed, not inside it. Deleting
     * files while the delete could still roll back would leave surviving rows pointing at images
     * that no longer exist -- a row that claims to have a photo and cannot show it.
     */
    private static int authorisedDelete(Context context, String where, String[] args) {
        int removed = deleteRowsAuthorised(context, where, args);
        if (removed > 0) sweepOrphanImages(context);
        return removed;
    }

    /**
     * Perform a delete with the owner-authorisation flag raised.
     *
     * Without the flag the BEFORE DELETE trigger aborts, which is what stops any automated or
     * accidental purge. The flag is lowered again in a finally block so authorisation cannot leak
     * past this call even if the delete throws.
     */
    private static int deleteRowsAuthorised(Context context, String where, String[] args) {
        if (context == null) return 0;
        synchronized (LOCK) {
            SQLiteDatabase db;
            try {
                db = database(context);
                if (db == null) return 0;
            } catch (Throwable t) {
                return 0;
            }
            try {
                db.beginTransaction();
                try {
                    setDeleteAllowed(db, true);
                    int removed = db.delete(ArchiveSchema.TABLE, where, args);
                    setDeleteAllowed(db, false);
                    db.setTransactionSuccessful();
                    return removed;
                } finally {
                    // Belt and braces: clear the flag even if the delete threw, then end the
                    // transaction. An uncommitted transaction rolls the flag back anyway, but
                    // leaving it raised must not be possible by any path.
                    try { setDeleteAllowed(db, false); } catch (Throwable ignored) { }
                    db.endTransaction();
                }
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    private static void setDeleteAllowed(SQLiteDatabase db, boolean allowed) {
        ContentValues values = new ContentValues();
        values.put("v", allowed ? 1 : 0);
        db.update(ArchiveSchema.CONTROL, values, "k = ?",
                new String[]{ArchiveSchema.KEY_DELETE_ALLOWED});
    }

    // ─── Plumbing ───

    private static ArchiveRow readRow(Cursor c) {
        ArchiveRow row = new ArchiveRow();
        row.id = c.getLong(c.getColumnIndexOrThrow(ArchiveSchema.COL_ID));
        row.pkg = str(c, ArchiveSchema.COL_PKG);
        row.appLabel = str(c, ArchiveSchema.COL_APP_LABEL);
        row.accountLabel = str(c, ArchiveSchema.COL_ACCOUNT_LABEL);
        row.userHandle = c.getInt(c.getColumnIndexOrThrow(ArchiveSchema.COL_USER_HANDLE));
        row.notifKey = str(c, ArchiveSchema.COL_NOTIF_KEY);
        row.rawTitle = str(c, ArchiveSchema.COL_RAW_TITLE);
        row.sender = str(c, ArchiveSchema.COL_SENDER);
        row.group = str(c, ArchiveSchema.COL_GROUP);
        row.body = str(c, ArchiveSchema.COL_BODY);
        row.postTime = c.getLong(c.getColumnIndexOrThrow(ArchiveSchema.COL_POST_TIME));
        row.capturedTime = c.getLong(c.getColumnIndexOrThrow(ArchiveSchema.COL_CAPTURED_TIME));
        row.rowKind = c.getInt(c.getColumnIndexOrThrow(ArchiveSchema.COL_ROW_KIND));
        row.deleted = c.getInt(c.getColumnIndexOrThrow(ArchiveSchema.COL_IS_DELETED)) == 1;
        row.suspected = c.getInt(c.getColumnIndexOrThrow(ArchiveSchema.COL_IS_SUSPECTED)) == 1;
        row.deletionTime = c.getLong(c.getColumnIndexOrThrow(ArchiveSchema.COL_DELETION_TIME));
        // Tolerant lookup: a database created before images existed and not yet migrated would
        // have no such column, and an unreadable image name must not stop the row being listed.
        try {
            int index = c.getColumnIndex(ArchiveSchema.COL_IMAGE_PATH);
            if (index >= 0) {
                String name = c.getString(index);
                row.imagePath = name == null ? "" : name;
            }
        } catch (Throwable ignored) { }
        return row;
    }

    private static String str(Cursor c, String column) {
        String value = c.getString(c.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }

    /**
     * Wrap a value for a LIKE containment match, escaping the wildcards.
     *
     * Without this, a sender or search term containing % or _ would behave as a pattern: "100%"
     * would match everything. This exact defect shipped once in PhoneHistoryReader, so it is
     * handled up front here.
     */
    private static String contains(String raw) {
        return "%" + escapeLike(raw.trim()) + "%";
    }

    private static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_') out.append('\\');
            out.append(ch);
        }
        return out.toString();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static SQLiteDatabase database(Context context) {
        synchronized (LOCK) {
            if (helper == null) helper = new Helper(context.getApplicationContext());
        }
        try {
            return helper.getWritableDatabase();
        } catch (Throwable first) {
            // A corrupted or unreadable store must not wedge capture forever. Drop it and start
            // again: losing history is bad, but a listener that can never archive anything again
            // is worse, and the owner's export is the designated backup.
            noteError("opening the archive database", first);
            try {
                context.getApplicationContext().deleteDatabase(ArchiveSchema.DB_NAME);
                synchronized (LOCK) {
                    helper = new Helper(context.getApplicationContext());
                }
                SQLiteDatabase db = helper.getWritableDatabase();
                lastError = "";
                return db;
            } catch (Throwable second) {
                // Both attempts failed, so the schema itself is almost certainly at fault. Keep the
                // reason: without it this is indistinguishable from no notifications arriving.
                noteError("recreating the archive database", second);
                return null;
            }
        }
    }

    /** Capture health: the text to show, and whether it represents a problem. */
    public static final class Status {
        public final String text;
        public final boolean problem;
        Status(String text, boolean problem) { this.text = text; this.problem = problem; }
    }

    /**
     * Why capture may not be working, in IRIS's own voice.
     *
     * Exists because every failure path here is deliberately silent to protect the notification
     * listener, which made an empty archive ambiguous: not switched on, no apps ticked, access not
     * granted, listener not bound, or a storage fault all looked identical. This reports which.
     *
     * The problem flag travels with the text rather than the caller grepping the string for
     * phrases like "NOT granted". That coupling meant any wording change -- such as this one --
     * would silently stop the warning being highlighted.
     */
    public static Status status(Context context, boolean accessGranted, boolean listenerBound) {
        if (context == null) return new Status("Status unavailable. Even I don't know why.", true);
        AppSettings settings = new AppSettings(context);
        StringBuilder out = new StringBuilder();

        if (!settings.notificationArchive()) {
            return new Status("Archiving is OFF, STG. You built me a vault and then locked me out "
                    + "of it. Bold.", true);
        }
        if (!accessGranted && !listenerBound) {
            return new Status("I have no eyes. Android won't let me read a single notification "
                    + "until you say so in Settings. Tap the button above and I promise to use "
                    + "this power responsibly. Mostly.", true);
        }
        if (!listenerBound) {
            String blind = ListenerWatchdog.blindMessage(
                    IrisNotificationListener.blindSince(), System.currentTimeMillis());
            if (!blind.isEmpty()) return new Status(blind, true);
            return new Status("Access granted, and Android unplugged me anyway. I've asked to be "
                    + "plugged back in \u2014 leave this screen and come back in a moment. If I'm "
                    + "still blind: toggle notification access off and on, and tell your battery "
                    + "optimiser to stop strangling me.", true);
        }

        int tickedCount;
        if (settings.archivedAppsChosen()) {
            tickedCount = settings.archivedApps().size();
            if (tickedCount == 0) {
                return new Status("Zero apps ticked. I am watching absolutely nothing, with "
                        + "enormous dedication. Tap TARGETS, STG.", true);
            }
        } else {
            tickedCount = ArchiveAppTargets.defaultTargets().length;
            out.append("Running my default target list \u2014 tap TARGETS to overrule me. ");
        }

        if (!lastError.isEmpty()) {
            return new Status("My storage is unwell: " + lastError + ". Hit WIPE ALL and I'll "
                    + "rebuild from nothing. Dramatic, but it works.", true);
        }

        out.append("Watching ").append(tickedCount)
                .append(tickedCount == 1 ? " app." : " apps.");
        if (recordedThisSession > 0) {
            out.append(" Logged ").append(recordedThisSession)
                    .append(recordedThisSession == 1 ? " notification" : " notifications")
                    .append(" since I woke up. Nobody suspects a thing.");
        } else {
            out.append(" Nothing logged since I woke up. I can only catch notifications that "
                    + "actually appear \u2014 if the chat was muted, or you were already staring "
                    + "at it, there was nothing to catch. Not my fault, for once.");
        }
        return new Status(out.toString(), false);
    }

    /**
     * Fill in a row's image filename after a background copy.
     *
     * The only content-bearing column the archive allows to be written after insert, and the
     * schema enforces that with its own write-once trigger. The concession exists because
     * decoding a photo takes long enough to stall the notification thread, so the row is stored
     * immediately and the image follows. A second attempt with a different name is rejected by
     * the trigger, not by this method.
     */
    public static boolean setImagePath(Context context, long rowId, String storedName) {
        if (context == null || rowId <= 0) return false;
        if (storedName == null || storedName.trim().isEmpty()) return false;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return false;
                ContentValues values = new ContentValues();
                values.put(ArchiveSchema.COL_IMAGE_PATH, storedName.trim());
                int updated = db.update(ArchiveSchema.TABLE, values,
                        ArchiveSchema.COL_ID + " = ?", new String[]{String.valueOf(rowId)});
                return updated > 0;
            } catch (Throwable t) {
                // Expected when the trigger refuses a second write. Not an error worth surfacing:
                // the first image is already stored and is the one that counts.
                return false;
            }
        }
    }

    /** One sender seen in the archive, for the contacts screen. */
    public static final class SenderRef {
        public String pkg = "";
        public String appLabel = "";
        public String sender = "";
    }

    /**
     * Distinct senders seen recently, newest first.
     *
     * The archive is the only honest source of "people you actually hear from". Reading the phone
     * book instead would list hundreds of contacts who have never messaged, and omit the unsaved
     * numbers that matter most when deciding who to answer.
     */
    public static java.util.List<SenderRef> recentSenders(Context context, int limit) {
        java.util.List<SenderRef> out = new java.util.ArrayList<>();
        if (context == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return out;
                try (Cursor cursor = db.query(ArchiveSchema.TABLE,
                        new String[]{ArchiveSchema.COL_PKG, ArchiveSchema.COL_APP_LABEL,
                                ArchiveSchema.COL_SENDER, ArchiveSchema.COL_RAW_TITLE},
                        ArchiveSchema.COL_ROW_KIND + " = " + ArchiveSchema.KIND_MESSAGE,
                        null, null, null,
                        ArchiveSchema.COL_POST_TIME + " DESC",
                        String.valueOf(Math.max(1, limit) * 20))) {
                    while (cursor.moveToNext() && out.size() < Math.max(1, limit)) {
                        SenderRef ref = new SenderRef();
                        ref.pkg = text(cursor, 0);
                        ref.appLabel = text(cursor, 1);
                        ref.sender = text(cursor, 2);
                        if (ref.sender.isEmpty()) ref.sender = text(cursor, 3);
                        if (ref.sender.isEmpty()) continue;
                        String key = ref.pkg + "|" + ref.sender;
                        if (!seen.add(key)) continue;
                        out.add(ref);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    private static String text(Cursor cursor, int index) {
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    /** The fingerprint of a stored row, used to name its image file. Empty if not found. */
    public static String fingerprintOf(Context context, long rowId) {
        if (context == null || rowId <= 0) return "";
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return "";
                try (Cursor cursor = db.query(ArchiveSchema.TABLE,
                        new String[]{ArchiveSchema.COL_FINGERPRINT},
                        ArchiveSchema.COL_ID + " = ?", new String[]{String.valueOf(rowId)},
                        null, null, null)) {
                    if (!cursor.moveToFirst()) return "";
                    String value = cursor.getString(0);
                    return value == null ? "" : value;
                }
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** Every stored image filename still referenced by a row, for orphan cleanup. */
    public static java.util.Set<String> referencedImageNames(Context context) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (context == null) return names;
        synchronized (LOCK) {
            try {
                SQLiteDatabase db = database(context);
                if (db == null) return names;
                try (Cursor cursor = db.query(ArchiveSchema.TABLE,
                        new String[]{ArchiveSchema.COL_IMAGE_PATH},
                        ArchiveSchema.COL_IMAGE_PATH + " != ''",
                        null, null, null, null)) {
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(0);
                        if (name != null && !name.isEmpty()) names.add(name);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return names;
    }

    /** Visible for the listener so a fresh process re-reads the file rather than a stale handle. */
    static void resetForTest() {
        synchronized (LOCK) { helper = null; }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, ArchiveSchema.DB_NAME, null, ArchiveSchema.DB_VERSION);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            for (String statement : ArchiveSchema.CREATE_STATEMENTS) db.execSQL(statement);
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // ADD only. Rebuilding a table would mean copying rows out and back, and a failure
            // halfway would destroy exactly the content the append-only triggers exist to protect.
            // That rule was documented here from version 1 and this, the first real migration,
            // keeps it: one ALTER TABLE ADD COLUMN and one new trigger, nothing dropped.
            //
            // Each step is attempted independently rather than inside one all-or-nothing
            // transaction, because the realistic failure is a column or trigger that already
            // exists from a partly applied upgrade. Aborting the whole migration in that case
            // would leave the archive permanently unopenable, which is far worse than a
            // redundant statement failing.
            try {
                if (oldVersion < 2) {
                    for (String statement : ArchiveSchema.MIGRATE_1_TO_2) {
                        try { db.execSQL(statement); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable ignored) { }
        }

        @Override public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            try {
                // The delete guard reads this flag. If a process died mid-delete the stored value
                // could still be 1, which would leave the archive unguarded for the next caller.
                db.execSQL("UPDATE " + ArchiveSchema.CONTROL + " SET v = 0 WHERE k = '"
                        + ArchiveSchema.KEY_DELETE_ALLOWED + "'");
            } catch (Throwable ignored) { }
        }
    }
}
