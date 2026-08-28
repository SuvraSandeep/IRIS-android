package com.iris.assistant;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * On-device speaker verification using a TFLite speaker embedding model.
 * Converts audio to a 192-dimensional embedding vector, then compares
 * against a stored voiceprint using cosine similarity.
 */
public final class SpeakerVerifier {
    private static final String MODEL_FILE = "speaker_model.tflite";
    private static final int EMBEDDING_SIZE = 192;
    private static final int NUM_MELS = 80;
    private static final float DEFAULT_THRESHOLD = 0.70f;

    private Interpreter interpreter;
    private boolean modelLoaded;

    /**
     * Load the TFLite speaker model from assets.
     * Returns true if model loaded successfully, false if model file is missing.
     */
    public boolean loadModel(Context context) {
        try {
            MappedByteBuffer model = loadModelFile(context);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(2);
            interpreter = new Interpreter(model, options);
            modelLoaded = true;
            return true;
        } catch (IOException e) {
            android.util.Log.w("IRIS", "Speaker model not found: " + e.getMessage());
            modelLoaded = false;
            return false;
        } catch (Exception e) {
            android.util.Log.e("IRIS", "Speaker model load failed: " + e.getMessage());
            modelLoaded = false;
            return false;
        }
    }

    public boolean isReady() { return modelLoaded && interpreter != null; }

    /**
     * Extract a 192-dimensional speaker embedding from raw 16kHz PCM audio.
     * Returns null if model is not loaded or audio is too short.
     */
    public float[] extractEmbedding(short[] audio) {
        if (!isReady() || audio == null || audio.length < 3200) return null;

        float[][] mel = MelSpectrogram.compute(audio);
        if (mel.length < 10) return null;

        // Prepare input tensor: [1, numFrames, numMels]
        int numFrames = mel.length;
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * numFrames * NUM_MELS);
        inputBuffer.order(ByteOrder.nativeOrder());
        for (int f = 0; f < numFrames; f++) {
            for (int m = 0; m < NUM_MELS; m++) {
                inputBuffer.putFloat(mel[f][m]);
            }
        }
        inputBuffer.rewind();

        // Run inference
        float[][] output = new float[1][EMBEDDING_SIZE];
        try {
            interpreter.resizeInput(0, new int[]{1, numFrames, NUM_MELS});
            interpreter.allocateTensors();
            interpreter.run(inputBuffer, output);
        } catch (Exception e) {
            android.util.Log.e("IRIS", "Speaker inference failed: " + e.getMessage());
            return null;
        }

        // L2 normalize the embedding
        float[] embedding = output[0];
        float norm = 0;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-6f) {
            for (int i = 0; i < embedding.length; i++) embedding[i] /= norm;
        }
        return embedding;
    }

    /**
     * Compute cosine similarity between two L2-normalized embeddings.
     * Returns value in range [-1, 1], where 1 = identical speaker.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // Already L2-normalized, so dot product = cosine similarity
    }

    /**
     * Verify if the given audio belongs to the enrolled speaker.
     * @param audio Raw 16kHz PCM audio
     * @param voiceprint Enrolled speaker's average embedding
     * @param threshold Similarity threshold (default 0.70)
     * @return true if speaker matches
     */
    public boolean verify(short[] audio, float[] voiceprint, float threshold) {
        if (voiceprint == null || voiceprint.length != EMBEDDING_SIZE) return true; // No voiceprint = skip verification
        float[] embedding = extractEmbedding(audio);
        if (embedding == null) return true; // Can't verify = allow (fail-open for usability)
        return cosineSimilarity(embedding, voiceprint) >= threshold;
    }

    /**
     * Compute average voiceprint from multiple audio samples.
     * Used during enrollment to create the owner's voiceprint.
     */
    public float[] enrollFromSamples(short[][] samples) {
        if (!isReady() || samples == null || samples.length == 0) return null;

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

        // Average and L2-normalize
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

    /** Release model resources. */
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        modelLoaded = false;
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream input = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = input.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    /** @return the expected embedding size (192) */
    public static int embeddingSize() { return EMBEDDING_SIZE; }
}
