package com.iris.assistant;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Collections;
import java.util.Map;

/**
 * Silero VAD (github.com/snakers4/silero-vad) — a small, pretrained, MIT-licensed neural
 * voice-activity detector, run entirely on-device via ONNX Runtime. Replaces the old hand-
 * rolled energy-percentile noise-floor gate that used to live inside the now-deleted
 * SoundPattern.extract() (see WAKE-TRAINING-REDESIGN.md): this model was specifically trained
 * and validated across "over 100 languages... different domains with various background noise
 * and quality levels," rather than a single hand-picked energy multiplier tuned against
 * whatever recordings happened to be tested during development.
 *
 * This class ONLY answers "is this a voice, and where does it start/end" — it never
 * transcribes anything and it is never itself an identity signal. It is a pure trim/gate step
 * that runs BEFORE the speaker-embedding models (EcapaEmbedding, VoskEngine.embed()) ever see
 * the audio, exactly mirroring the "separate speech from background" vs. "separate this
 * person's voice from other voices" split described in the redesign doc's first-principles
 * section. Deliberately kept as a single trim() call, not a second competing accept/reject
 * decision, so it cannot reintroduce the old two-independent-matchers problem this whole
 * redesign eliminates.
 *
 * 100% offline after the one-time model download: the model runs entirely on-device via ONNX
 * Runtime Mobile, no network call at inference time.
 */
final class SileroVad {
    private static final String MODEL_FILE_NAME = "silero_vad.onnx";
    // Canonical upstream repo's directly-downloadable ONNX file (verified to exist at this path
    // — snakers4/silero-vad, src/silero_vad/data/silero_vad.onnx — same "download the real
    // published artifact over plain HTTPS" pattern as VoskEngine's MODEL_URL/SPK_URL).
    private static final String MODEL_URL =
            "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx";
    private static final int SAMPLE_RATE = 16_000;
    // Silero VAD's published window sizes for 16kHz input (its ONNX graph accepts variable
    // length, but the model was trained/validated at these frame sizes).
    private static final int WINDOW_SAMPLES = 512;
    // A window's speech probability at/above this is treated as voiced. 0.5 is Silero's own
    // documented default operating point.
    private static final float SPEECH_THRESHOLD = 0.5f;
    // Pad this many samples of context on each side of the detected speech span so a clipped
    // consonant/vowel at the boundary isn't cut off — mirrors the old energy-gate's "first*320-320
    // .. (last+2)*320" padding behavior, just against the new model's boundaries instead.
    private static final int PAD_SAMPLES = 1600; // 100ms

    private OrtEnvironment env;
    private OrtSession session;
    private volatile boolean closed;
    private final Object lock = new Object();

    interface InitListener {
        void onReady();
        void onError(String message);
    }

    /** Load the model: bundled asset first, else download at runtime — same precedence as
     *  VoskEngine's model loading. */
    void load(Context context, InitListener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File target = new File(app.getFilesDir(), MODEL_FILE_NAME);
                synchronized (lock) {
                    if (closed) throw new IllegalStateException("Voice activity model was closed");
                    if (!isValidModelFile(target)) {
                        boolean bundled = false;
                        try { bundled = copyAsset(app, MODEL_FILE_NAME, target); } catch (Exception ignored) { }
                        if (!bundled) {
                            File tmp = new File(app.getCacheDir(), MODEL_FILE_NAME + ".part");
                            try {
                                downloadFile(MODEL_URL, tmp);
                                if (!isValidModelFile(tmp)) throw new java.io.IOException("Downloaded voice activity model is incomplete");
                                target.delete();
                                if (!tmp.renameTo(target)) throw new java.io.IOException("Could not install voice activity model");
                            } finally {
                                tmp.delete();
                            }
                        }
                    }
                    env = OrtEnvironment.getEnvironment();
                    session = env.createSession(target.getAbsolutePath(), new OrtSession.SessionOptions());
                }
                android.util.Log.i("IRIS", "Silero VAD ready");
                listener.onReady();
            } catch (Throwable t) {
                android.util.Log.e("IRIS", "Silero VAD load failed: " + t.getMessage());
                listener.onError(t.getMessage());
            }
        }, "IRIS-SileroVad-Load").start();
    }

    boolean isReady() {
        return session != null && !closed;
    }

    /**
     * Trims a PCM clip down to just the detected speech span (with padding), using Silero VAD's
     * real per-window speech probability instead of an energy-percentile heuristic. Falls back
     * to returning the ORIGINAL, untrimmed array if the model isn't loaded or finds no clear
     * speech span — this is a deliberate graceful degrade (never corrupt/truncate a clip when
     * the trim step itself is uncertain), matching the redesign doc's "falls back gracefully
     * rather than corrupting the clip" requirement.
     */
    short[] trim(short[] pcm) {
        if (pcm == null || pcm.length < WINDOW_SAMPLES) return pcm;
        if (!isReady()) return pcm;
        try {
            synchronized (lock) {
                if (closed || session == null) return pcm;
                int windows = pcm.length / WINDOW_SAMPLES;
                if (windows == 0) return pcm;
                int firstVoiced = -1, lastVoiced = -1;
                float[] state = new float[2 * 1 * 128]; // Silero VAD's recurrent state shape (2, 1, 128)
                for (int w = 0; w < windows; w++) {
                    float[] frame = new float[WINDOW_SAMPLES];
                    int base = w * WINDOW_SAMPLES;
                    for (int i = 0; i < WINDOW_SAMPLES; i++) frame[i] = pcm[base + i] / 32768f;
                    float prob = runWindow(frame, state);
                    if (prob >= SPEECH_THRESHOLD) {
                        if (firstVoiced < 0) firstVoiced = base;
                        lastVoiced = base + WINDOW_SAMPLES;
                    }
                }
                if (firstVoiced < 0) return pcm; // no speech detected at all -- let the caller's
                                                  // own downstream validity checks reject it,
                                                  // rather than this trim step silently emptying it
                int start = Math.max(0, firstVoiced - PAD_SAMPLES);
                int end = Math.min(pcm.length, lastVoiced + PAD_SAMPLES);
                if (end <= start) return pcm;
                short[] out = new short[end - start];
                System.arraycopy(pcm, start, out, 0, out.length);
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w("IRIS", "Silero VAD trim failed, using untrimmed clip: " + t.getMessage());
            return pcm;
        }
    }

    /** Runs one 512-sample window through the model, updating the recurrent state in place.
     *  Silero VAD's ONNX graph I/O: input (1, 512) float audio, sr (int64 16000), state
     *  (2, 1, 128) float recurrent state in/out. Output: prob (1, 1) float, stateN (2, 1, 128). */
    private float runWindow(float[] frame, float[] state) throws Exception {
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), new long[]{1, WINDOW_SAMPLES});
             OnnxTensor srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{SAMPLE_RATE}), new long[]{1});
             OnnxTensor stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), new long[]{2, 1, 128})) {
            Map<String, OnnxTensor> inputs = new java.util.LinkedHashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("sr", srTensor);
            inputs.put("state", stateTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] probOut = (float[][]) result.get(0).getValue();
                float[][][] stateOut = (float[][][]) result.get(1).getValue();
                for (int i = 0; i < 2; i++) for (int j = 0; j < 128; j++) state[i * 128 + j] = stateOut[i][0][j];
                return probOut[0][0];
            }
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
        return f.isFile() && f.length() > 100_000; // real silero_vad.onnx is ~1-2MB; a
                                                      // truncated download is far smaller
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
