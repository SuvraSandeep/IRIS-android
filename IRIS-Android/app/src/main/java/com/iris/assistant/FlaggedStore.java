package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent store for messages and items flagged as urgent or pinned to the assistant queue.
 *
 * Dedicated preferences file ("iris_flagged_items") so that modifying or clearing flagged
 * items never touches responder settings, notification archive, or alarm reminder queues.
 * Bounded to {@link #MAX} items with oldest completed items pruned first.
 */
public final class FlaggedStore {

    public static final String KIND_PINNED = "PINNED";
    public static final String KIND_FLAGGED = "FLAGGED";

    public static final class Item {
        public long id;
        public String kind = KIND_FLAGGED;
        public String text = "";
        public String sender = "";
        public String appLabel = "";
        public String reason = "";
        public long createdMs;
        public boolean completed;

        public boolean isPinned() { return KIND_PINNED.equalsIgnoreCase(kind); }
        public boolean isFlagged() { return KIND_FLAGGED.equalsIgnoreCase(kind); }
    }

    private static final String PREFS = "iris_flagged_items";
    private static final String KEY = "items";
    public static final int MAX = 200;

    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    private final SharedPreferences prefs;

    public FlaggedStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static FlaggedStore get(Context context) {
        return new FlaggedStore(context);
    }

    /** Add a pinned item and return its id. */
    public synchronized long addPinned(String text, String sender, String appLabel,
                                       String reason, long nowMs) {
        return add(KIND_PINNED, text, sender, appLabel, reason, nowMs);
    }

    /** Add a flagged urgent item and return its id. */
    public synchronized long addFlagged(String text, String sender, String appLabel,
                                        String reason, long nowMs) {
        return add(KIND_FLAGGED, text, sender, appLabel, reason, nowMs);
    }

    /** Add an item of specified kind. */
    public synchronized long add(String kind, String text, String sender, String appLabel,
                                 String reason, long nowMs) {
        List<Item> items = all();
        Item item = new Item();
        item.id = nowMs > 0 ? nowMs : System.currentTimeMillis();
        item.kind = KIND_PINNED.equalsIgnoreCase(kind) ? KIND_PINNED : KIND_FLAGGED;
        item.text = text == null ? "" : text.trim();
        item.sender = sender == null ? "" : sender.trim();
        item.appLabel = appLabel == null ? "" : appLabel.trim();
        item.reason = reason == null ? "" : reason.trim();
        item.createdMs = item.id;
        item.completed = false;

        // Dedup: if identical text and sender arrived in last 5 seconds, don't duplicate
        for (Item existing : items) {
            if (existing.sender.equalsIgnoreCase(item.sender)
                    && existing.text.equals(item.text)
                    && Math.abs(existing.createdMs - item.createdMs) < 5000L) {
                return existing.id;
            }
        }

        items.add(0, item); // Insert at beginning (newest first)

        // Prune: drop oldest completed items first, then oldest overall
        while (items.size() > MAX) {
            int victim = -1;
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).completed) {
                    victim = i;
                    break;
                }
            }
            items.remove(victim >= 0 ? victim : items.size() - 1);
        }

        write(items);
        return item.id;
    }

    /** Returns all items sorted newest first. */
    public List<Item> all() {
        List<Item> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "");
        if (raw.isEmpty()) return out;
        for (String rec : raw.split(RECORD)) {
            String[] p = rec.split(FIELD, -1);
            if (p.length < 8) continue;
            try {
                Item item = new Item();
                item.id = Long.parseLong(p[0]);
                item.kind = p[1];
                item.text = p[2];
                item.sender = p[3];
                item.appLabel = p[4];
                item.reason = p[5];
                item.createdMs = Long.parseLong(p[6]);
                item.completed = "1".equals(p[7]);
                out.add(item);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public List<Item> pinnedOnly() {
        List<Item> out = new ArrayList<>();
        for (Item it : all()) if (it.isPinned()) out.add(it);
        return out;
    }

    public List<Item> flaggedOnly() {
        List<Item> out = new ArrayList<>();
        for (Item it : all()) if (it.isFlagged()) out.add(it);
        return out;
    }

    public synchronized void remove(long id) {
        List<Item> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                items.remove(i);
                break;
            }
        }
        write(items);
    }

    public synchronized void setCompleted(long id, boolean completed) {
        List<Item> items = all();
        for (Item it : items) {
            if (it.id == id) it.completed = completed;
        }
        write(items);
    }

    public synchronized void clearCompleted() {
        List<Item> items = all();
        List<Item> kept = new ArrayList<>();
        for (Item it : items) {
            if (!it.completed) kept.add(it);
        }
        write(kept);
    }

    public synchronized void clearAll() {
        prefs.edit().remove(KEY).apply();
    }

    public int totalPinned() {
        int count = 0;
        for (Item it : all()) if (it.isPinned()) count++;
        return count;
    }

    public int totalFlagged() {
        int count = 0;
        for (Item it : all()) if (it.isFlagged()) count++;
        return count;
    }

    public int totalPending() {
        int count = 0;
        for (Item it : all()) if (!it.completed) count++;
        return count;
    }

    private void write(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(RECORD);
            sb.append(it.id).append(FIELD)
              .append(clean(it.kind)).append(FIELD)
              .append(clean(it.text)).append(FIELD)
              .append(clean(it.sender)).append(FIELD)
              .append(clean(it.appLabel)).append(FIELD)
              .append(clean(it.reason)).append(FIELD)
              .append(it.createdMs).append(FIELD)
              .append(it.completed ? "1" : "0");
        }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }

    private static String clean(String v) {
        if (v == null) return "";
        return v.replace(FIELD, " ").replace(RECORD, " ").trim();
    }
}
