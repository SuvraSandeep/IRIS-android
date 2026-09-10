package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
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
 * 100% offline, 100% free (Apache 2.0). Model bundled in assets/model-en-in.
 */
public final class VoskEngine {
    private static final float SAMPLE_RATE = 16_000f;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final String MODEL_DIR_NAME = "vosk-model-en-in-0.4";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";
    // Optional high-accuracy model (~1GB), downloaded on first use when the user opts in.
    private static final String LARGE_DIR_NAME = "vosk-model-en-in-0.5";
    private static final String LARGE_URL =
            "https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip";
    private static final String SPK_DIR_NAME = "vosk-model-spk-0.4";
    private static final String SPK_URL =
            "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip";

    private Model model;
    private volatile boolean modelLoaded;
    private Object spkModel;   // org.vosk.SpkModel via reflection (may be absent)
    private volatile boolean spkReady;
    private SpeechService speechService;

    public interface InitListener {
        void onReady();
        void onError(String message);
    }

    public interface WakeListener {
        /** @param voiceEmbedding Vosk speaker x-vector for the wake utterance, or null if unavailable. */
        void onWakeDetected(float[] voiceEmbedding);
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
        // High-accuracy path (opt-in): use the large en-IN model, downloaded on first use.
        // Skipped entirely under IRIS battery saver — the ~1GB model stays resident the whole
        // time IRIS listens, which is exactly the RAM cost battery saver exists to avoid.
        boolean large = false;
        try { large = new AppSettings(app).highAccuracyVoice() && !new AppSettings(app).irisPowerSaver(); } catch (Throwable ignored) { }
        if (large) {
            File lg = new File(app.getFilesDir(), LARGE_DIR_NAME);
            if (isValidModelDir(lg)) { loadFromPath(lg.getAbsolutePath(), listener); return; }
            downloadAndLoadLarge(app, listener);
            return;
        }
        // 0. If we've already downloaded/extracted the model before, load it directly.
        File extracted = new File(app.getFilesDir(), MODEL_DIR_NAME);
        if (isValidModelDir(extracted)) {
            loadFromPath(extracted.getAbsolutePath(), listener);
            return;
        }
        loadBundledIndianOrDownload(app, listener);
    }

    /** The upstream ZIP has no StorageService uuid asset. Copy into a staged Indian-only directory. */
    private void loadBundledIndianOrDownload(Context app, InitListener listener) {
        if (!assetDirExists(app, "model-en-in")) { downloadAndLoad(app, listener); return; }
        new Thread(() -> {
            File staging = new File(app.getFilesDir(), "vosk-indian-bundle-staging");
            File target = new File(app.getFilesDir(), MODEL_DIR_NAME);
            try {
                deleteRecursive(staging);
                copyAssetDir(app, "model-en-in", staging);
                if (!isValidModelDir(staging)) throw new java.io.IOException("Incomplete Indian voice bundle");
                if (!isValidModelDir(target)) {
                    deleteRecursive(target);
                    if (!staging.renameTo(target)) throw new java.io.IOException("Could not install Indian voice bundle");
                }
                deleteRecursive(staging);
                model = new Model(target.getAbsolutePath());
                modelLoaded = true;
                main.post(listener::onReady);
            } catch (Throwable t) {
                deleteRecursive(staging);
                android.util.Log.w("IRIS", "Indian voice bundle unavailable: " + t.getMessage());
                downloadAndLoad(app, listener);
            }
        }, "IRIS-IndianVoice-Install").start();
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
        return dir.isDirectory() && new File(dir, "am/final.mdl").length() > 0
                && new File(dir, "conf/model.conf").length() > 0
                && new File(dir, "graph").isDirectory();
    }

    /** Download the large en-IN model (~1GB) and load it; fall back to the small model on any failure. */
    private void downloadAndLoadLarge(Context context, InitListener listener) {
        new Thread(() -> {
            try {
                File modelDir = new File(context.getFilesDir(), LARGE_DIR_NAME);
                if (!isValidModelDir(modelDir)) {
                    File zip = new File(context.getCacheDir(), "vosk-large.zip");
                    android.util.Log.i("IRIS", "Downloading large Vosk model (~1GB, one-time)…");
                    downloadFile(LARGE_URL, zip);
                    File tmp = new File(context.getFilesDir(), "vosk-large-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
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
                android.util.Log.i("IRIS", "Large Vosk model ready");
                main.post(listener::onReady);
            } catch (Throwable t) {
                android.util.Log.w("IRIS", "Large model unavailable, falling back to small: " + t.getMessage());
                main.post(() -> fallbackSmall(context, listener));
            }
        }, "Vosk-Large-Download").start();
    }

    /** Load the bundled/small model (used as the automatic fallback). */
    private void fallbackSmall(Context app, InitListener listener) {
        File extracted = new File(app.getFilesDir(), MODEL_DIR_NAME);
        if (isValidModelDir(extracted)) { loadFromPath(extracted.getAbsolutePath(), listener); return; }
        loadBundledIndianOrDownload(app, listener);
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
    public void startWakeDetection(String phrase, WakeListener listener) {
        startWakeDetection(java.util.Collections.singletonList(phrase), listener);
    }

    private volatile long wakeGeneration;
    public void startWakeDetection(java.util.List<String> phrases, WakeListener listener) {
        startWakeDetection(phrases, listener, true);
    }
    /**
     * @param requireSpeakerModel When false, wake fires on the phrase alone: no speaker model
     *   is attached to the recognizer, and the spk_frames gate below is skipped. This is what
     *   makes the "phrase alone is enough" design (8.4.0) actually true end-to-end — previously
     *   this method unconditionally required isSpeakerReady() and unconditionally attached the
     *   speaker model, so wake could never fire without it even when the caller (and the user's
     *   own settings) never asked for voice verification at all.
     */
    public void startWakeDetection(java.util.List<String> phrases, WakeListener listener, boolean requireSpeakerModel) {
        if (!isReady()) { listener.onError("Voice model not ready"); return; }
        boolean attachSpeaker = requireSpeakerModel && isSpeakerReady();
        if (requireSpeakerModel && !isSpeakerReady()) { listener.onError("Owner verification model not ready"); return; }
        stop();
        final long generation = wakeGeneration;
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            final java.util.List<String> norm = new java.util.ArrayList<>();
            JSONArray grammar = new JSONArray();
            for (String raw : phrases) {
                String phrase = WakePolicy.normalize(raw);
                if (!phrase.isEmpty() && !norm.contains(phrase)) { norm.add(phrase); grammar.put(phrase); }
            }
            if (norm.isEmpty()) { listener.onError("No wake phrase"); return; }
            grammar.put("[unk]");
            Recognizer rec = new Recognizer(model, SAMPLE_RATE, grammar.toString());
            rec.setWords(true);
            if (attachSpeaker) {
                try {
                    rec.getClass().getMethod("setSpkModel", Class.forName("org.vosk.SpkModel"))
                            .invoke(rec, spkModel);
                } catch (Throwable error) { rec.close(); throw new IllegalStateException("Speaker attachment failed", error); }
            }
            final boolean spkAttached = attachSpeaker;
            speechService = new SpeechService(rec, SAMPLE_RATE);
            speechService.startListening(new RecognitionListener() {
                private void result(String json) {
                    if (generation != wakeGeneration || fired.get()) return;
                    try {
                        JSONObject result = new JSONObject(json);
                        if (!WakePolicy.matches(result.optString("text"), norm)) return;
                        JSONArray words = result.optJSONArray("result");
                        if (words == null || words.length() == 0) return;
                        double score = 1;
                        for (int i = 0; i < words.length(); i++) score = Math.min(score, words.getJSONObject(i).optDouble("conf", 0));
                        double duration = words.getJSONObject(words.length()-1).optDouble("end", 0)
                                - words.getJSONObject(0).optDouble("start", 0);
                        if (!Double.isFinite(score) || score < .85 || duration < .5 || duration > 4) return;
                        // The spk_frames field only appears when a speaker model is attached to
                        // the recognizer — gate on it only when we actually attached one.
                        if (spkAttached && result.optInt("spk_frames", 0) < 50) return;
                        if (fired.compareAndSet(false, true)) listener.onWakeDetected(spkAttached ? extractSpk(json) : null);
                    } catch (Exception ignored) { /* malformed results cannot wake */ }
                }
                @Override public void onPartialResult(String h) { }
                @Override public void onResult(String h) { result(h); }
                @Override public void onFinalResult(String h) { result(h); }
                @Override public void onError(Exception e) {
                    if (generation == wakeGeneration && fired.compareAndSet(false, true)) listener.onError(e.getMessage());
                }
                @Override public void onTimeout() { }
            });
        } catch (Exception error) { listener.onError(error.getMessage()); }
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
        wakeGeneration++;
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
        if (spkModel != null) {
            try { spkModel.getClass().getMethod("close").invoke(spkModel); } catch (Throwable ignored) { }
            spkModel = null;
        }
        spkReady = false;
        modelLoaded = false;
    }

    // ─── Speaker model (voice verification) ───

    public boolean isSpeakerReady() { return spkReady && spkModel != null; }

    /** Load the Vosk speaker model (bundled in assets/spk-model, else downloaded). Non-fatal. */
    public void initSpeaker(Context context) {
        if (spkReady) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File dir = new File(app.getFilesDir(), SPK_DIR_NAME);
                if (!isValidSpkDir(dir)) {
                    // Try bundled asset folder "spk-model" → copy to files.
                    if (assetDirExists(app, "spk-model")) {
                        deleteRecursive(dir);
                        copyAssetDir(app, "spk-model", dir);
                    }
                }
                if (!isValidSpkDir(dir)) {
                    // Fall back to a one-time download.
                    File zip = new File(app.getCacheDir(), "vosk-spk.zip");
                    downloadFile(SPK_URL, zip);
                    File tmp = new File(app.getFilesDir(), "spk-tmp");
                    deleteRecursive(tmp);
                    unzip(zip, tmp);
                    File[] kids = tmp.listFiles();
                    File src = (kids != null && kids.length == 1 && kids[0].isDirectory()) ? kids[0] : tmp;
                    deleteRecursive(dir);
                    if (!src.renameTo(dir)) copyRecursive(src, dir);
                    deleteRecursive(tmp);
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                }
                if (isValidSpkDir(dir)) {
                    Class<?> spkClass = Class.forName("org.vosk.SpkModel");
                    spkModel = spkClass.getConstructor(String.class).newInstance(dir.getAbsolutePath());
                    spkReady = true;
                    android.util.Log.i("IRIS", "Vosk speaker model ready");
                }
            } catch (Throwable t) {
                spkReady = false;
                android.util.Log.w("IRIS", "Speaker model unavailable (voice verification off): " + t.getMessage());
            }
        }, "Vosk-Spk-Load").start();
    }

    private static boolean isValidSpkDir(File dir) {
        return dir != null && new File(dir, "final.ext.raw").length() > 0
                && new File(dir, "mean.vec").length() > 0
                && new File(dir, "transform.mat").length() > 0;
    }

    private static boolean assetDirExists(Context c, String name) {
        try { String[] f = c.getAssets().list(name); return f != null && f.length > 0; }
        catch (Exception e) { return false; }
    }

    private static void copyAssetDir(Context c, String assetPath, File dst) throws Exception {
        String[] entries = c.getAssets().list(assetPath);
        if (entries == null || entries.length == 0) return;
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        for (String e : entries) {
            String childAsset = assetPath + "/" + e;
            String[] sub = c.getAssets().list(childAsset);
            if (sub != null && sub.length > 0) {
                copyAssetDir(c, childAsset, new File(dst, e));
            } else {
                try (java.io.InputStream in = c.getAssets().open(childAsset);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dst, e))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
        }
    }

    /** Transcribe a PCM clip (16kHz mono) with the full vocabulary — used to learn how the
     *  user pronounces a command word. Returns lowercase text, or "" on failure. */
    public String transcribe(short[] pcm) {
        if (!isReady() || pcm == null || pcm.length < 1600) return "";
        try {
            Recognizer rec = new Recognizer(model, SAMPLE_RATE);
            rec.acceptWaveForm(pcm, pcm.length);
            String json = rec.getFinalResult();
            rec.close();
            return extractText(json, "text");
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "transcribe failed: " + t.getMessage());
            return "";
        }
    }

    /** Compute a speaker x-vector for a PCM clip (16kHz mono). Null if unavailable. */
    public float[] embed(short[] pcm) {
        if (!isReady() || !isSpeakerReady() || pcm == null || pcm.length < 3200) return null;
        try {
            Class<?> spkClass = Class.forName("org.vosk.SpkModel");
            Recognizer rec = (Recognizer) Recognizer.class
                    .getConstructor(Model.class, float.class, spkClass)
                    .newInstance(model, SAMPLE_RATE, spkModel);
            rec.acceptWaveForm(pcm, pcm.length);
            String json = rec.getFinalResult();
            rec.close();
            return extractSpk(json);
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "embed failed: " + t.getMessage());
            return null;
        }
    }

    private static float[] extractSpk(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray spk = o.optJSONArray("spk");
            if (spk == null) return null;
            float[] v = new float[spk.length()];
            for (int i = 0; i < v.length; i++) v[i] = (float) spk.getDouble(i);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Helpers ───

    private static String extractText(String json, String field) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(field, "").trim().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
