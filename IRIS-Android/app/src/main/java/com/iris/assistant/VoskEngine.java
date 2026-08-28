package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.IOException;

/**
 * Vosk-based voice engine providing:
 * - Wake word detection via grammar-constrained recognition
 * - Streaming speech-to-text
 * - Speaker identification vectors
 *
 * 100% offline, 100% free (Apache 2.0).
 */
public final class VoskEngine {
    private static final int SAMPLE_RATE = 16_000;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Model model;
    private boolean modelLoaded;

    public interface InitListener {
        void onReady();
        void onError(String message);
    }

    /**
     * Initialize Vosk with model from assets.
     * Call from a background thread or use the async version.
     */
    public void init(Context context, InitListener listener) {
        StorageService.unpack(context, "model-en", "model",
                (model) -> {
                    this.model = model;
                    this.modelLoaded = true;
                    main.post(listener::onReady);
                },
                (exception) -> {
                    android.util.Log.e("IRIS", "Vosk model load failed: " + exception.getMessage());
                    modelLoaded = false;
                    main.post(() -> listener.onError(exception.getMessage()));
                });
    }

    public boolean isReady() { return modelLoaded && model != null; }

    /**
     * Create a grammar-constrained recognizer for wake word detection.
     * Only recognizes the specified wake phrase — rejects everything else.
     */
    public Recognizer createWakeRecognizer(String wakePhrase) {
        if (!isReady()) return null;
        try {
            String grammar = "[\"" + wakePhrase.toLowerCase().trim()
                    + "\", \"[unk]\"]";
            return new Recognizer(model, SAMPLE_RATE, grammar);
        } catch (Exception e) {
            android.util.Log.e("IRIS", "Wake recognizer creation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a full vocabulary recognizer for command recognition.
     */
    public Recognizer createCommandRecognizer() {
        if (!isReady()) return null;
        try {
            return new Recognizer(model, SAMPLE_RATE);
        } catch (Exception e) {
            android.util.Log.e("IRIS", "Command recognizer creation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Process a completed audio buffer and return the recognized text.
     * For use with TimedRecorder output.
     */
    public String recognizeAudio(short[] audio) {
        if (!isReady() || audio == null) return "";
        try {
            Recognizer rec = new Recognizer(model, SAMPLE_RATE);
            // Convert short[] to byte[] (little-endian PCM)
            byte[] bytes = new byte[audio.length * 2];
            for (int i = 0; i < audio.length; i++) {
                bytes[i * 2] = (byte) (audio[i] & 0xFF);
                bytes[i * 2 + 1] = (byte) ((audio[i] >> 8) & 0xFF);
            }
            rec.acceptWaveForm(bytes, bytes.length);
            String result = rec.getFinalResult();
            rec.close();
            // Parse JSON result: {"text": "hello world"}
            return extractText(result);
        } catch (Exception e) {
            return "";
        }
    }

    /** Release all resources. */
    public void close() {
        if (model != null) {
            model.close();
            model = null;
        }
        modelLoaded = false;
    }

    /** Extract "text" field from Vosk JSON result. */
    private static String extractText(String json) {
        if (json == null) return "";
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            return obj.optString("text", "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
