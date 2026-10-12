package com.iris.assistant;

/**
 * Schema and size policy for the append-only notification archive.
 *
 * SCOPE: no Android imports, so the SQL and the size arithmetic can both be verified offline.
 * The DDL below is executed against a real SQLite engine by tests/check-archive-schema.py, which
 * proves the append-only triggers actually refuse edits and deletions rather than merely claiming
 * to. NotificationArchive supplies the Android SQLiteOpenHelper around this.
 *
 * WHY THE WRITE-ONCE RULE LIVES IN THE DATABASE: the requirement is that the archive cannot be
 * edited or deleted except by an explicit owner action. Enforcing that only in Java would mean one
 * careless future change -- or one future AI agent "tidying up" -- could silently purge evidence of
 * a deleted message. Triggers make the engine itself refuse, so the guarantee survives code changes
 * nobody reviewed carefully.
 *
 * WHY EACH STATEMENT IS A SINGLE-LINE STRING LITERAL: the Python checker extracts these literals so
 * that Java and the test share one source of truth for the DDL. Splitting a statement across
 * concatenated lines would mean maintaining a second copy of the SQL in the test, which could drift
 * out of step with this file and silently stop testing the real schema.
 */
public final class ArchiveSchema {

    private ArchiveSchema() { }

    public static final String DB_NAME = "iris_notification_archive.db";
    public static final int DB_VERSION = 2;

    public static final String TABLE = "notifications";
    public static final String CONTROL = "archive_control";

    /** Key in {@link #CONTROL} that must be 1 for a DELETE to be permitted. */
    public static final String KEY_DELETE_ALLOWED = "manual_delete_allowed";

    // Row kinds. Markers make listener gaps visible; an archive worth trusting has to show where
    // it was not looking, rather than presenting a silent hole as continuous coverage.
    public static final int KIND_MESSAGE = 0;
    public static final int KIND_LISTENER_CONNECTED = 1;
    public static final int KIND_LISTENER_DISCONNECTED = 2;
    public static final int KIND_CAPTURE_PAUSED_FULL = 3;
    /**
     * A deletion was detected but the original message was not in the archive -- it predates
     * capture, or it arrived during a listener gap. Recorded rather than dropped so the event is
     * not lost; this is NOT the placeholder stored as a message, which the owner asked us never to
     * do.
     */
    public static final int KIND_ORPHAN_DELETION = 4;

    // Columns that may never change after insert.
    public static final String COL_ID = "_id";
    public static final String COL_PKG = "pkg";
    public static final String COL_APP_LABEL = "app_label";
    public static final String COL_ACCOUNT_LABEL = "account_label";
    public static final String COL_USER_HANDLE = "user_handle";
    public static final String COL_NOTIF_KEY = "notif_key";
    public static final String COL_RAW_TITLE = "raw_title";
    public static final String COL_SENDER = "sender";
    public static final String COL_GROUP = "group_name";
    public static final String COL_BODY = "body";
    public static final String COL_POST_TIME = "post_time";
    public static final String COL_CAPTURED_TIME = "captured_time";
    public static final String COL_FINGERPRINT = "fingerprint";
    public static final String COL_ROW_KIND = "row_kind";

    // The only mutable columns: a deletion is discovered after the message was already archived,
    // so these three have to remain writable for the feature to work at all.
    public static final String COL_IS_DELETED = "is_deleted";
    public static final String COL_IS_SUSPECTED = "is_suspected";
    public static final String COL_DELETION_TIME = "deletion_time";

    /**
     * Relative filename of a captured image, or empty.
     *
     * Write-once rather than immutable, enforced by its own trigger: the row is inserted from the
     * notification thread and the path filled in by a background copy a moment later, so one
     * empty-to-value transition must be allowed. Any change after that is refused, so an image
     * cannot be swapped for a different one later.
     *
     * Stores a bare filename, not an absolute path. An absolute path would break if Android ever
     * relocated app storage, and would also be a second place the archive directory is defined.
     */
    public static final String COL_IMAGE_PATH = "image_path";

    /** Directory under the app's private files dir that holds captured images. */
    public static final String IMAGE_DIR = "archive_images";

    /**
     * Full DDL, executed in order on create.
     *
     * Every column is NOT NULL with a default. That is deliberate: the immutability trigger
     * compares old and new values, and a NULL on either side of a comparison would weaken the
     * guard. NOT NULL removes that whole class of hole.
     */
    // ---SQL-BEGIN---
    public static final String[] CREATE_STATEMENTS = {
        "CREATE TABLE notifications (_id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL DEFAULT '', app_label TEXT NOT NULL DEFAULT '', account_label TEXT NOT NULL DEFAULT '', user_handle INTEGER NOT NULL DEFAULT 0, notif_key TEXT NOT NULL DEFAULT '', raw_title TEXT NOT NULL DEFAULT '', sender TEXT NOT NULL DEFAULT '', group_name TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', post_time INTEGER NOT NULL DEFAULT 0, captured_time INTEGER NOT NULL DEFAULT 0, fingerprint TEXT NOT NULL DEFAULT '', row_kind INTEGER NOT NULL DEFAULT 0, is_deleted INTEGER NOT NULL DEFAULT 0, is_suspected INTEGER NOT NULL DEFAULT 0, deletion_time INTEGER NOT NULL DEFAULT 0, image_path TEXT NOT NULL DEFAULT '')",
        "CREATE UNIQUE INDEX idx_fingerprint ON notifications (fingerprint)",
        "CREATE INDEX idx_sender_lookup ON notifications (pkg, sender, post_time)",
        "CREATE INDEX idx_post_time ON notifications (post_time)",
        "CREATE INDEX idx_flagged ON notifications (is_deleted, deletion_time)",
        "CREATE TABLE archive_control (k TEXT PRIMARY KEY NOT NULL, v INTEGER NOT NULL DEFAULT 0)",
        "INSERT INTO archive_control (k, v) VALUES ('manual_delete_allowed', 0)",
        "CREATE TRIGGER archive_content_is_immutable BEFORE UPDATE ON notifications FOR EACH ROW WHEN OLD.pkg IS NOT NEW.pkg OR OLD.app_label IS NOT NEW.app_label OR OLD.account_label IS NOT NEW.account_label OR OLD.user_handle IS NOT NEW.user_handle OR OLD.notif_key IS NOT NEW.notif_key OR OLD.raw_title IS NOT NEW.raw_title OR OLD.sender IS NOT NEW.sender OR OLD.group_name IS NOT NEW.group_name OR OLD.body IS NOT NEW.body OR OLD.post_time IS NOT NEW.post_time OR OLD.captured_time IS NOT NEW.captured_time OR OLD.fingerprint IS NOT NEW.fingerprint OR OLD.row_kind IS NOT NEW.row_kind BEGIN SELECT RAISE(ABORT, 'archive is append-only: captured content cannot be modified'); END",
        "CREATE TRIGGER archive_image_is_write_once BEFORE UPDATE OF image_path ON notifications FOR EACH ROW WHEN OLD.image_path != '' AND NEW.image_path IS NOT OLD.image_path BEGIN SELECT RAISE(ABORT, 'archive is append-only: a stored image path cannot be changed'); END",
        "CREATE TRIGGER archive_delete_needs_owner BEFORE DELETE ON notifications FOR EACH ROW WHEN (SELECT v FROM archive_control WHERE k = 'manual_delete_allowed') = 0 BEGIN SELECT RAISE(ABORT, 'archive is append-only: deletion requires an explicit owner action'); END"
    };
    // ---SQL-END---

    /**
     * Version 1 to 2: adds image_path and its write-once guard.
     *
     * The first real migration, and it obeys the rule onUpgrade has carried since version 1: ADD
     * only. No table is dropped or rebuilt, because rebuilding would mean copying rows out and
     * back, and a failure halfway would destroy exactly the content the append-only triggers exist
     * to protect.
     *
     * image_path is write-once rather than fully immutable, which the other content columns are.
     * That is deliberate: decoding and copying an image takes long enough that doing it on the
     * notification thread would stall it, so the row is inserted first and the path filled in from
     * a background thread moments later. The trigger permits exactly that one transition -- empty
     * to a value -- and refuses any later change, so the looser rule cannot be used to swap one
     * image for another after the fact.
     *
     * Executed in order inside a transaction by the caller. The trigger is created after the
     * column, since it references it.
     */
    // ---MIGRATE-1-2-BEGIN---
    public static final String[] MIGRATE_1_TO_2 = {
        "ALTER TABLE notifications ADD COLUMN image_path TEXT NOT NULL DEFAULT ''",
        "CREATE TRIGGER archive_image_is_write_once BEFORE UPDATE OF image_path ON notifications FOR EACH ROW WHEN OLD.image_path != '' AND NEW.image_path IS NOT OLD.image_path BEGIN SELECT RAISE(ABORT, 'archive is append-only: a stored image path cannot be changed'); END"
    };
    // ---MIGRATE-1-2-END---

    // ─── Size policy ───

    /** Owner-chosen ceiling. */
    public static final long MAX_BYTES = 1024L * 1024L * 1024L;
    public static final int WARN_PERCENT = 80;
    public static final int CRITICAL_PERCENT = 95;

    /**
     * How full the archive is.
     *
     * There is no automated eviction at any level: the owner required that nothing be deleted
     * without their say-so, so a full archive stops accepting new messages rather than quietly
     * discarding old ones.
     */
    public enum Usage {
        /** Plenty of room. */
        OK,
        /** Past the warning mark; mention it, do not nag. */
        WARN,
        /** Nearly full; prompt the owner to clear a date range. */
        CRITICAL,
        /** No room for new messages. Flagging already-archived ones still works. */
        FULL
    }

    /**
     * Classify current usage.
     *
     * A nonsensical input (negative size, zero ceiling) reports OK rather than FULL. Getting this
     * backwards would let a failed size check silently stop all capture, which is a far worse
     * outcome than briefly over-running a ceiling the owner will realistically never reach.
     */
    public static Usage usageFor(long bytes, long maxBytes) {
        if (bytes < 0 || maxBytes <= 0) return Usage.OK;
        if (bytes >= maxBytes) return Usage.FULL;
        long percent = (bytes * 100L) / maxBytes;
        if (percent >= CRITICAL_PERCENT) return Usage.CRITICAL;
        if (percent >= WARN_PERCENT) return Usage.WARN;
        return Usage.OK;
    }

    /** Percentage used, clamped to 0-100 so it can be rendered without further checking. */
    public static int percentUsed(long bytes, long maxBytes) {
        if (bytes < 0 || maxBytes <= 0) return 0;
        long percent = (bytes * 100L) / maxBytes;
        if (percent < 0) return 0;
        return percent > 100 ? 100 : (int) percent;
    }

    /**
     * Whether a new MESSAGE row may be inserted.
     *
     * IMPORTANT, and a correction to an earlier sketch of this design: when the archive is full we
     * cannot "keep capturing only deleted messages", because at insert time nothing is yet known to
     * have been deleted -- the deletion placeholder arrives later. What actually survives a full
     * archive is {@link #allowsFlagUpdate()}: existing rows can still be flagged. So the archive
     * freezes, and deletion detection keeps working on everything already captured.
     */
    public static boolean allowsMessageInsert(Usage usage) {
        return usage != Usage.FULL;
    }

    /**
     * Marker rows are always permitted. They are a few bytes each and they are what makes a
     * capture gap visible; suppressing them when full would hide the very fact that capture
     * stopped.
     */
    public static boolean allowsMarkerInsert(Usage usage) {
        return true;
    }

    /**
     * Flagging an existing row is always permitted, at any usage level. It is an update of three
     * small columns, it adds no rows, and it is the entire point of the feature.
     */
    public static boolean allowsFlagUpdate() {
        return true;
    }

    /** Owner-facing summary of archive size. */
    public static String usageMessage(Usage usage, long bytes, long maxBytes) {
        int percent = percentUsed(bytes, maxBytes);
        String size = readableSize(bytes);
        switch (usage) {
            case FULL:
                return "Archive is full (" + size + "). New messages are no longer being saved. "
                        + "Clear a date range to resume. Already-saved messages can still be "
                        + "flagged as deleted.";
            case CRITICAL:
                return "Archive is " + percent + "% full (" + size + "). Clear a date range soon, "
                        + "or capture will stop.";
            case WARN:
                return "Archive is " + percent + "% full (" + size + ").";
            default:
                return "Archive is using " + size + ".";
        }
    }

    /** Human-readable byte count. Deliberately coarse; exact bytes are not useful to a reader. */
    public static String readableSize(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MB";
        long tenths = (bytes * 10L) / (1024L * 1024L * 1024L);
        return (tenths / 10L) + "." + (tenths % 10L) + " GB";
    }
}
