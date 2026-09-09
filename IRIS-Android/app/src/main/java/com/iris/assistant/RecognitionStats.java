package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Phase 1 — measure recognition instead of guessing at it (AGENT-ARCHITECTURE.md §14).
 *
 * Tracks, locally and anonymously (no transcripts stored here):
 *   heard          — final transcripts accepted for handling
 *   unclear        — low confidence / empty, IRIS asked again instead of acting
 *   wrongAction    — user reported "that was wrong" after an action
 *   corrected      — user taught a correction
 *   latency        — wake/command-window open → transcript delivered
 *
 * Success target: wrongAction stays at zero. Asking once is better than acting wrongly.
 */
public final class RecognitionStats {
    private final SharedPreferences prefs;

    public RecognitionStats(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences("iris_recognition_stats", Context.MODE_PRIVATE);
    }

    private void bump(String key, int by) {
        try { prefs.edit().putInt(key, prefs.getInt(key, 0) + by).apply(); } catch (Exception ignored) { }
    }

    /** A final transcript was accepted and routed to command handling. */
    public void recordHeard() { bump("heard", 1); }

    /** Empty or low-confidence result — IRIS asked again rather than acting. */
    public void recordUnclear() { bump("unclear", 1); }

    /** The user reported that IRIS did the wrong thing. */
    public void recordWrongAction() { bump("wrong_action", 1); }

    /** The user taught a correction for a misheard phrase. */
    public void recordCorrected() { bump("corrected", 1); }

    /** Time from opening the command window to receiving the transcript. */
    public void recordLatency(long ms) {
        if (ms <= 0 || ms > 60_000) return;
        try {
            prefs.edit()
                    .putLong("latency_total", prefs.getLong("latency_total", 0) + ms)
                    .putInt("latency_count", prefs.getInt("latency_count", 0) + 1)
                    .apply();
        } catch (Exception ignored) { }
    }

    public int heard()       { return prefs.getInt("heard", 0); }
    public int unclear()     { return prefs.getInt("unclear", 0); }
    public int wrongAction() { return prefs.getInt("wrong_action", 0); }
    public int corrected()   { return prefs.getInt("corrected", 0); }

    public int avgLatencyMs() {
        int n = prefs.getInt("latency_count", 0);
        if (n <= 0) return 0;
        return (int) (prefs.getLong("latency_total", 0) / n);
    }

    /** Attempts = accepted transcripts + times IRIS had to ask again. */
    public int attempts() { return heard() + unclear(); }

    /** Percentage of attempts that produced a usable transcript on the first try. */
    public int clearRatePercent() {
        int a = attempts();
        return a == 0 ? 0 : Math.round(heard() * 100f / a);
    }

    public void reset() {
        try { prefs.edit().clear().apply(); } catch (Exception ignored) { }
    }

    /** Human-readable report for the Settings screen. */
    public String report() {
        int a = attempts();
        StringBuilder sb = new StringBuilder();
        sb.append("RECOGNITION\n");
        sb.append("  Attempts:            ").append(a).append('\n');
        sb.append("  Understood:          ").append(heard()).append('\n');
        sb.append("  Asked again:         ").append(unclear()).append('\n');
        sb.append("  Clear on first try:  ").append(a == 0 ? "\u2014" : clearRatePercent() + "%").append('\n');
        sb.append("  Avg response:        ")
          .append(avgLatencyMs() == 0 ? "\u2014" : String.format(Locale.US, "%.1f s", avgLatencyMs() / 1000f))
          .append("\n\n");
        sb.append("ACCURACY\n");
        sb.append("  Wrong actions:       ").append(wrongAction());
        sb.append(wrongAction() == 0 ? "  \u2705 target met\n" : "  \u26a0 investigate\n");
        sb.append("  Corrections taught:  ").append(corrected()).append("\n\n");
        if (a == 0) {
            sb.append("No data yet. Use IRIS normally, then check back.\n");
        } else if (clearRatePercent() < 70) {
            sb.append("Tip: a low first-try rate usually means microphone or noise, not wording.\n")
              .append("Check Settings \u2192 Microphone, keep internet on for system speech, and\n")
              .append("teach corrections with \u201CFix what IRIS misheard\u201D.\n");
        } else {
            sb.append("Goal: keep wrong actions at zero. Asking once is better than acting wrongly.\n");
        }
        return sb.toString();
    }
}
