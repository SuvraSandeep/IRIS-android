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
