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
    /** Reject silence, clipping and clips without sustained speech above their own noise floor. */
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
        double noise = Math.max(100, levels[levels.length / 10]);
        int voiced = 0; for (double rms : levels) if (rms >= Math.max(300, noise * 3)) voiced++;
        return voiced >= 20 && clipped < pcm.length / 100;
    }
}
