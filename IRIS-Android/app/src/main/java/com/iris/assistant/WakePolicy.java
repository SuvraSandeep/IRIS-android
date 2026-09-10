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
        return sample != null && sample.length == 128 && enrolled != null && enrolled.length == 128
                && Double.isFinite(threshold) && threshold >= .65 && threshold <= 1
                && cosine(sample, enrolled) >= threshold;
    }
    public static double threshold(float sensitivity) {
        if (!Float.isFinite(sensitivity)) return .75;
        return .65 + Math.max(0, Math.min(1, sensitivity)) * .20;
    }
    public static float[] enrollment(List<float[]> samples) {
        if (samples == null || samples.size() < 3) return null;
        float[] mean = new float[128];
        for (float[] a : samples) {
            if (a == null || a.length != 128 || cosine(a, a) < .99) return null;
            for (float[] b : samples) if (cosine(a, b) < .65) return null;
            double norm = 0; for (float v : a) norm += v * (double)v;
            for (int i = 0; i < 128; i++) mean[i] += a[i] / Math.sqrt(norm);
        }
        double norm = 0; for (float v : mean) norm += v * (double)v;
        if (norm <= 0) return null;
        for (int i = 0; i < mean.length; i++) mean[i] /= Math.sqrt(norm);
        return mean;
    }
    /** Exact phrase, optionally repeated twice for a short phrase's speaker evidence. */
    public static boolean matchesRepeated(String text, List<String> phrases) {
        if (matches(text, phrases)) return true;
        if (phrases == null) return false;
        String n = normalize(text);
        for (String phrase : phrases) {
            String p = normalize(phrase);
            if (!p.isEmpty() && n.equals(p + " " + p)) return true;
        }
        return false;
    }
    public static boolean ownerAny(float[] sample, List<float[]> enrolled, double threshold) {
        if (enrolled == null || enrolled.isEmpty() || enrolled.size() > 2) return false;
        for (float[] e : enrolled) if (owner(sample, e, threshold)) return true;
        return false;
    }
    /** Phrase confidence is separate from identity: neither gate can bypass the other. */
    public static String rejection(String text, List<String> phrases, double confidence,
                                   double duration, int speakerFrames, float[] embedding) {
        if (!matchesRepeated(text, phrases)) return "phrase mismatch";
        if (!Double.isFinite(confidence) || confidence < .85) return "phrase unclear";
        if (!Double.isFinite(duration) || duration < .25 || duration > 8) return "phrase duration invalid";
        if (speakerFrames < 20 || !owner(embedding, embedding, .99))
            return "not enough voice evidence; say the full phrase twice with a short pause";
        return "";
    }
    /** Relative signal quality, independent of volume. Recognition still verifies speech. */
    public static boolean usableAudio(short[] pcm) {
        if (pcm == null || pcm.length < 8000) return false;
        double[] levels = new double[pcm.length / 320]; int clipped = 0;
        for (int f = 0; f < levels.length; f++) {
            double mean = 0, e = 0;
            for (int i = f*320; i < (f+1)*320; i++) mean += pcm[i];
            mean /= 320;
            for (int i = f*320; i < (f+1)*320; i++) {
                double v = pcm[i]-mean; e += v*v;
                if (Math.abs((int)pcm[i]) > 32000) clipped++;
            }
            levels[f] = Math.sqrt(e / 320);
        }
        java.util.Arrays.sort(levels);
        double noise = Math.max(4, levels[levels.length / 10]);
        int voiced = 0;
        for (double rms : levels) if (rms >= Math.max(24, noise * 2.5)) voiced++;
        return voiced >= 15 && clipped < pcm.length / 100;
    }
}
