package com.iris.assistant;

import android.content.Context;

/**
 * Speaker verification using voice embeddings.
 *
 * Currently operates as a placeholder that will integrate with Sherpa-ONNX
 * speaker ID model when available. Without a model, all verification
 * calls return fail-open results (treat as owner) so the app works normally.
 *
 * The voiceprint enrollment and cosine similarity logic is fully functional —
 * only the neural embedding extraction requires a model.
 */
public final class SpeakerVerifier {
    private static final int EMBEDDING_SIZE = 192;
    private boolean modelLoaded;

    /**
     * Attempt to load a speaker model. Returns true if model loaded,
     * false if model is not available (app works without it).
     */
    public boolean loadModel(Context context) {
        // Sherpa-ONNX speaker model integration point
        // When sherpa-models/3dspeaker_speech_eres2net_base.onnx is available,
        // load it here via Sherpa-ONNX speaker embedding API
        modelLoaded = false;
        android.util.Log.i("IRIS", "Speaker model: not yet available (voice verification disabled)");
        return false;
    }

    public boolean isReady() { return modelLoaded; }

    /**
     * Extract a speaker embedding from raw 16kHz PCM audio.
     * Returns null when no model is loaded.
     */
    public float[] extractEmbedding(short[] audio) {
        if (!modelLoaded || audio == null || audio.length < 3200) return null;
        // TODO: Sherpa-ONNX speaker embedding extraction
        return null;
    }

    /**
     * Compute cosine similarity between two L2-normalized embeddings.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    /**
     * Verify if audio belongs to the enrolled speaker.
     * Without a model, returns true (fail-open for usability).
     */
    public boolean verify(short[] audio, float[] voiceprint, float threshold) {
        if (!modelLoaded) return true;
        if (voiceprint == null || voiceprint.length != EMBEDDING_SIZE) return true;
        float[] embedding = extractEmbedding(audio);
        if (embedding == null) return true;
        return cosineSimilarity(embedding, voiceprint) >= threshold;
    }

    /**
     * Three-tier speaker verification.
     * Without a model, returns 2 (owner) — fail-open.
     * @return 2 = owner, 1 = unknown, 0 = stranger
     */
    public int verifyTier(short[] audio, float[] voiceprint, float ownerThreshold, float strangerThreshold) {
        if (!modelLoaded) return 2;
        if (voiceprint == null || voiceprint.length != EMBEDDING_SIZE) return 2;
        float[] embedding = extractEmbedding(audio);
        if (embedding == null) return 2;
        float similarity = cosineSimilarity(embedding, voiceprint);
        if (similarity >= ownerThreshold) return 2;
        if (similarity >= strangerThreshold) return 1;
        return 0;
    }

    /**
     * Compute average voiceprint from multiple audio samples.
     * Without a model, returns null.
     */
    public float[] enrollFromSamples(short[][] samples) {
        if (!modelLoaded || samples == null || samples.length == 0) return null;
        float[] average = new float[EMBEDDING_SIZE];
        int validCount = 0;
        for (short[] sample : samples) {
            float[] embedding = extractEmbedding(sample);
            if (embedding != null) {
                for (int i = 0; i < EMBEDDING_SIZE; i++) average[i] += embedding[i];
                validCount++;
            }
        }
        if (validCount == 0) return null;
        float norm = 0;
        for (int i = 0; i < EMBEDDING_SIZE; i++) {
            average[i] /= validCount;
            norm += average[i] * average[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-6f) {
            for (int i = 0; i < EMBEDDING_SIZE; i++) average[i] /= norm;
        }
        return average;
    }

    /** Release resources. */
    public void close() {
        modelLoaded = false;
    }

    /** @return the expected embedding size (192) */
    public static int embeddingSize() { return EMBEDDING_SIZE; }
}
