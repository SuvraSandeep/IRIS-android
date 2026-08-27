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
    private static final Object LOCK = new Object();

    private LogStore() { }

    public static void append(Context context, String type, String message) {
        migrateLegacy(context);
        AppSettings settings = new AppSettings(context);
        String mode = settings.logMode();
        if (AppSettings.LOG_OFF.equals(mode)) return;
        if (AppSettings.LOG_COMMANDS.equals(mode)
                && ("HEARD".equals(type) || "IGNORED".equals(type) || "PARTIAL".equals(type))) return;
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

    public static String readNewestFirst(Context context) {
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

    public static void clear(Context context) {
        migrateLegacy(context);
        synchronized (LOCK) {
            try { SecureStore.write(context, FILE_NAME, ""); } catch (Exception ignored) { }
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
