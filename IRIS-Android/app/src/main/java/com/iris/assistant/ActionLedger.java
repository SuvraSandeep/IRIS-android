package com.iris.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3 — the local action ledger (AGENT-ARCHITECTURE.md §6).
 *
 * Records what IRIS actually did, so it can answer "what did you do last?", "where did you save
 * it?", "send the last one" and "undo that" from facts instead of guesses. Plain framework SQLite
 * (no new dependencies), stored only on this phone, with a user-controlled retention window.
 *
 * A record is written only AFTER Android confirms the action — never optimistically.
 */
public final class ActionLedger extends SQLiteOpenHelper {

    private static final String DB = "iris_actions.db";
    private static final int VERSION = 1;
    private static final String TABLE = "actions";

    /** Status values. */
    public static final String OK = "done";
    public static final String FAILED = "failed";

    public ActionLedger(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "time INTEGER NOT NULL,"
                + "intent TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "summary TEXT,"
                + "location TEXT,"
                + "ref TEXT,"
                + "reversible INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_time ON " + TABLE + "(time DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Only additive changes are expected; recreate on any downgrade/oddity.
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /** One thing IRIS did. */
    public static final class Record {
        public long id;
        public long time;
        public String intent = "";
        public String status = "";
        public String summary = "";
        public String location = "";
        public String ref = "";
        public boolean reversible;

        /** "Recorded a video · Movies/IRIS · 2 minutes ago" */
        public String describe() {
            StringBuilder sb = new StringBuilder(summary == null || summary.isEmpty() ? intent : summary);
            if (location != null && !location.isEmpty()) sb.append(" \u00b7 ").append(location);
            sb.append(" \u00b7 ").append(ago(time));
            return sb.toString();
        }
    }

    /** Write a record. Returns the row id, or -1 on failure. */
    public long record(String intent, String status, String summary, String location,
                       String ref, boolean reversible) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("time", System.currentTimeMillis());
            cv.put("intent", intent == null ? "" : intent);
            cv.put("status", status == null ? OK : status);
            cv.put("summary", summary == null ? "" : summary);
            cv.put("location", location == null ? "" : location);
            cv.put("ref", ref == null ? "" : ref);
            cv.put("reversible", reversible ? 1 : 0);
            long id = getWritableDatabase().insert(TABLE, null, cv);
            purgeOld(30);
            return id;
        } catch (Throwable t) { return -1; }
    }

    /** Convenience: a completed action with a location (screenshot, video, memo…). */
    public long recordSaved(String intent, String summary, String location) {
        return record(intent, OK, summary, location, "", false);
    }

    /** The most recent record, or null. */
    public Record last() {
        List<Record> r = recent(1);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The most recent record whose intent contains {@code intentLike}, or null. */
    public Record lastOf(String intentLike) {
        if (intentLike == null || intentLike.isEmpty()) return last();
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                "intent LIKE ?", new String[]{ "%" + intentLike + "%" },
                null, null, "time DESC", "1")) {
            return c != null && c.moveToFirst() ? read(c) : null;
        } catch (Throwable t) { return null; }
    }

    /** The most recent reversible record, or null. */
    public Record lastReversible() {
        try (Cursor c = getReadableDatabase().query(TABLE, null,
                "reversible = 1", null, null, null, "time DESC", "1")) {
            return c != null && c.moveToFirst() ? read(c) : null;
        } catch (Throwable t) { return null; }
    }

    public List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null,
                "time DESC", String.valueOf(Math.max(1, limit)))) {
            while (c != null && c.moveToNext()) out.add(read(c));
        } catch (Throwable ignored) { }
        return out;
    }

    /** Mark a record as undone so it is not offered again. */
    public void markUndone(long id) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("reversible", 0);
            cv.put("status", "undone");
            getWritableDatabase().update(TABLE, cv, "id = ?", new String[]{ String.valueOf(id) });
        } catch (Throwable ignored) { }
    }

    /** Drop records older than {@code days} (retention control, §6). */
    public void purgeOld(int days) {
        try {
            long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
            getWritableDatabase().delete(TABLE, "time < ?", new String[]{ String.valueOf(cutoff) });
        } catch (Throwable ignored) { }
    }

    public void clearAll() {
        try { getWritableDatabase().delete(TABLE, null, null); } catch (Throwable ignored) { }
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            return c != null && c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** Human-readable timeline for the Settings screen ("IRIS did this"). */
    public String timeline(int limit) {
        List<Record> rs = recent(limit);
        if (rs.isEmpty()) return "Nothing recorded yet. IRIS logs actions here once it does something.";
        StringBuilder sb = new StringBuilder();
        for (Record r : rs) sb.append("\u2022 ").append(r.describe()).append('\n');
        sb.append("\nKept for 30 days, on this phone only.");
        return sb.toString();
    }

    private static Record read(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.time = c.getLong(c.getColumnIndexOrThrow("time"));
        r.intent = str(c, "intent");
        r.status = str(c, "status");
        r.summary = str(c, "summary");
        r.location = str(c, "location");
        r.ref = str(c, "ref");
        r.reversible = c.getInt(c.getColumnIndexOrThrow("reversible")) == 1;
        return r;
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    /** "2 minutes ago" / "just now". */
    public static String ago(long time) {
        long d = Math.max(0, System.currentTimeMillis() - time) / 1000;
        if (d < 20) return "just now";
        if (d < 60) return d + " seconds ago";
        long m = d / 60;
        if (m < 60) return m == 1 ? "a minute ago" : m + " minutes ago";
        long h = m / 60;
        if (h < 24) return h == 1 ? "an hour ago" : h + " hours ago";
        long days = h / 24;
        return days == 1 ? "yesterday" : days + " days ago";
    }

    /** Spoken form used in replies. */
    public static String spoken(Record r) {
        if (r == null) return "";
        String s = r.summary == null || r.summary.isEmpty() ? r.intent : r.summary;
        return s + " " + ago(r.time).replace("just now", "a moment ago");
    }

    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }
}
