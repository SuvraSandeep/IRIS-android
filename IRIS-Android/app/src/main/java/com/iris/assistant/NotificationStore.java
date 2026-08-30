package com.iris.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent rolling store of captured phone notifications, used as context
 * for voice queries ("read my latest notification", "how many WhatsApp
 * messages", "any message from mom on WhatsApp").
 *
 * Persisted (encrypted) via SecureStore so it survives process restarts.
 * Populated by IrisNotificationListener; queried by the voice handler.
 */
public final class NotificationStore {
    private static final int MAX = 100;
    private static final String FILE_NAME = "iris_notifications.json";
    private static final List<Item> ITEMS = new ArrayList<>();
    private static final Object LOCK = new Object();
    private static boolean loaded = false;

    public static class Item {
        public final String pkg;
        public final String appLabel;
        public final String title;
        public final String text;
        public final long time;
        Item(String pkg, String appLabel, String title, String text, long time) {
            this.pkg = pkg; this.appLabel = appLabel;
            this.title = title; this.text = text; this.time = time;
        }
    }

    private NotificationStore() { }

    private static void ensureLoaded(Context context) {
        if (loaded) return;
        try {
            String data = SecureStore.read(context, FILE_NAME, "");
            if (!data.isEmpty()) {
                JSONArray arr = new JSONArray(data);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    ITEMS.add(new Item(o.optString("pkg"), o.optString("app"),
                            o.optString("title"), o.optString("text"), o.optLong("time")));
                }
            }
        } catch (Exception ignored) { }
        loaded = true;
    }

    private static void persist(Context context) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : ITEMS) {
                JSONObject o = new JSONObject();
                o.put("pkg", it.pkg); o.put("app", it.appLabel);
                o.put("title", it.title); o.put("text", it.text); o.put("time", it.time);
                arr.put(o);
            }
            SecureStore.write(context, FILE_NAME, arr.toString());
        } catch (Exception ignored) { }
    }

    public static void add(Context context, String pkg, String appLabel, String title, String text, long time) {
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) return;
        synchronized (LOCK) {
            ensureLoaded(context);
            if (!ITEMS.isEmpty()) {
                Item last = ITEMS.get(0);
                if (last.pkg.equals(pkg) && eq(last.title, title) && eq(last.text, text)) return;
            }
            ITEMS.add(0, new Item(pkg, appLabel == null ? pkg : appLabel,
                    title == null ? "" : title, text == null ? "" : text, time));
            while (ITEMS.size() > MAX) ITEMS.remove(ITEMS.size() - 1);
            persist(context);
        }
    }

    /** Most recent notifications, newest first. */
    public static List<Item> recent(Context context, int limit) {
        synchronized (LOCK) {
            ensureLoaded(context);
            return new ArrayList<>(ITEMS.subList(0, Math.min(limit, ITEMS.size())));
        }
    }

    /** All notifications (optionally filtered by app and/or sender), newest first. */
    public static List<Item> query(Context context, String appFilter, String sender, int limit) {
        String app = appFilter == null ? null : appFilter.toLowerCase().trim();
        String snd = sender == null ? null : sender.toLowerCase().trim();
        List<Item> out = new ArrayList<>();
        synchronized (LOCK) {
            ensureLoaded(context);
            for (Item it : ITEMS) {
                if (app != null && !matchesApp(it, app)) continue;
                if (snd != null && !matchesSender(it, snd)) continue;
                out.add(it);
                if (limit > 0 && out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Count notifications matching optional app/sender filters. */
    public static int count(Context context, String appFilter, String sender) {
        return query(context, appFilter, sender, 0).size();
    }

    public static int total(Context context) {
        synchronized (LOCK) { ensureLoaded(context); return ITEMS.size(); }
    }

    public static void clearAll(Context context) {
        synchronized (LOCK) { ensureLoaded(context); ITEMS.clear(); persist(context); }
    }

    // ─── Matching helpers ───

    /** "whatsapp", "sms" (messaging family), or a literal app-label substring. */
    private static boolean matchesApp(Item it, String app) {
        String label = it.appLabel.toLowerCase();
        String pkg = it.pkg.toLowerCase();
        if (app.equals("sms")) {
            return pkg.contains("mms") || pkg.contains("sms") || pkg.contains("messaging")
                    || label.contains("message");
        }
        return label.contains(app) || pkg.contains(app);
    }

    private static boolean matchesSender(Item it, String sender) {
        return it.title.toLowerCase().contains(sender)
                || it.text.toLowerCase().contains(sender);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
