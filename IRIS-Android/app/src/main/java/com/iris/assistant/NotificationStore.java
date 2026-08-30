package com.iris.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory rolling store of recently captured phone notifications.
 * Populated by IrisNotificationListener; read by the voice command handler.
 * Kept in memory only (privacy — notification content is sensitive and never
 * written to disk).
 */
public final class NotificationStore {
    private static final int MAX = 50;
    private static final List<Item> ITEMS = new ArrayList<>();
    private static final Object LOCK = new Object();

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

    public static void add(String pkg, String appLabel, String title, String text, long time) {
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) return;
        synchronized (LOCK) {
            // De-dupe: drop an identical consecutive notification (apps re-post often)
            if (!ITEMS.isEmpty()) {
                Item last = ITEMS.get(0);
                if (last.pkg.equals(pkg) && eq(last.title, title) && eq(last.text, text)) return;
            }
            ITEMS.add(0, new Item(pkg, appLabel == null ? pkg : appLabel,
                    title == null ? "" : title, text == null ? "" : text, time));
            while (ITEMS.size() > MAX) ITEMS.remove(ITEMS.size() - 1);
        }
    }

    /** Most recent notifications, newest first. */
    public static List<Item> recent(int limit) {
        synchronized (LOCK) {
            List<Item> out = new ArrayList<>(ITEMS.subList(0, Math.min(limit, ITEMS.size())));
            return out;
        }
    }

    /** Most recent notifications whose app label or package matches the filter. */
    public static List<Item> recentFor(String appFilter, int limit) {
        String f = appFilter.toLowerCase().trim();
        List<Item> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Item it : ITEMS) {
                if (it.appLabel.toLowerCase().contains(f) || it.pkg.toLowerCase().contains(f)) {
                    out.add(it);
                    if (out.size() >= limit) break;
                }
            }
        }
        return out;
    }

    public static int size() { synchronized (LOCK) { return ITEMS.size(); } }

    public static void clear() { synchronized (LOCK) { ITEMS.clear(); } }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
