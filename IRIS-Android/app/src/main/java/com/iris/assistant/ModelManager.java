package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Manages download and verification of Sherpa-ONNX model files.
 * Models are downloaded once on first launch and stored in app internal storage.
 */
public final class ModelManager {
    private static final String MODEL_DIR = "sherpa-models";
    private static final String BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface DownloadListener {
        void onProgress(String model, int percent);
        void onComplete();
        void onError(String message);
    }

    /** Required model files for IRIS. */
    private static final String[][] REQUIRED_MODELS = {
        // {filename, url, description}
        {"silero_vad.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", "Voice activity detection"},
    };

    private ModelManager() { }

    // ─── Gemma LLM model ───

    /** Candidate filenames the LlmAgent looks for. */
    public static final String GEMMA_FILE = "gemma3-1b-it-int4.task";

    /**
     * Public URL for the Gemma 3 1B int4 .task model (LiteRT community build).
     * If this becomes gated, the user can adb-push the .task file into the model
     * dir manually instead.
     */
    private static final String GEMMA_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true";

    public interface LlmDownloadListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File model);
        void onError(String message);
    }

    public static boolean gemmaPresent(Context context) {
        File f = new File(modelDir(context), GEMMA_FILE);
        return f.exists() && f.length() > 100_000_000L; // > 100 MB sanity check
    }

    /** Download the Gemma LLM model (~550 MB) on a background thread. */
    public static void downloadGemma(Context context, LlmDownloadListener listener) {
        new Thread(() -> {
            File dir = modelDir(context);
            File target = new File(dir, GEMMA_FILE);
            if (gemmaPresent(context)) { main.post(() -> listener.onComplete(target)); return; }
            File temp = new File(dir, GEMMA_FILE + ".tmp");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(GEMMA_URL).openConnection();
                c.setConnectTimeout(20_000);
                c.setReadTimeout(60_000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "IRIS-Android");
                c.connect();
                int code = c.getResponseCode();
                if (code == 401 || code == 403) {
                    main.post(() -> listener.onError("Model is gated. Accept the Gemma license on Hugging Face, or adb-push the .task file into the app model folder."));
                    return;
                }
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buf = new byte[65536];
                    long done = 0; int r; long lastPost = 0;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                        done += r;
                        long now = System.currentTimeMillis();
                        if (now - lastPost > 500) {
                            lastPost = now;
                            long d = done; long t = total;
                            int pct = t > 0 ? (int) (100L * d / t) : -1;
                            main.post(() -> listener.onProgress(pct, d, t));
                        }
                    }
                    out.getFD().sync();
                }
                if (temp.length() < 100_000_000L) {
                    temp.delete();
                    main.post(() -> listener.onError("Download incomplete or blocked. Try again on WiFi, or adb-push the model."));
                    return;
                }
                if (!temp.renameTo(target)) { main.post(() -> listener.onError("Could not save model.")); return; }
                main.post(() -> listener.onComplete(target));
            } catch (Exception e) {
                try { temp.delete(); } catch (Exception ignored) { }
                main.post(() -> listener.onError(e.getMessage()));
            }
        }, "IRIS-GemmaDownload").start();
    }

    /** Get the model directory path. */
    public static File modelDir(Context context) {
        File dir = new File(context.getFilesDir(), MODEL_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Check if all required models are present. */
    public static boolean modelsReady(Context context) {
        File dir = modelDir(context);
        for (String[] model : REQUIRED_MODELS) {
            if (!new File(dir, model[0]).exists()) return false;
        }
        return true;
    }

    /** Download missing models in background. */
    public static void downloadModels(Context context, DownloadListener listener) {
        new Thread(() -> {
            File dir = modelDir(context);
            try {
                for (String[] model : REQUIRED_MODELS) {
                    File target = new File(dir, model[0]);
                    if (target.exists()) continue;
                    String name = model[2];
                    main.post(() -> listener.onProgress(name, 0));
                    downloadFile(model[1], target, (percent) ->
                            main.post(() -> listener.onProgress(name, percent)));
                }
                main.post(listener::onComplete);
            } catch (Exception e) {
                main.post(() -> listener.onError(e.getMessage()));
            }
        }, "IRIS-ModelDownload").start();
    }

    private static void downloadFile(String urlStr, File target, ProgressCallback progress) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "IRIS-Android");
        connection.connect();

        int totalSize = connection.getContentLength();
        File temp = new File(target.getParent(), target.getName() + ".tmp");

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int downloaded = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (totalSize > 0) {
                    int percent = (int) (100L * downloaded / totalSize);
                    progress.onProgress(percent);
                }
            }
            out.getFD().sync();
        }

        if (!temp.renameTo(target)) throw new Exception("Failed to save model file");
    }

    private interface ProgressCallback {
        void onProgress(int percent);
    }
}
