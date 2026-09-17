package com.iris.assistant;

import java.util.List;
import java.util.Locale;

/** Shared by enrollment, the test screen and the live wake service. No Android dependencies. */
public final class WakePolicy {
    private WakePolicy() { }
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{M}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
    }
    public static boolean matches(String text, List<String> phrases) {
        String n = normalize(text);
        if (n.isEmpty() || phrases == null) return false;
        for (String phrase : phrases) if (n.equals(normalize(phrase))) return true;
        return false;
    }
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return -1;
            dot += a[i] * (double)b[i]; aa += a[i] * (double)a[i]; bb += b[i] * (double)b[i];
        }
        return aa > 0 && bb > 0 ? dot / Math.sqrt(aa * bb) : -1;
    }
    public static boolean owner(float[] sample, float[] enrolled, double threshold) {
        return Double.isFinite(threshold) && threshold >= 0 && threshold <= 1 && sample != null && sample.length == EMBED_DIM && enrolled != null && enrolled.length == EMBED_DIM
                && cosine(sample, enrolled) >= threshold;
    }
    public static boolean ownerEither(float[] sample,float[] normal,float[] quiet,double threshold) {
        return owner(sample,normal,threshold)||owner(sample,quiet,threshold);
    }
    /** Expected dimension of a Vosk speaker x-vector (vosk-model-spk-0.4 produces 128-dim
     *  embeddings). Shared by VoskEngine.extractSpk() and every length check here, so a future
     *  speaker-model swap to a different embedding size fails loudly (owner()/enrollment() both
     *  already reject any length that doesn't match) instead of silently degrading — there was
     *  previously no single named constant tying the two classes' hardcoded "128" together. */
    public static final int EMBED_DIM = 128;
    /** Expected dimension of the dedicated ECAPA-TDNN speaker embedding (EcapaEmbedding.java —
     *  SpeechBrain's spkrec-ecapa-voxceleb produces 192-dim embeddings). Deliberately a SEPARATE
     *  constant from EMBED_DIM, not a shared one: the two embedding spaces (Vosk's x-vector and
     *  the dedicated ECAPA-TDNN model) are intentionally independent and are never compared
     *  against each other, only ever against their own respective centroid — see finalScore()
     *  below, which is a weighted SUM of two independently-computed cosine scores, not a shared
     *  vector-space comparison. See WAKE-TRAINING-REDESIGN.md's Ensemble section. */
    public static final int ECAPA_EMBED_DIM = 192;
    /** Generic owner-match check parameterized by expected embedding dimension, so the same
     *  validation logic (finite threshold in [0,1], correct non-null length, cosine similarity
     *  above the bar) works for both Vosk's 128-dim x-vector and the dedicated ECAPA-TDNN
     *  model's 192-dim embedding without duplicating the check twice. owner() above is kept as
     *  a thin wrapper over this for EMBED_DIM (128), unchanged for any existing caller. */
    public static boolean ownerDim(float[] sample, float[] enrolled, double threshold, int dim) {
        return Double.isFinite(threshold) && threshold >= 0 && threshold <= 1
                && sample != null && sample.length == dim && enrolled != null && enrolled.length == dim
                && cosine(sample, enrolled) >= threshold;
    }
    /**
     * Ensemble score combining the dedicated ECAPA-TDNN embedding (primary, weighted 0.8) with
     * Vosk's own bundled x-vector (secondary/confirmatory, weighted 0.2) — see
     * WAKE-TRAINING-REDESIGN.md's "Ensemble embedding scoring" section for the full rationale.
     * This is a WEIGHTED AVERAGE of two independently-computed cosine similarities, never an
     * "either accepts" OR gate: the old dual-signal design (DTW sound pattern OR speaker
     * embedding, either sufficient to admit) only ever made false acceptance MORE likely, since
     * either signal alone was sufficient. A weighted-average fusion is the opposite risk
     * direction — both models must broadly agree, and either model's individual blind spot is
     * smoothed by the other rather than being a second independent way to get in.
     *
     * Returns a value in [-1, 1] (the same range as cosine()) — NOT a boolean — because the
     * ensemble is meant to be compared against threshold(sensitivity) exactly like a single
     * cosine score would be, keeping the sensitivity-scaling logic in one place regardless of
     * how many models feed into the score.
     *
     * Either embedding may be null/invalid (e.g. the ECAPA-TDNN model failed to load, or Vosk's
     * speaker model isn't ready) — in that case only the available signal is scored, scaled up
     * to compensate for the missing weight, rather than the whole ensemble failing outright.
     * This mirrors this project's standing "graceful degrade over hard failure for a single
     * missing signal" pattern (e.g. SileroVad.trim()'s untrimmed-fallback), while still
     * requiring AT LEAST ONE valid signal — if both are unavailable, returns -1 (never matches),
     * consistent with AGENTS.md's "missing identity/model... must reject wake" contract.
     */
    public static double finalScore(float[] ecapaSample, float[] ecapaCentroid,
                                     float[] voskSample, float[] voskCentroid) {
        boolean ecapaValid = ecapaSample != null && ecapaSample.length == ECAPA_EMBED_DIM
                && ecapaCentroid != null && ecapaCentroid.length == ECAPA_EMBED_DIM;
        boolean voskValid = voskSample != null && voskSample.length == EMBED_DIM
                && voskCentroid != null && voskCentroid.length == EMBED_DIM;
        double ecapaScore = ecapaValid ? cosine(ecapaSample, ecapaCentroid) : Double.NaN;
        double voskScore = voskValid ? cosine(voskSample, voskCentroid) : Double.NaN;
        if (ecapaValid && !Double.isFinite(ecapaScore)) ecapaValid = false;
        if (voskValid && !Double.isFinite(voskScore)) voskValid = false;
        if (!ecapaValid && !voskValid) return -1;
        if (ecapaValid && voskValid) return ECAPA_WEIGHT * ecapaScore + VOSK_WEIGHT * voskScore;
        // Only one signal available: use it directly rather than a partial weighted sum (which
        // would always undershoot a real match by the missing weight's share).
        return ecapaValid ? ecapaScore : voskScore;
    }
    /** Weight given to the dedicated ECAPA-TDNN model in finalScore() — see that method's doc
     *  and WAKE-TRAINING-REDESIGN.md's Ensemble section for why 0.8/0.2 favoring the stronger
     *  dedicated model was chosen as the starting split. */
    public static final double ECAPA_WEIGHT = 0.8;
    public static final double VOSK_WEIGHT = 0.2;
    /** Ensemble owner-match check: finalScore() combined with the same threshold(sensitivity)
     *  bar a single-model owner() check would use. This is the primary accept/reject decision
     *  for the redesigned wake pipeline — see WAKE-TRAINING-REDESIGN.md. */
    public static boolean ownerEnsemble(float[] ecapaSample, float[] ecapaCentroid,
                                         float[] voskSample, float[] voskCentroid, double threshold) {
        return Double.isFinite(threshold) && threshold >= 0 && threshold <= 1
                && finalScore(ecapaSample, ecapaCentroid, voskSample, voskCentroid) >= threshold;
    }
    /**
     * True if the given vector represents a legitimately ABSENT signal (e.g. the ECAPA-TDNN
     * model failed to load/isn't hosted yet, or a live wake detection ran before the model
     * attached) rather than a corrupt/wrong-length one. A null reference and a zero-length
     * array are both treated as "absent" — callers should normalize null to float[0] at the
     * boundary (see OwnerEnrollmentController.absent()) so storage/JSON always sees a
     * consistent, round-trippable empty array instead of sometimes a JSON null.
     *
     * This exists because ownerDim()/enrollment() intentionally return false/null for ANY
     * length mismatch, which is correct for a genuinely corrupt vector but was, before this
     * check existed, indistinguishable from "this signal was never available" — every layer
     * upstream (OwnerEnrollmentController.add(), OwnerVoiceProfile's constructor/create())
     * used to hard-require a valid ECAPA vector on every take, which made training completely
     * unusable while EcapaEmbedding.MODEL_URL is still a placeholder (a real, confirmed
     * on-device failure: every take showed "ECAPA components: 0" and was rejected outright,
     * even though finalScore()/ownerEnsemble() were always designed to gracefully degrade to
     * Vosk-only). Vosk must still always be valid — see WakePolicy.owner()/ownerDim() for that
     * check, which is unaffected by this helper.
     */
    public static boolean isAbsent(float[] v) {
        return v == null || v.length == 0;
    }

    /**
     * Minimum cosine-similarity a captured embedding must reach against the enrolled voiceprint
     * to be accepted as the owner's voice, scaled by the same voiceSensitivity() setting (0..1)
     * as VoskEngine.minWordConfidence — but with an INDEPENDENT, differently-scaled formula:
     *   - this (speaker match):     0.85 (low sensitivity) DOWN to 0.65 (max) — higher sensitivity
     *                               means the app accepts a WEAKER voice match (easier to pass).
     *   - VoskEngine.minWordConfidence (phrase heard clearly): 0.65 (low) down to 0.30 (max) —
     *                               higher sensitivity means a LOWER confidence bar (easier to pass).
     * Both move the SAME direction in both effect and formula shape (higher sensitivity = lower
     * required bar = easier to wake); they just start/end at different numeric bounds because
     * cosine similarity and word confidence aren't the same scale. Previously this formula rose
     * from 0.65 to 0.85 as sensitivity increased, which made turning sensitivity UP make owner
     * voice matching STRICTER — the opposite of every other sensitivity control in the app and
     * the opposite of what this comment claimed. Fixed to descend like minWordConfidence does.
     * Keep both comments in sync if either formula changes — they are read together whenever
     * tuning "how easily does IRIS wake".
     */
    public static double threshold(float sensitivity) {
        if (!Float.isFinite(sensitivity)) return .75;
        return .85 - Math.max(0, Math.min(1, sensitivity)) * .20;
    }
    /**
     * Build an enrollment voiceprint from recorded samples, keeping consistent ones and
     * dropping outliers rather than discarding the whole batch over one noisy/mismatched
     * recording. Previously any single sample failing the .65 pairwise-similarity bar against
     * ANY other sample discarded every sample, so one bad take silently failed enrollment with
     * no way for the trainee to know which recording was the problem. Now each sample is
     * scored against the others; only the samples that agree with the majority are kept, and
     * enrollment only fails if fewer than 3 usable samples remain.
     *
     * REAL ON-DEVICE BUG this section was rewritten to fix (2026-09-17): the original
     * "agree with at least half of the OTHER samples" voting rule has essentially no slack at
     * OwnerTrainingPlan.ENROLLMENT's actual size (4). With 4 samples, each one only has 3
     * peers, and "at least half, rounded up" requires agreeing with 2 of those 3 — so a single
     * take that sounds even moderately different (a real person naturally varies pace/pitch/
     * energy take to take, saying a short phrase differently each time is completely normal,
     * not a defect) can drag a SECOND borderline take below ITS required count too, cascading
     * to fewer than 3 survivors and failing the whole batch — repeatedly, no matter how
     * clearly the phrase was spoken. This voting shape was inherited from the pre-redesign
     * pipeline, which enrolled from 5 raw takes per volume group (10 total) — "agree with half
     * of the other 9" (5 required) has real slack; "agree with half of the other 3" (2
     * required) does not. It was carried over into this redesign's 4-take plan unmodified and
     * never re-validated for the smaller batch size (see WAKE-TRAINING-REDESIGN.md's
     * Calibration section, which explicitly says this aggregator's logic was "kept,
     * unmodified" from the old design).
     *
     * Fixed by making outlier tolerance explicit and count-based instead of a symmetric
     * majority vote: rank every sample by how well it agrees with the OTHERS on average, then
     * keep all of them if at most one is a clear outlier — this directly matches what this
     * method's own doc already promised ("tolerates one or two inconsistent takes") instead of
     * a vote formula that couldn't actually deliver that promise at n=4.
     */
    public static float[] enrollment(List<float[]> samples) {
        return enrollment(samples, EMBED_DIM);
    }
    /** Same algorithm as enrollment(List), parameterized by expected embedding dimension so it
     *  also serves the dedicated ECAPA-TDNN model's 192-dim embeddings (ECAPA_EMBED_DIM) without
     *  duplicating this method. Used by MainActivity's enrollment-boundary pipeline to build
     *  BOTH centroids (ecapaCentroid via enrollment(ecapaTakes, ECAPA_EMBED_DIM) and
     *  voskCentroid via enrollment(voskTakes, EMBED_DIM)) with the same outlier-tolerant logic —
     *  see WAKE-TRAINING-REDESIGN.md's Calibration section. */
    public static float[] enrollment(List<float[]> samples, int dim) {
        if (samples == null) return null;
        List<float[]> valid = new java.util.ArrayList<>();
        for (float[] a : samples) if (a != null && a.length == dim && cosine(a, a) >= .99) valid.add(a);
        if (valid.size() < 3) return null;
        // Rank each sample by its AVERAGE agreement with every other valid sample (not a
        // pass/fail vote against a required count) — this is the same ranking already exposed
        // to the user via MainActivity's "Sound calibration diagnostics" tool, just reused
        // here to decide what to keep instead of only for display.
        double[] avgAgreement = new double[valid.size()];
        for (int i = 0; i < valid.size(); i++) {
            double sum = 0;
            for (int j = 0; j < valid.size(); j++) {
                if (i == j) continue;
                sum += cosine(valid.get(i), valid.get(j));
            }
            avgAgreement[i] = sum / (valid.size() - 1);
        }
        // Tolerate at most ONE clear outlier — an average agreement well below the rest — never
        // more than a third of the batch, so a genuinely bad recording session (not just one
        // differently-paced take) still fails honestly rather than averaging in noise. "Clear
        // outlier" means: this sample's average agreement is at least .15 lower than the
        // MEDIAN of everyone else's average agreement, and its own average agreement is below
        // .55 outright — a real match should still broadly resemble itself even accounting for
        // natural pace/pitch/energy variation between takes of a short phrase; this just stops
        // treating "not exactly like the vote-required count of peers" as a failure.
        int maxOutliers = Math.max(1, valid.size() / 3);
        double[] sortedAgreement = avgAgreement.clone();
        java.util.Arrays.sort(sortedAgreement);
        double median = sortedAgreement[sortedAgreement.length / 2];
        List<float[]> kept = new java.util.ArrayList<>();
        List<Integer> outlierCandidates = new java.util.ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            if (avgAgreement[i] < .55 && avgAgreement[i] < median - .15) outlierCandidates.add(i);
        }
        // Only drop the WORST outlier candidates, up to maxOutliers — if more samples look like
        // outliers than that, the batch is genuinely inconsistent and should fail honestly
        // rather than silently discarding most of the recording session.
        outlierCandidates.sort((a, b) -> Double.compare(avgAgreement[a], avgAgreement[b]));
        java.util.Set<Integer> drop = new java.util.HashSet<>(outlierCandidates.subList(0, Math.min(maxOutliers, outlierCandidates.size())));
        for (int i = 0; i < valid.size(); i++) if (!drop.contains(i)) kept.add(valid.get(i));
        if (kept.size() < 3) return null;
        float[] mean = new float[dim];
        for (float[] a : kept) {
            double norm = 0; for (float v : a) norm += v * (double)v;
            if (norm <= 0) continue;
            for (int i = 0; i < dim; i++) mean[i] += a[i] / Math.sqrt(norm);
        }
        double norm = 0; for (float v : mean) norm += v * (double)v;
        if (norm <= 0) return null;
        for (int i = 0; i < mean.length; i++) mean[i] /= Math.sqrt(norm);
        return mean;
    }
    /** Reject silence, clipping and clips without sustained speech above their own noise floor.
     *  Published acoustic research on whispered speech shows its energy runs roughly 20 dB below
     *  normal phonated speech — about a 10x drop in RMS amplitude. Normal speech into a phone mic
     *  typically lands in the low thousands of RMS; a whisper can genuinely be as low as ~150-300.
     *  The previous fixed 300 floor sat right at (or above) realistic whisper levels, meaning a
     *  real whisper could fail this gate even after VoskEngine's confidence-floor fix — this gate
     *  runs BEFORE any embedding/transcription is attempted, so a rejection here is silent and
     *  looks identical to "wake isn't working". Lowered the floor and the required voiced-frame
     *  count so quiet/whispered training samples aren't thrown out before they're even evaluated;
     *  the noise*3 multiplier is untouched, so it still rejects genuine background hiss/noise. */
    public static boolean usableAudio(short[] pcm) {
        if (pcm == null || pcm.length < 8000) return false;
        double[] levels = new double[pcm.length / 320]; int clipped = 0;
        for (int f = 0; f < levels.length; f++) {
            double e = 0;
            for (int i = f * 320; i < (f + 1) * 320; i++) {
                e += pcm[i] * (double)pcm[i]; if (Math.abs((int)pcm[i]) > 32000) clipped++;
            }
            levels[f] = Math.sqrt(e / 320);
        }
        java.util.Arrays.sort(levels);
        double noise = Math.max(60, levels[levels.length / 10]);
        int voiced = 0; for (double rms : levels) if (rms >= Math.max(150, noise * 3)) voiced++;
        return voiced >= 12 && clipped < pcm.length / 100;
    }
}
