package com.iris.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists in-progress wake-phrase training (captured dual-embedding takes + step) so the user
 * can exit — or have the app's process killed outright, not just backgrounded — and resume
 * later. Redesigned per WAKE-TRAINING-REDESIGN.md's "Persisting in-progress training" section:
 * the old schema (DTW sound-pattern templates + raw PCM samples, a legacy binary format that
 * was never actually wired to the current training flow) is replaced entirely with the new
 * flat dual-embedding state — two pairs of embedding lists (ECAPA-TDNN + Vosk) instead of raw
 * audio/sound-pattern templates, which is both simpler to persist AND avoids storing raw voice
 * audio at rest (embeddings are one-way, per this project's standing privacy design in
 * SPEAKER-VERIFICATION.md).
 *
 * Stored as encrypted JSON via SecureStore (AES-256-GCM) — the same encrypted-storage path
 * already used for the saved owner profile — not a new plaintext file, per this project's
 * standing AGENTS.md contract on audio-processing/identity-adjacent data.
 */
final class TrainingProgress {
    private static final String FILE = "wake_training_v2.enc";
    private static final int SCHEMA = 2;

    static final class Data {
        String phrase = "";
        int takeIndex = 0;
        String sessionRoute = "UNCONFIRMED";
        final List<float[]> ecapaTakes = new ArrayList<>();
        final List<float[]> voskTakes = new ArrayList<>();
        final List<float[]> ecapaHeldOut = new ArrayList<>();
        final List<float[]> voskHeldOut = new ArrayList<>();
    }

    static boolean exists(Context c) {
        return !SecureStore.read(c, FILE, "").isEmpty();
    }

    static void clear(Context c) {
        try { SecureStore.write(c, FILE, ""); } catch (Exception ignored) { }
    }

    /** Saves the current in-progress session. Called after every successfully accepted take
     *  (a state transition), not on every keystroke/level-callback — this keeps writes to a
     *  handful of small (well under 5KB) encrypted blobs per session, not a hot-path cost. */
    static void save(Context c, String phrase, int takeIndex, String sessionRoute,
                      List<float[]> ecapaTakes, List<float[]> voskTakes,
                      List<float[]> ecapaHeldOut, List<float[]> voskHeldOut) {
        try {
            JSONObject j = new JSONObject()
                .put("schema", SCHEMA)
                .put("phrase", phrase == null ? "" : phrase)
                .put("takeIndex", takeIndex)
                .put("sessionRoute", sessionRoute == null ? "UNCONFIRMED" : sessionRoute)
                .put("ecapaTakes", vectorArray(ecapaTakes))
                .put("voskTakes", vectorArray(voskTakes))
                .put("ecapaHeldOut", vectorArray(ecapaHeldOut))
                .put("voskHeldOut", vectorArray(voskHeldOut));
            SecureStore.write(c, FILE, j.toString());
        } catch (Exception ignored) {
            // A failed save must never crash or corrupt an in-progress session — worst case,
            // the resume prompt simply won't appear next time and the user starts fresh, which
            // is the same behavior as this feature not existing at all.
        }
    }

    /** Loads the persisted session, or null if none exists / it's corrupt / it's from an
     *  incompatible schema. Never throws — a corrupt or foreign-schema blob is treated exactly
     *  like "no session," not a crash. */
    static Data load(Context c) {
        String raw = SecureStore.read(c, FILE, "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(raw);
            if (j.optInt("schema", -1) != SCHEMA) return null;
            Data d = new Data();
            d.phrase = j.optString("phrase", "");
            d.takeIndex = j.optInt("takeIndex", 0);
            d.sessionRoute = j.optString("sessionRoute", "UNCONFIRMED");
            readInto(d.ecapaTakes, j.optJSONArray("ecapaTakes"), WakePolicy.ECAPA_EMBED_DIM);
            readInto(d.voskTakes, j.optJSONArray("voskTakes"), WakePolicy.EMBED_DIM);
            readInto(d.ecapaHeldOut, j.optJSONArray("ecapaHeldOut"), WakePolicy.ECAPA_EMBED_DIM);
            readInto(d.voskHeldOut, j.optJSONArray("voskHeldOut"), WakePolicy.EMBED_DIM);
            // Reconcile: takeIndex must never point past what was actually captured — a
            // partial/interrupted save could otherwise leave it stale.
            int captured = d.ecapaTakes.size() + d.ecapaHeldOut.size();
            if (d.takeIndex < 0 || d.takeIndex > captured) d.takeIndex = captured;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray vectorArray(List<float[]> vectors) throws Exception {
        JSONArray out = new JSONArray();
        if (vectors == null) return out;
        for (float[] v : vectors) {
            JSONArray row = new JSONArray();
            for (float f : v) row.put((double) f);
            out.put(row);
        }
        return out;
    }

    private static void readInto(List<float[]> target, JSONArray source, int expectedDim) throws Exception {
        if (source == null) return;
        if (source.length() > 12) throw new IllegalArgumentException("Too many persisted takes");
        for (int i = 0; i < source.length(); i++) {
            JSONArray row = source.getJSONArray(i);
            if (row.length() != expectedDim) throw new IllegalArgumentException("Wrong embedding dimension in persisted take");
            float[] v = new float[row.length()];
            for (int k = 0; k < v.length; k++) v[k] = (float) row.getDouble(k);
            target.add(v);
        }
    }
}
