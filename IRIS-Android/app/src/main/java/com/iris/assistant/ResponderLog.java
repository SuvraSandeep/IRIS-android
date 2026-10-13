package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded record of what the responder decided, including every time it chose to stay quiet.
 *
 * The silences matter as much as the sends. A responder that declines without saying why is
 * indistinguishable from one that is broken, and during a dry run the owner is specifically trying
 * to find out whether the rules behave.
 *
 * **Incoming message bodies are deliberately NOT stored here.** Message text belongs in exactly
 * one place, the archive, which has its own retention and wipe controls and is excluded from
 * backups. Copying bodies into a second store is precisely the privacy defect that was fixed in
 * 13.4.0, and a diagnostic is not a good enough reason to reintroduce it. The log keeps the
 * sender, the app, the category it matched, the tone, the verdict and the reply that would have
 * been sent -- everything needed to judge the rules, and nothing that duplicates the message.
 *
 * If the matched category ever looks wrong, the incoming text is in the ARCHIVE tab at the same
 * timestamp.
 */
public final class ResponderLog {

    private static final String PREFS = "iris_responder";
    private static final String KEY = "decision_log";

    /** Entries kept. Enough to review a day of traffic, small enough to stay in preferences. */
    public static final int MAX_ENTRIES = 60;

    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    /** One decision. */
    public static final class Entry {
        public long whenMs;
        public String appLabel = "";
        /**
         * Package the message arrived through.
         *
         * Added so the contacts screen can offer people the RESPONDER has seen, not only those
         * the ARCHIVE captured. Those are different lists -- the two features have separate app
         * selections -- and on device the contacts screen came up empty because of it.
         */
        public String pkg = "";
        public String sender = "";
        public boolean group;
        public String category = "";
        public String tone = "";
        public String verdict = "";
        /** The text that was sent, or would have been in a dry run. Empty when silent. */
        public String reply = "";
        /** True when the responder was in dry run and withheld the send. */
        public boolean dryRun;
        /**
         * What actually happened to the send, as a NotificationReply.Result name.
         *
         * Empty when nothing was attempted. Added because the log claimed delivery it could not
         * prove: a failed send used to overwrite the reply text with its own failure message and
         * was then rendered under the word "SENT", so the log read "SENT: the notification changed
         * or the reply action expired". The outcome needs its own field so the display can never
         * again put the two on the same line.
         */
        public String sendResult = "";

        public boolean wouldHaveSent() { return !reply.isEmpty(); }

        /** True only when a send was attempted and the app accepted the handle. */
        public boolean actuallySent() {
            return !dryRun && "SENT".equals(sendResult);
        }

        /** True when a send was attempted and did not succeed. */
        public boolean sendFailed() {
            return !dryRun && !sendResult.isEmpty() && !"SENT".equals(sendResult);
        }
    }

    private final SharedPreferences prefs;

    public ResponderLog(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Append a decision, discarding the oldest once the cap is reached. */
    public void record(Entry entry) {
        if (entry == null) return;
        List<Entry> entries = all();
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        overwrite(entries);
    }

    /** Every stored decision, oldest first. */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "");
        if (raw.isEmpty()) return out;
        for (String record : raw.split(RECORD)) {
            String[] parts = record.split(FIELD, -1);
            if (parts.length < 9) continue;
            try {
                Entry entry = new Entry();
                entry.whenMs = Long.parseLong(parts[0]);
                entry.appLabel = parts[1];
                entry.sender = parts[2];
                entry.group = "1".equals(parts[3]);
                entry.category = parts[4];
                entry.tone = parts[5];
                entry.verdict = parts[6];
                entry.reply = parts[7];
                entry.dryRun = "1".equals(parts[8]);
                // Tenth field added later. Records written before it are still valid and keep an
                // empty package rather than being discarded -- the owner's dry-run log is
                // evidence he collected, and a schema addition must not throw it away.
                entry.pkg = parts.length > 9 ? parts[9] : "";
                entry.sendResult = parts.length > 10 ? parts[10] : "";
                out.add(entry);
            } catch (Exception ignored) {
                // A malformed record is skipped rather than discarding the whole log.
            }
        }
        return out;
    }

    /** Every stored decision, newest first, for display. */
    public List<Entry> newestFirst() {
        List<Entry> entries = all();
        Collections.reverse(entries);
        return entries;
    }

    /** How many decisions would have sent something. */
    public int wouldHaveSentCount() {
        int count = 0;
        for (Entry entry : all()) {
            if (entry.wouldHaveSent()) count++;
        }
        return count;
    }

    /** How many were handed to an app successfully. */
    public int sentCount() {
        int count = 0;
        for (Entry entry : all()) {
            if (entry.actuallySent()) count++;
        }
        return count;
    }

    /**
     * How many were decided but did not leave.
     *
     * Surfaced because a responder that decides correctly and then fails to deliver looks, from
     * the summary line, exactly like one that is working.
     */
    public int failedCount() {
        int count = 0;
        for (Entry entry : all()) {
            if (entry.sendFailed()) count++;
        }
        return count;
    }

    public void clear() {
        prefs.edit().remove(KEY).apply();
    }

    /**
     * Remove one decision, matched on its timestamp and sender.
     *
     * The log is a diagnostic, not irreplaceable evidence like the archive, so a delete here is
     * cheap -- but the UI still offers an undo, so {@link #overwrite} exists to put a snapshot
     * back. Matching on whenMs plus sender plus verdict is enough: two decisions sharing all three
     * in the same millisecond would be genuinely indistinguishable and deleting either is fine.
     */
    public void deleteOne(long whenMs, String sender, String verdict) {
        List<Entry> entries = all();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.whenMs == whenMs
                    && e.sender.equals(sender == null ? "" : sender)
                    && e.verdict.equals(verdict == null ? "" : verdict)) {
                entries.remove(i);
                overwrite(entries);
                return;
            }
        }
    }

    /** Replace the whole log with a given list, oldest first. Used to undo a delete. */
    public void overwrite(List<Entry> entries) {
        if (entries == null) { clear(); return; }
        StringBuilder out = new StringBuilder();
        for (Entry item : entries) {
            if (out.length() > 0) out.append(RECORD);
            out.append(item.whenMs).append(FIELD)
                    .append(clean(item.appLabel)).append(FIELD)
                    .append(clean(item.sender)).append(FIELD)
                    .append(item.group ? "1" : "0").append(FIELD)
                    .append(clean(item.category)).append(FIELD)
                    .append(clean(item.tone)).append(FIELD)
                    .append(clean(item.verdict)).append(FIELD)
                    .append(clean(item.reply)).append(FIELD)
                    .append(item.dryRun ? "1" : "0").append(FIELD)
                    .append(clean(item.pkg)).append(FIELD)
                    .append(clean(item.sendResult));
        }
        prefs.edit().putString(KEY, out.toString()).apply();
    }

    /**
     * A cheap value that changes whenever the log does.
     *
     * The decision log is written by the notification listener on a background thread while the
     * RESPONDER tab may be open, and nothing told the screen. Rather than rebuilding the view on a
     * timer -- which would fight the owner every time he moved a switch -- the tab polls this and
     * redraws only when it differs. Reads the raw string and does not parse it.
     */
    public String signature() {
        String raw = prefs.getString(KEY, "");
        return raw.length() + ":" + raw.hashCode();
    }

    /**
     * Distinct senders the responder itself has seen, newest first.
     *
     * The contacts screen originally drew only on the archive, which has a separate app list, so
     * someone the responder had decided about could be missing from the one screen where the
     * owner goes to set a rule about them. This closes that gap. Senders only -- no message
     * bodies are stored here and none are returned.
     */
    public List<Entry> distinctSenders() {
        List<Entry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry entry : newestFirst()) {
            if (entry.sender == null || entry.sender.trim().isEmpty()) continue;
            String key = entry.pkg + "|" + entry.sender.toLowerCase(java.util.Locale.ROOT);
            if (seen.add(key)) out.add(entry);
        }
        return out;
    }

    /** Strip the field separators so one value cannot corrupt the record structure. */
    private static String clean(String value) {
        if (value == null) return "";
        return value.replace(FIELD, " ").replace(RECORD, " ").trim();
    }
}
