package com.iris.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3 — the local action ledger (AGENT-ARCHITECTURE.md §6).
 *
 * Records what IRIS actually did, so it can answer "what did you do last?", "where did you save
 * it?", "send the last one" and "undo that" from facts instead of guesses. Stored only on this
 * phone, with a user-controlled retention window.
 *
 * A record is written only AFTER Android confirms the action — never optimistically.
 *
 * Storage: encrypted JSON via SecureStore (AES-GCM, AndroidKeyStore-backed), matching
 * CommandHistory and RoutineStore's existing pattern — NOT plaintext framework SQLite as this
 * class previously used. Real gap this closes: the ledger records what IRIS actually did,
 * including file paths (e.g. "Movies/IRIS"), contact references, and message targets -- data at
 * least as sensitive as the transcripts CommandHistory already encrypts, but it was the one
 * local store left in plaintext, recoverable from a backup/root/forensic image where its
 * siblings are not. The public API (record/recordSaved/last/lastOf/lastReversible/markUndone/
 * recent/count/timeline/clearAll, AutoCloseable) is unchanged so every existing caller
 * (IrisListeningService, MainActivity, NotificationReplyActivity, ReliabilityTools) keeps
 * working exactly as before.
 */
public final class ActionLedger implements AutoCloseable {

    private static final String FILE = "action-ledger.json";
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    /** Status values. */
    public static final String OK = "done";
    public static final String FAILED = "failed";

    private final Context context;

    public ActionLedger(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public void close() { /* no native resource to release; kept for API compatibility */ }

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

    /** Loads the ledger, drops anything past the retention window, and persists the pruned
     *  result back if anything was actually dropped -- mirrors CommandHistory.recent()'s
     *  read-prune-write pattern exactly. */
    private synchronized JSONArray load() {
        try {
            JSONArray raw = new JSONArray(SecureStore.read(context, FILE, "[]"));
            JSONArray out = new JSONArray();
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            for (int i = 0; i < raw.length(); i++) {
                JSONObject r = raw.optJSONObject(i);
                if (r != null && r.optLong("time") >= cutoff) out.put(r);
            }
            if (out.length() != raw.length()) SecureStore.write(context, FILE, out.toString());
            return out;
        } catch (Exception e) { return new JSONArray(); }
    }

    private synchronized void save(JSONArray a) {
        try { SecureStore.write(context, FILE, a.toString()); } catch (Exception ignored) { }
    }

    /** Write a record. Returns the row id, or -1 on failure. */
    public synchronized long record(String intent, String status, String summary, String location,
                       String ref, boolean reversible) {
        try {
            JSONArray a = load();
            long id = nextId(a);
            JSONObject r = new JSONObject()
                .put("id", id)
                .put("time", System.currentTimeMillis())
                .put("intent", intent == null ? "" : intent)
                .put("status", status == null ? OK : status)
                .put("summary", summary == null ? "" : summary)
                .put("location", location == null ? "" : location)
                .put("ref", ref == null ? "" : ref)
                .put("reversible", reversible);
            a.put(r);
            save(a);
            return id;
        } catch (Exception t) { return -1; }
    }

    private static long nextId(JSONArray a) {
        long max = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null) max = Math.max(max, r.optLong("id"));
        }
        return max + 1;
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
    public synchronized Record lastOf(String intentLike) {
        if (intentLike == null || intentLike.isEmpty()) return last();
        try {
            JSONArray a = load();
            Record best = null;
            for (int i = 0; i < a.length(); i++) {
                Record r = read(a.optJSONObject(i));
                if (r != null && r.intent.contains(intentLike) && (best == null || r.time > best.time)) best = r;
            }
            return best;
        } catch (Exception t) { return null; }
    }

    /** The most recent reversible record, or null. */
    public synchronized Record lastReversible() {
        try {
            JSONArray a = load();
            Record best = null;
            for (int i = 0; i < a.length(); i++) {
                Record r = read(a.optJSONObject(i));
                if (r != null && r.reversible && (best == null || r.time > best.time)) best = r;
            }
            return best;
        } catch (Exception t) { return null; }
    }

    public synchronized List<Record> recent(int limit) {
        List<Record> out = new ArrayList<>();
        try {
            JSONArray a = load();
            for (int i = 0; i < a.length(); i++) {
                Record r = read(a.optJSONObject(i));
                if (r != null) out.add(r);
            }
            out.sort((x, y) -> Long.compare(y.time, x.time)); // newest first, matching the old "time DESC" query
            if (out.size() > Math.max(1, limit)) out = out.subList(0, Math.max(1, limit));
        } catch (Exception ignored) { }
        return out;
    }

    /** Mark a record as undone so it is not offered again. */
    public synchronized void markUndone(long id) {
        try {
            JSONArray a = load();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null && r.optLong("id") == id) {
                    r.put("reversible", false);
                    r.put("status", "undone");
                }
            }
            save(a);
        } catch (Exception ignored) { }
    }

    /** Drop records older than {@code days} (retention control, §6). */
    public synchronized void purgeOld(int days) {
        try {
            JSONArray a = load(); // load() already applies the class's own 30-day retention
            JSONArray out = new JSONArray();
            long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null && r.optLong("time") >= cutoff) out.put(r);
            }
            save(out);
        } catch (Exception ignored) { }
    }

    public synchronized void clearAll() {
        save(new JSONArray());
    }

    public synchronized int count() {
        return load().length();
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

    private static Record read(JSONObject o) {
        if (o == null) return null;
        Record r = new Record();
        r.id = o.optLong("id");
        r.time = o.optLong("time");
        r.intent = o.optString("intent", "");
        r.status = o.optString("status", "");
        r.summary = o.optString("summary", "");
        r.location = o.optString("location", "");
        r.ref = o.optString("ref", "");
        r.reversible = o.optBoolean("reversible", false);
        return r;
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
