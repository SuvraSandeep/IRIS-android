package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProfileStore {
    private static final String FILE_NAME = "iris_profile_v2.enc";
    private static final String LEGACY_PREFS = "iris_profile_store";
    private static final String LEGACY_KEY = "profile_json";

    public static class Entry {
        public String contactName;
        public String phoneNumber;
        public final List<String> phrases = new ArrayList<>();
        public int callCount;
        public long lastCalled;
    }

    public static class WakeProfile {
        public String phrase = "";
        public double threshold = 1.05;
        public long trainedAt;
        public final List<float[][]> templates = new ArrayList<>();
        public float[] voiceprint;
        public boolean isReady() { return !phrase.trim().isEmpty() && templates.size() >= 3; }
        public boolean isVoiceEnrolled() { return voiceprint != null && voiceprint.length > 0; }
    }

    public static class Match {
        public final String contactName;
        public final String phoneNumber;
        public final String phrase;
        public final double confidence;
        Match(String contactName, String phoneNumber, String phrase, double confidence) {
            this.contactName = contactName;
            this.phoneNumber = phoneNumber;
            this.phrase = phrase;
            this.confidence = confidence;
        }
    }

    private final Context context;

    public ProfileStore(Context context) {
        this.context = context.getApplicationContext();
        migrateLegacyIfNeeded();
    }

    public synchronized List<Entry> getEntries() {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray profiles = root().optJSONArray("profiles");
            if (profiles == null) return result;
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject item = profiles.getJSONObject(i);
                Entry entry = new Entry();
                entry.contactName = item.optString("contactName", "");
                entry.phoneNumber = item.optString("phoneNumber", "");
                entry.callCount = item.optInt("callCount", 0);
                entry.lastCalled = item.optLong("lastCalled", 0);
                JSONArray phrases = item.optJSONArray("phrases");
                if (phrases != null) {
                    for (int p = 0; p < phrases.length(); p++) {
                        String phrase = phrases.optString(p, "").trim();
                        if (!phrase.isEmpty()) entry.phrases.add(phrase);
                    }
                }
                if (!entry.contactName.isEmpty() && !entry.phoneNumber.isEmpty()) result.add(entry);
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized WakeProfile getWakeProfile() {
        WakeProfile profile = new WakeProfile();
        try {
            JSONObject wake = root().optJSONObject("wakeWord");
            if (wake == null) return profile;
            profile.phrase = wake.optString("phrase", "");
            profile.threshold = Math.max(.40, Math.min(2.50, wake.optDouble("threshold", 1.05)));
            profile.trainedAt = wake.optLong("trainedAt", 0);
            JSONArray templates = wake.optJSONArray("templates");
            if (templates != null) {
                for (int t = 0; t < Math.min(5, templates.length()); t++) {
                    JSONArray frames = templates.getJSONArray(t);
                    int frameCount = Math.min(120, frames.length());
                    float[][] matrix = new float[frameCount][];
                    for (int f = 0; f < frameCount; f++) {
                        JSONArray features = frames.getJSONArray(f);
                        int featureCount = Math.min(20, features.length());
                        matrix[f] = new float[featureCount];
                        for (int c = 0; c < featureCount; c++) matrix[f][c] = (float) features.getDouble(c);
                    }
                    if (matrix.length > 0) profile.templates.add(matrix);
                }
            }
            JSONArray vp = wake.optJSONArray("voiceprint");
            if (vp != null && vp.length() > 0) {
                profile.voiceprint = new float[vp.length()];
                for (int i = 0; i < vp.length(); i++) profile.voiceprint[i] = (float) vp.getDouble(i);
            }
        } catch (Exception ignored) { }
        return profile;
    }

    public synchronized boolean setWakeProfile(String phrase, List<float[][]> templates) {
        try {
            JSONObject current = root();
            JSONObject wake = new JSONObject();
            wake.put("phrase", phrase.trim());
            wake.put("trainedAt", System.currentTimeMillis());
            wake.put("threshold", WakeWordEngine.calibratedThreshold(templates));
            JSONArray allTemplates = new JSONArray();
            for (float[][] template : templates) {
                JSONArray frames = new JSONArray();
                for (float[] frame : template) {
                    JSONArray values = new JSONArray();
                    for (float value : frame) values.put(Math.round(value * 10000f) / 10000.0);
                    frames.put(values);
                }
                allTemplates.put(frames);
            }
            wake.put("templates", allTemplates);
            current.put("wakeWord", wake);
            persist(current);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public synchronized boolean setVoiceprint(float[] voiceprint) {
        try {
            JSONObject current = root();
            JSONObject wake = current.optJSONObject("wakeWord");
            if (wake == null) wake = new JSONObject();
            if (voiceprint != null && voiceprint.length > 0) {
                JSONArray vp = new JSONArray();
                for (float v : voiceprint) vp.put(Math.round(v * 1000000f) / 1000000.0);
                wake.put("voiceprint", vp);
            } else {
                wake.remove("voiceprint");
            }
            current.put("wakeWord", wake);
            persist(current);
            return true;
        } catch (Exception ignored) { return false; }
    }

    /** The enrolled speaker voiceprint (x-vector), or null if none. */
    public synchronized float[] getVoiceprint() {
        try {
            JSONObject wake = root().optJSONObject("wakeWord");
            if (wake == null) return null;
            JSONArray vp = wake.optJSONArray("voiceprint");
            if (vp == null || vp.length() == 0) return null;
            float[] out = new float[vp.length()];
            for (int i = 0; i < out.length; i++) out[i] = (float) vp.getDouble(i);
            return out;
        } catch (Exception e) { return null; }
    }

    /** Store the most recent rejected wake embedding so feedback can learn from it. */
    public synchronized boolean setPendingVoiceSample(float[] v) {
        try {
            JSONObject r = root();
            if (v == null || v.length == 0) { r.remove("pendingVoice"); }
            else {
                JSONArray a = new JSONArray();
                for (float x : v) a.put(Math.round(x * 1000000f) / 1000000.0);
                r.put("pendingVoice", a);
            }
            persist(r);
            return true;
        } catch (Exception e) { return false; }
    }

    public synchronized float[] getPendingVoiceSample() {
        try {
            JSONArray a = root().optJSONArray("pendingVoice");
            if (a == null || a.length() == 0) return null;
            float[] o = new float[a.length()];
            for (int i = 0; i < o.length; i++) o[i] = (float) a.getDouble(i);
            return o;
        } catch (Exception e) { return null; }
    }

    /** Blend a new sample into the enrolled voiceprint (adaptive learning). */
    public synchronized boolean mergeVoiceprint(float[] sample) {
        if (sample == null || sample.length == 0) return false;
        float[] cur = getVoiceprint();
        if (cur == null) return setVoiceprint(sample);
        if (cur.length != sample.length) return false;
        float[] merged = new float[cur.length];
        for (int i = 0; i < merged.length; i++) merged[i] = cur[i] * 0.7f + sample[i] * 0.3f;
        return setVoiceprint(merged);
    }

    public synchronized java.util.Map<String, String[]> getRelationships() {
        java.util.Map<String, String[]> result = new java.util.LinkedHashMap<>();
        try {
            JSONObject rels = root().optJSONObject("relationships");
            if (rels == null) return result;
            java.util.Iterator<String> keys = rels.keys();
            while (keys.hasNext()) {
                String label = keys.next();
                JSONObject entry = rels.getJSONObject(label);
                result.put(label, new String[]{entry.optString("name", ""), entry.optString("number", "")});
            }
        } catch (Exception ignored) { }
        return result;
    }

    public synchronized void setRelationship(String label, String name, String number) {
        try {
            JSONObject current = root();
            JSONObject rels = current.optJSONObject("relationships");
            if (rels == null) rels = new JSONObject();
            JSONObject entry = new JSONObject();
            entry.put("name", name);
            entry.put("number", number);
            rels.put(normalize(label), entry);
            current.put("relationships", rels);
            persist(current);
        } catch (Exception ignored) { }
    }

    public synchronized void removeRelationship(String label) {
        try {
            JSONObject current = root();
            JSONObject rels = current.optJSONObject("relationships");
            if (rels != null) {
                rels.remove(normalize(label));
                current.put("relationships", rels);
                persist(current);
            }
        } catch (Exception ignored) { }
    }

    public synchronized String[] resolveRelationship(String label) {
        try {
            JSONObject rels = root().optJSONObject("relationships");
            if (rels == null) return null;
            String key = normalize(label);
            // Direct match
            if (rels.has(key)) {
                JSONObject entry = rels.getJSONObject(key);
                return new String[]{entry.optString("name", ""), entry.optString("number", "")};
            }
            // Try with "my" prefix removed
            if (key.startsWith("my ")) key = key.substring(3).trim();
            if (rels.has(key)) {
                JSONObject entry = rels.getJSONObject(key);
                return new String[]{entry.optString("name", ""), entry.optString("number", "")};
            }
        } catch (Exception ignored) { }
        return null;
    }

    public synchronized List<Entry> calledToday() {
        long todayStart = todayMidnight();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : getEntries()) {
            if (entry.lastCalled >= todayStart) result.add(entry);
        }
        result.sort((a, b) -> Long.compare(b.lastCalled, a.lastCalled));
        return result;
    }

    public synchronized Entry lastCalled() {
        Entry latest = null;
        for (Entry entry : getEntries()) {
            if (entry.lastCalled > 0 && (latest == null || entry.lastCalled > latest.lastCalled)) latest = entry;
        }
        return latest;
    }

    public synchronized int callCountToday(String number) {
        long todayStart = todayMidnight();
        String target = normalizeNumber(number);
        for (Entry entry : getEntries()) {
            if (normalizeNumber(entry.phoneNumber).equals(target) && entry.lastCalled >= todayStart) {
                return entry.callCount; // Approximate — callCount is total, not daily
            }
        }
        return 0;
    }

    private static long todayMidnight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public synchronized void addTraining(String name, String number, List<String> phrases) {
        List<Entry> entries = getEntries();
        Entry target = null;
        String normalizedNumber = normalizeNumber(number);
        for (Entry entry : entries) {
            if (normalizeNumber(entry.phoneNumber).equals(normalizedNumber)) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            target = new Entry();
            target.contactName = name;
            target.phoneNumber = number;
            entries.add(target);
        }
        target.contactName = name;
        for (String phrase : phrases) {
            boolean exists = false;
            for (String old : target.phrases) if (normalize(old).equals(normalize(phrase))) exists = true;
            if (!exists && !phrase.trim().isEmpty()) target.phrases.add(phrase.trim());
        }
        saveEntries(entries);
    }

    public synchronized void deleteContact(String number) {
        String target = normalizeNumber(number);
        List<Entry> entries = getEntries();
        entries.removeIf(entry -> normalizeNumber(entry.phoneNumber).equals(target));
        saveEntries(entries);
    }

    public synchronized void recordCall(String name, String number) {
        List<Entry> entries = getEntries();
        Entry target = null;
        for (Entry entry : entries) {
            if (normalizeNumber(entry.phoneNumber).equals(normalizeNumber(number))) target = entry;
        }
        if (target == null) {
            target = new Entry();
            target.contactName = name == null ? number : name;
            target.phoneNumber = number;
            entries.add(target);
        }
        target.callCount++;
        target.lastCalled = System.currentTimeMillis();
        saveEntries(entries);
    }

    public synchronized List<Entry> frequentContacts(int limit) {
        List<Entry> entries = getEntries();
        entries.sort((a, b) -> {
            int calls = Integer.compare(b.callCount, a.callCount);
            return calls != 0 ? calls : Long.compare(b.lastCalled, a.lastCalled);
        });
        return new ArrayList<>(entries.subList(0, Math.min(limit, entries.size())));
    }

    public synchronized Match findMatch(String spokenText) {
        String heard = normalize(spokenText);
        Match best = null;
        for (Entry entry : getEntries()) {
            for (String phrase : entry.phrases) {
                String learned = normalize(phrase);
                double score;
                if (heard.equals(learned)) score = 1.0;
                else if (heard.contains(learned) || learned.contains(heard)) score = .91;
                else score = similarity(heard, learned);
                if (score >= .76 && (best == null || score > best.confidence)) {
                    best = new Match(entry.contactName, entry.phoneNumber, phrase, score);
                }
            }
        }
        return best;
    }

    public synchronized String exportJson() {
        try {
            JSONObject result = root();
            result.put("exportedAt", System.currentTimeMillis());
            result.put("description", "IRIS portable wake-word and calling profile");
            return result.toString(2);
        } catch (Exception error) {
            return emptyRoot().toString();
        }
    }

    public synchronized int importAndMerge(String json) throws Exception {
        JSONObject incoming = new JSONObject(json);
        if (!"iris-profile".equals(incoming.optString("schema"))) {
            throw new IllegalArgumentException("This is not an IRIS profile.");
        }
        int version = incoming.optInt("version", 1);
        if (version < 1 || version > 2) throw new IllegalArgumentException("Unsupported IRIS profile version.");
        JSONArray profiles = incoming.optJSONArray("profiles");
        List<Entry> current = getEntries();
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : current) merged.put(normalizeNumber(entry.phoneNumber), entry);
        int imported = 0;
        if (profiles != null) {
            for (int i = 0; i < Math.min(500, profiles.length()); i++) {
                JSONObject item = profiles.getJSONObject(i);
                String name = item.optString("contactName", "").trim();
                String number = item.optString("phoneNumber", "").trim();
                if (name.isEmpty() || number.isEmpty()) continue;
                Entry target = merged.get(normalizeNumber(number));
                if (target == null) {
                    target = new Entry();
                    target.contactName = name;
                    target.phoneNumber = number;
                    merged.put(normalizeNumber(number), target);
                }
                JSONArray phrases = item.optJSONArray("phrases");
                if (phrases != null) {
                    for (int p = 0; p < Math.min(50, phrases.length()); p++) {
                        String phrase = phrases.optString(p, "").trim();
                        if (phrase.length() > 300) phrase = phrase.substring(0, 300);
                        boolean duplicate = false;
                        for (String old : target.phrases) if (normalize(old).equals(normalize(phrase))) duplicate = true;
                        if (!phrase.isEmpty() && !duplicate) target.phrases.add(phrase);
                    }
                }
                imported++;
            }
        }
        JSONObject result = root();
        result.put("profiles", entriesArray(new ArrayList<>(merged.values())));
        JSONObject wake = incoming.optJSONObject("wakeWord");
        if (wake != null && wake.optJSONArray("templates") != null) {
            // Validate wake profile before importing
            double threshold = wake.optDouble("threshold", 1.05);
            if (threshold < 0.40 || threshold > 2.50) throw new IllegalArgumentException("Invalid wake threshold in imported profile.");
            JSONArray wakeTemplates = wake.optJSONArray("templates");
            if (wakeTemplates.length() > 5) throw new IllegalArgumentException("Too many wake templates in imported profile.");
            for (int t = 0; t < wakeTemplates.length(); t++) {
                JSONArray frames = wakeTemplates.getJSONArray(t);
                if (frames.length() > 120) throw new IllegalArgumentException("Wake template too large in imported profile.");
                for (int f = 0; f < frames.length(); f++) {
                    JSONArray features = frames.getJSONArray(f);
                    if (features.length() > 20) throw new IllegalArgumentException("Wake feature vector too large in imported profile.");
                }
            }
            String phrase = wake.optString("phrase", "").trim();
            if (phrase.length() > 200) throw new IllegalArgumentException("Wake phrase too long in imported profile.");
            result.put("wakeWord", wake);
        }
        result.put("version", 2);
        persist(result);
        return imported;
    }

    public synchronized int phraseCount() {
        int count = 0;
        for (Entry entry : getEntries()) count += entry.phrases.size();
        return count;
    }

    private void saveEntries(List<Entry> entries) {
        try {
            JSONObject current = root();
            current.put("profiles", entriesArray(entries));
            persist(current);
        } catch (Exception ignored) { }
    }

    private JSONArray entriesArray(List<Entry> entries) throws Exception {
        JSONArray profiles = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            item.put("contactName", entry.contactName);
            item.put("phoneNumber", entry.phoneNumber);
            item.put("callCount", entry.callCount);
            item.put("lastCalled", entry.lastCalled);
            JSONArray phrases = new JSONArray();
            for (String phrase : entry.phrases) phrases.put(phrase);
            item.put("phrases", phrases);
            profiles.put(item);
        }
        return profiles;
    }

    private JSONObject root() {
        try {
            return new JSONObject(SecureStore.read(context, FILE_NAME, emptyRoot().toString()));
        } catch (Exception ignored) {
            return emptyRoot();
        }
    }

    private void persist(JSONObject root) throws Exception {
        root.put("schema", "iris-profile");
        root.put("version", 2);
        SecureStore.write(context, FILE_NAME, root.toString());
    }

    private JSONObject emptyRoot() {
        JSONObject root = new JSONObject();
        try {
            root.put("schema", "iris-profile");
            root.put("version", 2);
            root.put("profiles", new JSONArray());
        } catch (Exception ignored) { }
        return root;
    }

    private void migrateLegacyIfNeeded() {
        if (!SecureStore.read(context, FILE_NAME, "").isEmpty()) return;
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String json = legacy.getString(LEGACY_KEY, "");
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject old = new JSONObject(json);
            old.put("schema", "iris-profile");
            old.put("version", 2);
            SecureStore.write(context, FILE_NAME, old.toString());
            legacy.edit().remove(LEGACY_KEY).apply();
        } catch (Exception ignored) { }
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.startsWith("hey iris ")) text = text.substring(9).trim();
        if (text.startsWith("iris ")) text = text.substring(5).trim();
        return text;
    }

    private static String normalizeNumber(String value) {
        return value == null ? "" : value.replaceAll("[^0-9+]", "");
    }

    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return 1.0 - ((double) previous[b.length()] / Math.max(a.length(), b.length()));
    }
}
