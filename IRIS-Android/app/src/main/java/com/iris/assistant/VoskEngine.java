package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

/**
 * Vosk-based voice engine — the robust, offline core of IRIS.
 *
 * Provides:
 *  - Continuous wake-word detection via grammar-constrained recognition
 *    (only accepts the trained phrase; random noise scores as [unk])
 *  - Continuous speech-to-text for commands
 *
 * 100% offline, 100% free (Apache 2.0). Model bundled in assets/model-en-us.
 */
public final class VoskEngine {
    private static final float SAMPLE_RATE = 16_000f;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Model model;
    private volatile boolean modelLoaded;
    private SpeechService speechService;

    public interface InitListener {
        void onReady();
        void onError(String message);
    }

    public interface WakeListener {
        void onWakeDetected();
        void onError(String message);
    }

    public interface SttListener {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    /** Load the Vosk model from assets (async). Safe to call multiple times. */
    public void init(Context context, InitListener listener) {
        if (modelLoaded) { main.post(listener::onReady); return; }
        StorageService.unpack(context, "model-en-us", "vosk-model",
                (m) -> {
                    model = m;
                    modelLoaded = true;
                    main.post(listener::onReady);
                },
                (e) -> {
                    modelLoaded = false;
                    android.util.Log.e("IRIS", "Vosk model load failed: " + e.getMessage());
                    main.post(() -> listener.onError(e.getMessage()));
                });
    }

    public boolean isReady() { return modelLoaded && model != null; }

    /**
     * Start continuous wake-word detection.
     * Uses a grammar limited to the wake phrase, so only that phrase
     * (spoken as actual speech) triggers detection.
     */
    public void startWakeDetection(String wakePhrase, WakeListener listener) {
        if (!isReady()) { listener.onError("Voice model not ready"); return; }
        stop();
        try {
            String phrase = wakePhrase.toLowerCase().trim();
            String grammar = "[\"" + phrase + "\", \"[unk]\"]";
            Recognizer recognizer = new Recognizer(model, SAMPLE_RATE, grammar);
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            speechService.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) {
                    if (containsPhrase(hypothesis, phrase)) {
                        listener.onWakeDetected();
                    }
                }
                @Override public void onResult(String hypothesis) {
                    if (containsPhrase(hypothesis, phrase)) {
                        listener.onWakeDetected();
                    }
                }
                @Override public void onFinalResult(String hypothesis) { }
                @Override public void onError(Exception e) {
                    listener.onError(e.getMessage());
                }
                @Override public void onTimeout() { }
            });
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
    }

    /**
     * Start continuous speech-to-text for command recognition.
     * Uses the full vocabulary model.
     */
    public void startListening(SttListener listener) {
        if (!isReady()) { listener.onError("Voice model not ready"); return; }
        stop();
        try {
            Recognizer recognizer = new Recognizer(model, SAMPLE_RATE);
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            speechService.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) {
                    String text = extractText(hypothesis, "partial");
                    if (!text.isEmpty()) listener.onPartial(text);
                }
                @Override public void onResult(String hypothesis) {
                    String text = extractText(hypothesis, "text");
                    if (!text.isEmpty()) listener.onFinal(text);
                }
                @Override public void onFinalResult(String hypothesis) {
                    String text = extractText(hypothesis, "text");
                    if (!text.isEmpty()) listener.onFinal(text);
                }
                @Override public void onError(Exception e) {
                    listener.onError(e.getMessage());
                }
                @Override public void onTimeout() { }
            });
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
    }

    /** Stop any active recognition. */
    public void stop() {
        if (speechService != null) {
            try {
                speechService.stop();
                speechService.shutdown();
            } catch (Exception ignored) { }
            speechService = null;
        }
    }

    /** Release all resources. */
    public void close() {
        stop();
        if (model != null) {
            try { model.close(); } catch (Exception ignored) { }
            model = null;
        }
        modelLoaded = false;
    }

    // ─── Helpers ───

    private static boolean containsPhrase(String hypothesisJson, String phrase) {
        String text = extractText(hypothesisJson, "partial");
        if (text.isEmpty()) text = extractText(hypothesisJson, "text");
        return text.contains(phrase);
    }

    private static String extractText(String json, String field) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(field, "").trim().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
