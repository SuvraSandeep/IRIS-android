package com.iris.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notification summary (INTELLIGENCE-ROADMAP §4.3).
 *
 * NotificationStore already stores and filters captured notifications, and IRIS can already
 * read them out one by one. The gap this fills is the SUMMARY: "4 from WhatsApp, 2 from Gmail
 * and one from Slack" instead of a long recital, which is what you actually want when you pick
 * the phone up after a while.
 *
 * Grouping/phrasing is pure and works on plain (app, title) pairs rather than on
 * NotificationStore.Item, so it is unit-testable offline with no Context and no Android types.
 */
public final class NotificationSummary {

    /** Distinct apps to name before collapsing the tail into "and N others". */
    private static final int MAX_APPS_NAMED = 4;

    private NotificationSummary() { }

    /** One captured notification reduced to what the summary needs. */
    public static final class Entry {
        public final String app;
        public final String sender;
        public Entry(String app, String sender) {
            this.app = app == null ? "" : app.trim();
            this.sender = sender == null ? "" : sender.trim();
        }
    }

    // NOTE: there is deliberately no adapter from NotificationStore.Item here. Referencing that
    // type would pull NotificationStore -> Context/SecureStore into this file and make the class
    // impossible to compile (and therefore to unit-test) without Android. The caller does the
    // two-field adaptation instead, which keeps this policy provably pure.

    /**
     * Spoken summary grouped by app, busiest first.
     *
     * Returns a "nothing waiting" line for an empty list rather than an empty string, so the
     * caller can always just speak the result.
     */
    public static String summarize(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "Nothing waiting.";

        // LinkedHashMap keeps first-seen order so equal counts stay in arrival order and the
        // summary is stable between calls instead of shuffling.
        Map<String, Integer> byApp = new LinkedHashMap<>();
        for (Entry e : entries) {
            String app = e.app.isEmpty() ? "another app" : e.app;
            byApp.merge(app, 1, Integer::sum);
        }

        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(byApp.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int total = entries.size();
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(total == 1 ? " notification" : " notifications").append(": ");

        int named = Math.min(MAX_APPS_NAMED, ordered.size());
        for (int i = 0; i < named; i++) {
            Map.Entry<String, Integer> row = ordered.get(i);
            if (i > 0) sb.append(i == named - 1 && ordered.size() <= MAX_APPS_NAMED ? " and " : ", ");
            sb.append(row.getValue()).append(" from ").append(row.getKey());
        }
        int remainingApps = ordered.size() - named;
        if (remainingApps > 0) {
            int remainingCount = 0;
            for (int i = named; i < ordered.size(); i++) remainingCount += ordered.get(i).getValue();
            // "N more from M other apps". The previous wording jammed two different numbers
            // together as "N from M other apps", which read as the nonsensical
            // "1 from 1 other app"; "more" makes clear the two numbers mean different things.
            sb.append(", and ").append(remainingCount).append(" more from ")
              .append(remainingApps).append(remainingApps == 1 ? " other app" : " other apps");
        }
        sb.append('.');

        String who = topSenders(entries);
        if (who != null) sb.append(' ').append(who);
        return sb.toString();
    }

    /**
     * "Mostly from Ana." when one sender clearly dominates, else null.
     *
     * Only claims a dominant sender when there are at least three notifications and that
     * sender accounts for more than half, so a single message never produces a misleading
     * "mostly from" claim.
     */
    private static String topSenders(List<Entry> entries) {
        Map<String, Integer> bySender = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.sender.isEmpty()) continue;
            bySender.merge(e.sender, 1, Integer::sum);
        }
        if (bySender.isEmpty() || entries.size() < 3) return null;
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> row : bySender.entrySet()) {
            if (row.getValue() > bestCount) { bestCount = row.getValue(); best = row.getKey(); }
        }
        if (best == null || bestCount * 2 <= entries.size()) return null;
        return "Mostly from " + best + ".";
    }

    /** True when the phrasing is asking for a summary rather than a full read-out. */
    public static boolean isSummaryRequest(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[?.!]+$", "")
                .replaceAll("\\s+", " ").trim();
        // what'?s rather than what.s: "." would require a character between "what" and "s",
        // so the plain unapostrophed "whats" that speech-to-text usually produces would miss.
        return s.matches("^(?:(?:give\\s+me\\s+|what'?s\\s+)?(?:a\\s+)?"
                + "(?:summary|summarise|summarize|overview|recap)"
                + "(?:\\s+of)?(?:\\s+(?:my|the))?\\s+notifications?"
                + "|notification\\s+(?:summary|overview|recap)"
                + "|summari[sz]e\\s+(?:my\\s+)?notifications?"
                + "|what\\s+(?:have\\s+)?i\\s+missed"
                + "|what\\s+did\\s+i\\s+miss"
                + "|(?:anything|what'?s)\\s+waiting)$");
    }
}
