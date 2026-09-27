package com.iris.assistant;

import android.content.Context;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class LogStore {
    private static final String FILE_NAME = "iris_activity_v2.enc";
    private static final int MAX_CHARACTERS = 600_000;
    private static final java.util.concurrent.ThreadPoolExecutor WRITER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(128), r -> {
                Thread t = new Thread(r, "IRIS-ActivityLog"); t.setDaemon(true); return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private static final Object LOCK = new Object();

    private LogStore() { }

    public static void append(Context context, String type, String message) {
        AppSettings settings = new AppSettings(context);
        String mode = settings.logMode();
        if (AppSettings.LOG_OFF.equals(mode)) return;
        if (AppSettings.LOG_COMMANDS.equals(mode)
                && ("HEARD".equals(type) || "IGNORED".equals(type) || "PARTIAL".equals(type))) return;
        Context app = context.getApplicationContext();
        // Never run encryption or wait for a full queue on a capture/UI caller.
        String bounded = message == null ? "" : message.substring(0, Math.min(message.length(), 4000));
        try { WRITER.execute(() -> appendNow(app, type, bounded)); }
        catch (java.util.concurrent.RejectedExecutionException full) { /* bounded best-effort diagnostics */ }
    }

    private static void appendNow(Context context, String type, String message) {
        AppSettings settings = new AppSettings(context);
        if (AppSettings.LOG_OFF.equals(settings.logMode())) return;
        migrateLegacy(context);
        synchronized (LOCK) {
            String existing = SecureStore.read(context, FILE_NAME, "");
            existing = applyRetention(existing, settings.retentionDays());
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String safe = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
            String combined = existing + time + "  " + type + "  " + safe + "\n";
            if (combined.length() > MAX_CHARACTERS) {
                int cutAt = combined.length() / 2;
                int lineBreak = combined.indexOf('\n', cutAt);
                combined = lineBreak >= 0 && lineBreak < combined.length() - 1
                        ? combined.substring(lineBreak + 1) : combined.substring(cutAt);
            }
            try { SecureStore.write(context, FILE_NAME, combined); } catch (Exception ignored) { }
        }
    }

    /** Background-only ordered read; pending appends are visible to the export/view. */
    public static String readNewestFirst(Context context) {
        try { return WRITER.submit(() -> readNow(context)).get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return "Activity read interrupted."; }
        catch (Exception error) { return "Activity log busy. Try again."; }
    }

    private static String readNow(Context context) {
        migrateLegacy(context);
        synchronized (LOCK) {
            String content = applyRetention(SecureStore.read(context, FILE_NAME, ""),
                    new AppSettings(context).retentionDays());
            if (content.isEmpty()) return "";
            List<String> lines = new ArrayList<>();
            for (String line : content.split("\\n")) if (!line.trim().isEmpty()) lines.add(line);
            Collections.reverse(lines);
            if (lines.size() > 500) lines = lines.subList(0, 500);
            return String.join("\n\n", lines);
        }
    }

    /** Background-only barrier: older queued appends cannot restore cleared history. */
    public static void clear(Context context) throws Exception {
        WRITER.submit(() -> clearNow(context)).get();
    }

    private static void clearNow(Context context) {
        migrateLegacy(context);
        synchronized (LOCK) {
            try { SecureStore.write(context, FILE_NAME, ""); } catch (Exception error) { throw new IllegalStateException("Activity clear failed", error); }
        }
    }

    private static String applyRetention(String content, int days) {
        if (content.isEmpty() || days <= 0) return content;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder kept = new StringBuilder();
        for (String line : content.split("\\n")) {
            if (line.length() < 19) continue;
            try {
                Date date = format.parse(line.substring(0, 19));
                if (date != null && date.getTime() >= cutoff) kept.append(line).append('\n');
            } catch (ParseException ignored) { }
        }
        return kept.toString();
    }

    private static void migrateLegacy(Context context) {
        File legacy = new File(context.getFilesDir(), "iris_activity.log");
        if (!legacy.exists()) return;
        synchronized (LOCK) {
            try {
                byte[] bytes = new byte[(int) legacy.length()];
                try (FileInputStream input = new FileInputStream(legacy)) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int read = input.read(bytes, offset, bytes.length - offset);
                        if (read < 0) break;
                        offset += read;
                    }
                }
                String old = new String(bytes, StandardCharsets.UTF_8);
                String existing = SecureStore.read(context, FILE_NAME, "");
                SecureStore.write(context, FILE_NAME, old + existing);
                legacy.delete();
            } catch (Exception ignored) { }
        }
    }
}
