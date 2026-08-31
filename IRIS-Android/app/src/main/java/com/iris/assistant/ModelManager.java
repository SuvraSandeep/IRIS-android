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

    // ─── Automatic setup with notifications ───

    private static final String DL_CHANNEL = "iris_download_v1";
    private static final int DL_NOTIF_ID = 4301;

    /**
     * Automatically download the Gemma AI brain if missing, on ANY network.
     * Shows a live progress notification and a completion notification.
     * Notes if it's using mobile data. Retries next launch on failure.
     */
    public static void autoDownloadGemmaIfNeeded(Context context) {
        if (gemmaPresent(context)) return;
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        android.net.Network net = cm.getActiveNetwork();
        if (net == null) {
            createDownloadChannel(context);
            postNotif(context, "\uD83E\uDDE0 IRIS AI brain", "Waiting for a connection to download the AI brain (~550 MB).", false);
            return;
        }
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
        boolean unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        createDownloadChannel(context);
        String netNote = unmetered ? "" : " (using mobile data)";
        postNotif(context, "\uD83E\uDDE0 Downloading IRIS AI brain", "Starting\u2026 (~550 MB, one time)" + netNote, true);
        downloadGemma(context, new LlmDownloadListener() {
            @Override public void onProgress(int percent, long done, long total) {
                String mb = total > 0 ? (done / 1048576) + " / " + (total / 1048576) + " MB" : (done / 1048576) + " MB";
                postProgress(context, "\uD83E\uDDE0 Downloading IRIS AI brain",
                        (percent >= 0 ? percent + "% \u2022 " : "") + mb + netNote, percent);
            }
            @Override public void onComplete(File model) {
                postNotif(context, "\u2705 IRIS AI brain ready!",
                        "Conversational AI is active. Say your wake phrase and chat.", false);
            }
            @Override public void onError(String message) {
                postNotif(context, "\u26A0\uFE0F AI brain download failed",
                        message + " \u2014 will retry next time you open IRIS.", false);
            }
        });
    }

    private static void createDownloadChannel(Context context) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new android.app.NotificationChannel(
                DL_CHANNEL, "IRIS model downloads", android.app.NotificationManager.IMPORTANCE_LOW));
    }

    private static void postNotif(Context context, String title, String text, boolean ongoing) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(DL_NOTIF_ID, new android.app.Notification.Builder(context, DL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setOngoing(ongoing).setOnlyAlertOnce(true).build());
    }

    private static void postProgress(Context context, String title, String text, int percent) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        android.app.Notification.Builder b = new android.app.Notification.Builder(context, DL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true);
        if (percent >= 0) b.setProgress(100, percent, false); else b.setProgress(0, 0, true);
        nm.notify(DL_NOTIF_ID, b.build());
    }

    // ─── Gemma LLM model ───

    /** Candidate filenames the LlmAgent looks for. */
    public static final String GEMMA_FILE = "qwen2.5-0.5b-it-int8.task";

    /**
     * Public URL for the AI brain model. Uses litert-community/Qwen2.5-0.5B-Instruct
     * (Apache-2.0, NOT gated) — ~547 MB, ~1.3 GB peak RAM, so it runs reliably on
     * phones without the out-of-memory crashes the 1.5B model caused.
     * Built for the MediaPipe LLM Inference API (same engine LlmAgent uses).
     */
    private static final String GEMMA_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true";

    public interface LlmDownloadListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File model);
        void onError(String message);
    }

    public static boolean gemmaPresent(Context context) {
        File f = new File(modelDir(context), GEMMA_FILE);
        return f.exists() && f.length() > 100_000_000L; // > 100 MB sanity check
    }

    /** Download the AI brain model (~550 MB) on a background thread. */
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
                String token = new AppSettings(context).hfToken();
                if (token != null && !token.isEmpty()) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                c.connect();
                int code = c.getResponseCode();
                if (code == 401 || code == 403) {
                    boolean hasToken = token != null && !token.isEmpty();
                    String msg = hasToken
                            ? "Access denied. Make sure you clicked Agree/Acknowledge on the license at huggingface.co/litert-community/Gemma3-1B-IT with the same account as your token."
                            : "Model is gated. In Settings, paste a Hugging Face token (after accepting the license at huggingface.co/litert-community/Gemma3-1B-IT).";
                    main.post(() -> listener.onError(msg));
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
