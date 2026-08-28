package com.iris.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encrypted on-device memory store for personal facts, preferences, rules,
 * and corrections. Stored as JSON via SecureStore (AES-256-GCM).
 */
public final class MemoryStore {
    private static final String FILE_NAME = "iris_memory_v1.enc";
    private static final int MAX_MEMORIES = 500;
    private static final Object LOCK = new Object();

    public static final String CAT_ABOUT_ME = "about_me";
    public static final String CAT_PEOPLE = "people";
    public static final String CAT_PREFERENCE = "preference";
    public static final String CAT_RULE = "rule";
    public static final String CAT_CORRECTION = "correction";
    public static final String CAT_SCHEDULE = "schedule";

    public static class Memory {
        public String id;
        public String category;
        public String key;
        public String value;
        public String detail;
        public String source;
        public long createdAt;
        public long updatedAt;

        public Memory() {
            this.id = "m_" + UUID.randomUUID().toString().substring(0, 8);
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
            this.source = "user_input";
        }

        @Override
        public String toString() {
            if (detail != null && !detail.isEmpty()) return key + ": " + value + " (" + detail + ")";
            return key + ": " + value;
        }
    }

    private MemoryStore() { }

    public static List<Memory> getAll(Context context) {
        synchronized (LOCK) {
            return parseMemories(readRoot(context));
        }
    }

    public static List<Memory> getByCategory(Context context, String category) {
        List<Memory> result = new ArrayList<>();
        for (Memory m : getAll(context)) {
            if (category.equals(m.category)) result.add(m);
        }
        return result;
    }

    public static Memory findByKey(Context context, String category, String key) {
        String normalizedKey = key.toLowerCase().trim();
        for (Memory m : getAll(context)) {
            if (category.equals(m.category) && m.key.toLowerCase().trim().equals(normalizedKey)) return m;
        }
        return null;
    }

    public static List<Memory> search(Context context, String query) {
        String q = query.toLowerCase().trim();
        List<Memory> result = new ArrayList<>();
        for (Memory m : getAll(context)) {
            if (m.key.toLowerCase().contains(q) || m.value.toLowerCase().contains(q)
                    || (m.detail != null && m.detail.toLowerCase().contains(q))) {
                result.add(m);
            }
        }
        return result;
    }

    public static void add(Context context, Memory memory) {
        synchronized (LOCK) {
            JSONObject root = readRoot(context);
            try {
                JSONArray memories = root.optJSONArray("memories");
                if (memories == null) memories = new JSONArray();
                if (memories.length() >= MAX_MEMORIES) return;
                memories.put(memoryToJson(memory));
                root.put("memories", memories);
                root.put("updatedAt", System.currentTimeMillis());
                writeRoot(context, root);
            } catch (Exception ignored) { }
        }
    }

    public static void update(Context context, String id, String value, String detail) {
        synchronized (LOCK) {
            JSONObject root = readRoot(context);
            try {
                JSONArray memories = root.optJSONArray("memories");
                if (memories == null) return;
                for (int i = 0; i < memories.length(); i++) {
                    JSONObject item = memories.getJSONObject(i);
                    if (id.equals(item.optString("id"))) {
                        item.put("value", value);
                        if (detail != null) item.put("detail", detail);
                        item.put("updatedAt", System.currentTimeMillis());
                        break;
                    }
                }
                root.put("memories", memories);
                root.put("updatedAt", System.currentTimeMillis());
                writeRoot(context, root);
            } catch (Exception ignored) { }
        }
    }

    public static void delete(Context context, String id) {
        synchronized (LOCK) {
            JSONObject root = readRoot(context);
            try {
                JSONArray memories = root.optJSONArray("memories");
                if (memories == null) return;
                JSONArray filtered = new JSONArray();
                for (int i = 0; i < memories.length(); i++) {
                    JSONObject item = memories.getJSONObject(i);
                    if (!id.equals(item.optString("id"))) filtered.put(item);
                }
                root.put("memories", filtered);
                root.put("updatedAt", System.currentTimeMillis());
                writeRoot(context, root);
            } catch (Exception ignored) { }
        }
    }

    public static int count(Context context) {
        return getAll(context).size();
    }

    public static String exportJson(Context context) {
        synchronized (LOCK) {
            try {
                JSONObject root = readRoot(context);
                root.put("exportedAt", System.currentTimeMillis());
                root.put("description", "IRIS personal memory export");
                return root.toString(2);
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    public static int importAndMerge(Context context, String json) throws Exception {
        JSONObject incoming = new JSONObject(json);
        if (!"iris-memory".equals(incoming.optString("schema"))) {
            throw new IllegalArgumentException("Not an IRIS memory file.");
        }
        JSONArray incomingMemories = incoming.optJSONArray("memories");
        if (incomingMemories == null) return 0;

        synchronized (LOCK) {
            JSONObject root = readRoot(context);
            JSONArray existing = root.optJSONArray("memories");
            if (existing == null) existing = new JSONArray();

            java.util.Set<String> existingIds = new java.util.HashSet<>();
            for (int i = 0; i < existing.length(); i++) {
                existingIds.add(existing.getJSONObject(i).optString("id"));
            }

            int imported = 0;
            for (int i = 0; i < Math.min(MAX_MEMORIES, incomingMemories.length()); i++) {
                JSONObject item = incomingMemories.getJSONObject(i);
                String id = item.optString("id");
                if (!existingIds.contains(id)) {
                    existing.put(item);
                    imported++;
                }
            }

            root.put("memories", existing);
            root.put("updatedAt", System.currentTimeMillis());
            writeRoot(context, root);
            return imported;
        }
    }

    /** Check if a preference/rule memory matches a query. */
    public static String findPreference(Context context, String keyword) {
        String k = keyword.toLowerCase().trim();
        for (Memory m : getAll(context)) {
            if ((CAT_PREFERENCE.equals(m.category) || CAT_RULE.equals(m.category))
                    && (m.key.toLowerCase().contains(k) || m.value.toLowerCase().contains(k))) {
                return m.value;
            }
        }
        return null;
    }

    /** Find a correction for a spoken phrase. */
    public static Memory findCorrection(Context context, String spokenPhrase) {
        String normalized = spokenPhrase.toLowerCase().trim();
        for (Memory m : getAll(context)) {
            if (CAT_CORRECTION.equals(m.category) && m.key.toLowerCase().trim().equals(normalized)) {
                return m;
            }
        }
        return null;
    }

    /** Get the owner's name from memory (if stored). */
    public static String ownerName(Context context) {
        Memory name = findByKey(context, CAT_ABOUT_ME, "name");
        return name != null ? name.value : null;
    }

    // --- Internal ---

    private static JSONObject readRoot(Context context) {
        try {
            String data = SecureStore.read(context, FILE_NAME, "");
            if (!data.isEmpty()) return new JSONObject(data);
        } catch (Exception ignored) { }
        return emptyRoot();
    }

    private static void writeRoot(Context context, JSONObject root) {
        try {
            root.put("schema", "iris-memory");
            root.put("version", 1);
            SecureStore.write(context, FILE_NAME, root.toString());
        } catch (Exception ignored) { }
    }

    private static JSONObject emptyRoot() {
        JSONObject root = new JSONObject();
        try {
            root.put("schema", "iris-memory");
            root.put("version", 1);
            root.put("memories", new JSONArray());
            root.put("createdAt", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return root;
    }

    private static List<Memory> parseMemories(JSONObject root) {
        List<Memory> result = new ArrayList<>();
        try {
            JSONArray arr = root.optJSONArray("memories");
            if (arr == null) return result;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                Memory m = new Memory();
                m.id = item.optString("id", m.id);
                m.category = item.optString("category", CAT_ABOUT_ME);
                m.key = item.optString("key", "");
                m.value = item.optString("value", "");
                m.detail = item.optString("detail", null);
                m.source = item.optString("source", "user_input");
                m.createdAt = item.optLong("createdAt", 0);
                m.updatedAt = item.optLong("updatedAt", m.createdAt);
                if (!m.key.isEmpty()) result.add(m);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static JSONObject memoryToJson(Memory m) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", m.id);
        item.put("category", m.category);
        item.put("key", m.key);
        item.put("value", m.value);
        if (m.detail != null && !m.detail.isEmpty()) item.put("detail", m.detail);
        item.put("source", m.source);
        item.put("createdAt", m.createdAt);
        item.put("updatedAt", m.updatedAt);
        return item;
    }
}
