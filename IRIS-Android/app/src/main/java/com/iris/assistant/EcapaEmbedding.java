package com.iris.assistant;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;

/**
 * Dedicated ECAPA-TDNN speaker-embedding model (SpeechBrain's speechbrain/spkrec-ecapa-voxceleb,
 * trained on VoxCeleb1+2 — thousands of real speakers across real recording conditions), run
 * entirely on-device via ONNX Runtime. This is the PRIMARY identity signal in the redesigned
 * wake-training pipeline (see WAKE-TRAINING-REDESIGN.md) — measurably more accurate and more
 * robust to real domain shift (background noise, far-field/phone-mic distance, compression)
 * than Vosk's smaller bundled x-vector, per current published benchmarks (MDPI 2024: 1.71% EER;
 * Interspeech 2025 robustness study). Vosk's own x-vector (VoskEngine.embed()/extractSpk()) is
 * kept as a SECONDARY, ensemble-fused signal — see WakePolicy.finalScore() — never replaced
 * outright, since it costs nothing extra to compute as a side effect of Vosk's own ASR pass.
 *
 * ============================================================================================
 * KNOWN BLOCKER — MODEL FILE SOURCING (see WAKE-TRAINING-REDESIGN.md "Model integration plan"):
 * SpeechBrain's official repository ships only PyTorch .ckpt checkpoints, not ONNX. Producing
 * the .onnx file this class downloads requires a ONE-TIME export step run on a machine with
 * Python + PyTorch + SpeechBrain installed (see tools/export_ecapa_to_onnx.py in this repo for
 * the exact script). Until that export is run once and the resulting file is hosted somewhere
 * this app can download from (e.g. a GitHub release in this project's own repo, mirroring how
 * VoskEngine.MODEL_URL/SPK_URL point at alphacephei.com), MODEL_URL below is a PLACEHOLDER and
 * load() will fail with a clear, non-crashing error rather than silently pretending to work.
 * ============================================================================================
 *
 * 100% offline after that one-time model acquisition: inference itself never calls any network
 * API, matching this project's standing offline-first requirement.
 */
final class EcapaEmbedding {
    private static final String MODEL_FILE_NAME = "ecapa_tdnn_voxceleb.onnx";
    // PLACEHOLDER — replace with the real hosted URL once tools/export_ecapa_to_onnx.py has
    // been run once and its output uploaded somewhere (see class doc above). Left as an obvious
    // non-resolving placeholder rather than a guessed real-looking URL, so a load failure is
    // immediately traceable to "the export hasn't been done yet" instead of looking like a
    // transient network error.
    private static final String MODEL_URL =
            "https://REPLACE-ME-with-a-real-hosted-url/ecapa_tdnn_voxceleb.onnx";
    private static final int SAMPLE_RATE = 16_000;
    /** ECAPA-TDNN's native output embedding dimension (SpeechBrain's spkrec-ecapa-voxceleb
     *  produces 192-dim embeddings) — deliberately NOT forced to match Vosk's 128-dim
     *  WakePolicy.EMBED_DIM; the two embedding spaces are intentionally independent and only
     *  ever compared against their own respective centroid (see WakePolicy.ECAPA_EMBED_DIM). */
    static final int OUTPUT_DIM = 192;

    private OrtEnvironment env;
    private OrtSession session;
    private volatile boolean closed;
    private final Object lock = new Object();

    interface InitListener {
        void onReady();
        void onError(String message);
    }

    void load(Context context, InitListener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File target = new File(app.getFilesDir(), MODEL_FILE_NAME);
                synchronized (lock) {
                    if (closed) throw new IllegalStateException("Speaker embedding model was closed");
                    if (!isValidModelFile(target)) {
                        boolean bundled = false;
                        try { bundled = copyAsset(app, MODEL_FILE_NAME, target); } catch (Exception ignored) { }
                        if (!bundled) {
                            if (MODEL_URL.contains("REPLACE-ME")) {
                                throw new java.io.IOException(
                                    "ECAPA-TDNN model not bundled and no real download URL is configured yet. "
                                    + "Run tools/export_ecapa_to_onnx.py once, host the resulting .onnx file, "
                                    + "and update EcapaEmbedding.MODEL_URL (see class doc).");
                            }
                            File tmp = new File(app.getCacheDir(), MODEL_FILE_NAME + ".part");
                            try {
                                downloadFile(MODEL_URL, tmp);
                                if (!isValidModelFile(tmp)) throw new java.io.IOException("Downloaded speaker embedding model is incomplete");
                                target.delete();
                                if (!tmp.renameTo(target)) throw new java.io.IOException("Could not install speaker embedding model");
                            } finally {
                                tmp.delete();
                            }
                        }
                    }
                    env = OrtEnvironment.getEnvironment();
                    session = env.createSession(target.getAbsolutePath(), new OrtSession.SessionOptions());
                }
                android.util.Log.i("IRIS", "ECAPA-TDNN speaker embedding model ready");
                listener.onReady();
            } catch (Throwable t) {
                android.util.Log.e("IRIS", "ECAPA-TDNN model load failed: " + t.getMessage());
                listener.onError(t.getMessage());
            }
        }, "IRIS-EcapaEmbedding-Load").start();
    }

    boolean isReady() {
        return session != null && !closed;
    }

    /**
     * Extracts a 192-dim speaker embedding from a (already VAD-trimmed) PCM clip. Returns null
     * on any failure (not ready, invalid audio, malformed output) rather than throwing — mirrors
     * VoskEngine.embed()/extractSpk()'s existing "null means unavailable, caller already handles
     * null" contract, so WakePolicy's validation (NaN/dimension/zero-norm checks) is the single
     * place that decides what a bad embedding means, not scattered exception handling.
     */
    float[] extract(short[] pcm) {
        if (pcm == null || pcm.length < 1600 || !isReady()) return null;
        try {
            synchronized (lock) {
                if (closed || session == null) return null;
                float[] samples = new float[pcm.length];
                for (int i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 32768f;
                try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), new long[]{1, samples.length})) {
                    java.util.Map<String, OnnxTensor> inputs = new java.util.LinkedHashMap<>();
                    inputs.put("wav", inputTensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        float[][] embeddingOut = (float[][]) result.get(0).getValue();
                        float[] embedding = embeddingOut[0];
                        if (embedding.length != OUTPUT_DIM) {
                            android.util.Log.w("IRIS", "ECAPA-TDNN embedding length " + embedding.length
                                    + " != expected " + OUTPUT_DIM + " — model export may not match this wrapper's assumptions.");
                            return null;
                        }
                        return embedding;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "ECAPA-TDNN extract failed: " + t.getMessage());
            return null;
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            try { if (session != null) session.close(); } catch (Exception ignored) { }
            session = null;
        }
    }

    // ─── Helpers (mirrors VoskEngine's own download/validity helpers) ───

    private static boolean isValidModelFile(File f) {
        return f.isFile() && f.length() > 1_000_000; // real ECAPA-TDNN ONNX export is several MB
    }

    private static boolean copyAsset(Context c, String assetName, File dest) throws Exception {
        try (java.io.InputStream in = c.getAssets().open(assetName);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return isValidModelFile(dest);
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
}
