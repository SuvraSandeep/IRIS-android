package com.iris.assistant;

import java.util.List;
import java.util.Locale;

/** Shared by enrollment, the test screen and the live wake service. No Android dependencies. */
public final class WakePolicy {
    private WakePolicy() { }
    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
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
        return sample != null && sample.length == EMBED_DIM && enrolled != null && enrolled.length == EMBED_DIM
                && cosine(sample, enrolled) >= threshold;
    }
    /** Expected dimension of a Vosk speaker x-vector (vosk-model-spk-0.4 produces 128-dim
     *  embeddings). Shared by VoskEngine.extractSpk() and every length check here, so a future
     *  speaker-model swap to a different embedding size fails loudly (owner()/enrollment() both
     *  already reject any length that doesn't match) instead of silently degrading — there was
     *  previously no single named constant tying the two classes' hardcoded "128" together. */
    public static final int EMBED_DIM = 128;
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
     */
    public static float[] enrollment(List<float[]> samples) {
        if (samples == null) return null;
        List<float[]> valid = new java.util.ArrayList<>();
        for (float[] a : samples) if (a != null && a.length == EMBED_DIM && cosine(a, a) >= .99) valid.add(a);
        if (valid.size() < 3) return null;
        // Keep only samples that agree (cosine >= .65) with at least half of the OTHERS —
        // this tolerates one or two inconsistent takes instead of failing on any single one.
        // Compared by index, not object identity: two recordings can legitimately be the same
        // reference (e.g. duplicate samples), and reference equality would wrongly treat a
        // sample as disagreeing with itself.
        // "At least half of the others" must round UP, not down: with integer division,
        // (valid.size()-1)/2 for 3 samples gives (3-1)/2=1, letting a sample pass by agreeing
        // with only 1 of its 2 peers — so two mutually-consistent bad takes could outvote one
        // good take. Math.ceil((valid.size()-1)/2.0) requires agreeing with both peers when
        // there are only 2 others, matching "at least half" for every group size.
        List<float[]> kept = new java.util.ArrayList<>();
        int required = Math.max(1, (int) Math.ceil((valid.size() - 1) / 2.0));
        for (int i = 0; i < valid.size(); i++) {
            float[] a = valid.get(i);
            int agree = 0;
            for (int j = 0; j < valid.size(); j++) {
                if (i == j) continue;
                if (cosine(a, valid.get(j)) >= .65) agree++;
            }
            if (agree >= required) kept.add(a);
        }
        if (kept.size() < 3) return null;
        float[] mean = new float[EMBED_DIM];
        for (float[] a : kept) {
            double norm = 0; for (float v : a) norm += v * (double)v;
            if (norm <= 0) continue;
            for (int i = 0; i < EMBED_DIM; i++) mean[i] += a[i] / Math.sqrt(norm);
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
