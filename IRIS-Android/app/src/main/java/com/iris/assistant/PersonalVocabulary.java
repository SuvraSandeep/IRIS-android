package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 1 — personal vocabulary learned from the user's own corrections.
 *
 * Two kinds of knowledge, both stored locally and only ever added by an explicit user action:
 *   1. Phrase corrections  — "IRIS heard X, I meant Y". Applied ONLY on a whole-utterance match.
 *   2. Name variants       — the ways this user's speech gets transcribed for a contact/place
 *                            name ("somojit" -> "Soumyajit"). Applied only in the command head
 *                            region, never inside a dictated message.
 *
 * Design rule (from AGENT-ARCHITECTURE.md §4): repair conservatively, and NEVER rewrite the
 * contents of a dictated message. When in doubt, return the text unchanged.
 */
public final class PersonalVocabulary {
    /** Only the first few words are treated as the command head; message payloads come after. */
    private static final int MAX_CORRECTIONS = 300;
    private static final int MAX_VARIANTS_PER_NAME = 12;

    private final SharedPreferences prefs;

    public PersonalVocabulary(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences("iris_vocabulary", Context.MODE_PRIVATE);
    }

    // ─────────────────────────── normalisation ───────────────────────────

    static String norm(String s) { return SpeechText.normalizeLoose(s); }

    // ─────────────────────────── phrase corrections ───────────────────────────

    /** Record "I said X but meant Y". Stored normalised; applied only on a full-utterance match. */
    public synchronized boolean addCorrection(String heard, String meant) {
        String h = norm(heard), m = meant == null ? "" : meant.trim();
        if (h.isEmpty() || m.isEmpty() || h.equals(norm(m))) return false;
        try {
            JSONObject all = corrections();
            all.put(h, m);
            // Trim oldest-ish entries if the map grows unreasonably large.
            if (all.length() > MAX_CORRECTIONS) {
                java.util.Iterator<String> it = all.keys();
                if (it.hasNext()) { String first = it.next(); all.remove(first); }
            }
            prefs.edit().putString("corrections", all.toString()).apply();
            bump("corrections_learned");
            return true;
        } catch (Exception e) { return false; }
    }

    public synchronized Map<String, String> allCorrections() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject all = corrections();
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) { String k = keys.next(); out.put(k, all.optString(k, "")); }
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized boolean removeCorrection(String heard) {
        try {
            JSONObject all = corrections();
            all.remove(norm(heard));
            prefs.edit().putString("corrections", all.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    private JSONObject corrections() throws Exception {
        String raw = prefs.getString("corrections", "{}");
        return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
    }

    // ─────────────────────────── name variants ───────────────────────────

    /** Learn that {@code heardVariant} is how this user's "{@code canonical}" gets transcribed. */
    public synchronized boolean addNameVariant(String canonical, String heardVariant) {
        String c = canonical == null ? "" : canonical.trim();
        String v = norm(heardVariant);
        if (c.isEmpty() || v.isEmpty() || v.equals(norm(c))) return false;
        if (SpeechText.PROTECTED_WORDS.contains(v)) return false;   // never shadow a command word
        if (v.length() < 3) return false;                    // too short to be safe
        try {
            JSONObject all = variants();
            JSONArray arr = all.optJSONArray(c);
            if (arr == null) arr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (v.equals(norm(arr.optString(i)))) return true;   // already known
            }
            if (arr.length() >= MAX_VARIANTS_PER_NAME) return false;
            arr.put(v);
            all.put(c, arr);
            prefs.edit().putString("variants", all.toString()).apply();
            bump("variants_learned");
            return true;
        } catch (Exception e) { return false; }
    }

    public synchronized Map<String, List<String>> allNameVariants() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            JSONObject all = variants();
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONArray arr = all.optJSONArray(k);
                List<String> list = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) list.add(s);
                }
                if (!list.isEmpty()) out.put(k, list);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public synchronized boolean removeName(String canonical) {
        try {
            JSONObject all = variants();
            all.remove(canonical == null ? "" : canonical.trim());
            prefs.edit().putString("variants", all.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    private JSONObject variants() throws Exception {
        String raw = prefs.getString("variants", "{}");
        return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw);
    }

    // ─────────────────────────── conservative repair ───────────────────────────

    /**
     * Repair a transcript using only what the user explicitly taught.
     * Order: (1) whole-utterance correction, (2) name variants inside the command head only.
     * Returns the input unchanged when nothing is confidently applicable.
     */
    public String repair(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        try {
            String out = repairWith(text, allCorrections(), allNameVariants());
            if (!out.equals(text)) bump("repairs_applied");
            return out;
        } catch (Exception e) {
            return text;   // never break command handling because of vocabulary
        }
    }

    /**
     * Pure, testable repair — delegates to {@link SpeechText#personalRepair} so the logic can be
     * unit tested without Android. Order: (1) whole-utterance correction, (2) learned name variants
     * inside the command head only.
     */
    static String repairWith(String text, Map<String, String> corrections,
                             Map<String, List<String>> names) {
        return SpeechText.personalRepair(text, corrections, names);
    }

    // ─────────────────────────── counters / summary ───────────────────────────

    private void bump(String key) {
        try { prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply(); } catch (Exception ignored) { }
    }

    public int correctionCount() { return allCorrections().size(); }
    public int nameCount() { return allNameVariants().size(); }
    public int repairsApplied() { return prefs.getInt("repairs_applied", 0); }

    public synchronized void clearAll() {
        prefs.edit().remove("corrections").remove("variants")
                .remove("corrections_learned").remove("variants_learned").remove("repairs_applied").apply();
    }

    /** Human-readable summary for the report screen. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Learned phrase corrections: ").append(correctionCount()).append('\n');
        sb.append("Names with pronunciation variants: ").append(nameCount()).append('\n');
        sb.append("Transcript repairs applied: ").append(repairsApplied()).append("\n\n");
        Map<String, String> corr = allCorrections();
        if (!corr.isEmpty()) {
            sb.append("CORRECTIONS\n");
            int shown = 0;
            for (Map.Entry<String, String> e : corr.entrySet()) {
                sb.append("  \u201C").append(e.getKey()).append("\u201D \u2192 ").append(e.getValue()).append('\n');
                if (++shown >= 20) { sb.append("  \u2026\n"); break; }
            }
            sb.append('\n');
        }
        Map<String, List<String>> names = allNameVariants();
        if (!names.isEmpty()) {
            sb.append("NAME VARIANTS\n");
            int shown = 0;
            for (Map.Entry<String, List<String>> e : names.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
                if (++shown >= 20) { sb.append("  \u2026\n"); break; }
            }
        }
        if (corr.isEmpty() && names.isEmpty()) {
            sb.append("Nothing learned yet. Use \u201CFix what IRIS misheard\u201D after a wrong transcript.");
        }
        return sb.toString();
    }
}
