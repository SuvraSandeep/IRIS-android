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

import java.io.File;

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
    private static final String MODEL_DIR_NAME = "vosk-model-en-in-0.4";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";

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

    /** Load the Vosk model: bundled assets first, else download at runtime. */
    public void init(Context context, InitListener listener) {
        if (modelLoaded) { main.post(listener::onReady); return; }
        Context app = context.getApplicationContext();
        // 0. If we've already downloaded/extracted the model before, load it directly.
        File extracted = new File(app.getFilesDir(), MODEL_DIR_NAME);
        if (isValidModelDir(extracted)) {
            loadFromPath(extracted.getAbsolutePath(), listener);
            return;
        }
        // 1. Try the model bundled in assets (instant, offline).
        try {
            StorageService.unpack(app, "model-en-us", "vosk-model",
                    (m) -> { model = m; modelLoaded = true; main.post(listener::onReady); },
                    (e) -> {
                        android.util.Log.w("IRIS", "Vosk assets unpack failed, downloading: " + e.getMessage());
                        downloadAndLoad(app, listener);
                    });
        } catch (Throwable t) {
            downloadAndLoad(app, listener);
        }
    }

    private void loadFromPath(String path, InitListener listener) {
        new Thread(() -> {
            try {
                model = new Model(path);
                modelLoaded = true;
                main.post(listener::onReady);
            } catch (Throwable t) {
                modelLoaded = false;
                main.post(() -> listener.onError(t.getMessage()));
            }
        }, "Vosk-Load").start();
    }

    /** Download the small English model (~40 MB) and load it. One-time, then offline. */
    private void downloadAndLoad(Context context, InitListener listener) {
        new Thread(() -> {
            try {
                File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
                if (!isValidModelDir(modelDir)) {
                    File zip = new File(context.getCacheDir(), "vosk-model.zip");
                    android.util.Log.i("IRIS", "Downloading Vosk model…");
                    downloadFile(MODEL_URL, zip);
                    File tmp = new File(context.getFilesDir(), "vosk-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
                    // The zip contains a single top-level folder; move it to modelDir
                    File[] children = tmp.listFiles();
                    File src = (children != null && children.length == 1 && children[0].isDirectory())
                            ? children[0] : tmp;
                    deleteRecursive(modelDir);
                    if (!src.renameTo(modelDir)) copyRecursive(src, modelDir);
                    deleteRecursive(tmp);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
                model = new Model(modelDir.getAbsolutePath());
                modelLoaded = true;
                android.util.Log.i("IRIS", "Vosk model ready (downloaded)");
                main.post(listener::onReady);
            } catch (Throwable t) {
                modelLoaded = false;
                android.util.Log.e("IRIS", "Vosk model download/load failed: " + t.getMessage());
                main.post(() -> listener.onError(t.getMessage()));
            }
        }, "Vosk-Download").start();
    }

    private static boolean isValidModelDir(File dir) {
        return dir.isDirectory() && (new File(dir, "am").exists() || new File(dir, "conf").exists());
    }

    private static void downloadFile(String url, File dest) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static void unzip(File zip, File targetDir) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                // Zip-slip guard
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)) continue;
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.getParentFile().mkdirs();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRecursive(k, new File(dst, k.getName()));
        } else {
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
                    // Do NOT trigger on partial results — too noisy.
                }
                @Override public void onResult(String hypothesis) {
                    if (isExactPhrase(hypothesis, phrase)) {
                        listener.onWakeDetected();
                    }
                }
                @Override public void onFinalResult(String hypothesis) {
                    if (isExactPhrase(hypothesis, phrase)) {
                        listener.onWakeDetected();
                    }
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

    private static boolean isExactPhrase(String hypothesisJson, String phrase) {
        // Vosk grammar mode result: {"text": "nova"}. Since the recognizer is
        // constrained to the phrase (or [unk]), a final result containing the
        // phrase is a reliable, safe wake trigger.
        String text = extractText(hypothesisJson, "text");
        if (text.isEmpty()) return false;
        // Match the phrase as a whole word within the final text
        return text.equals(phrase)
                || text.matches("(^|.*\\s)" + java.util.regex.Pattern.quote(phrase) + "(\\s.*|$)");
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
