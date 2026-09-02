package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads the owner's personal profile from iris-me.json so IRIS knows the user
 * even when the AI brain is off.
 *
 * Load priority:
 *   1. An editable override at getExternalFilesDir()/iris-me.json
 *      (visible via a file manager under Android/data/&lt;pkg&gt;/files/, no permission needed).
 *   2. The bundled asset iris-me.json (copied from the repo-root file at build time).
 *
 * On load it seeds MemoryStore (identity, preferences) and ProfileStore
 * relationships (family, relationships, special_contacts) so voice commands
 * like "call my mother" and "what do you know about me" work with no AI.
 */
public final class PersonalProfile {

    private static final String ASSET = "iris-me.json";
    private static volatile JSONObject cached;

    private PersonalProfile() { }

    /** Load the profile (cached). Returns null if none present/parseable. */
    public static JSONObject get(Context context) {
        if (cached != null) return cached;
        synchronized (PersonalProfile.class) {
            if (cached != null) return cached;
            String raw = readOverride(context);
            if (raw == null) raw = readAsset(context);
            if (raw == null) return null;
            try {
                cached = new JSONObject(raw);
            } catch (Exception e) {
                android.util.Log.w("IRIS", "iris-me.json invalid JSON: " + e.getMessage());
                cached = null;
            }
            return cached;
        }
    }

    private static String readOverride(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, ASSET);
            if (!f.exists()) return null;
            return readStream(new FileInputStream(f));
        } catch (Exception e) { return null; }
    }

    private static String readAsset(Context context) {
        try {
            return readStream(context.getAssets().open(ASSET));
        } catch (Exception e) { return null; }
    }

    private static String readStream(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // ---------- Convenience getters ----------

    public static String preferredName(Context context) {
        JSONObject p = get(context);
        if (p == null) return null;
        JSONObject id = p.optJSONObject("identity");
        if (id != null) {
            String pn = id.optString("preferred_name", "");
            if (!pn.isEmpty()) return pn;
            String n = id.optString("name", "");
            if (!n.isEmpty()) return n;
        }
        return null;
    }

    public static String wakePhrase(Context context) {
        JSONObject p = get(context);
        JSONObject w = p == null ? null : p.optJSONObject("wake");
        String phrase = w == null ? "" : w.optString("phrase", "");
        return phrase.isEmpty() ? null : phrase;
    }

    public static String picovoiceKey(Context context) {
        JSONObject p = get(context);
        JSONObject w = p == null ? null : p.optJSONObject("wake");
        return w == null ? "" : w.optString("picovoice_access_key", "");
    }

    public static double verificationSensitivity(Context context, double def) {
        JSONObject p = get(context);
        JSONObject w = p == null ? null : p.optJSONObject("wake");
        return w == null ? def : w.optDouble("verification_sensitivity", def);
    }

    public static boolean notRecognizedCueEnabled(Context context) {
        JSONObject p = get(context);
        JSONObject w = p == null ? null : p.optJSONObject("wake");
        return w == null || w.optBoolean("not_recognized_cue_enabled", true);
    }

    public static String tone(Context context) {
        JSONObject p = get(context);
        JSONObject per = p == null ? null : p.optJSONObject("personality");
        String t = per == null ? "" : per.optString("tone", "");
        return t.isEmpty() ? null : t;
    }

    /** Seed rule-based memory + relationships so IRIS knows the owner without the AI. */
    public static void seedInto(Context context) {
        JSONObject p = get(context);
        if (p == null) return;
        // Only re-seed when the profile content changes.
        SharedPreferences prefs = context.getSharedPreferences("iris_profile_seed", Context.MODE_PRIVATE);
        int hash = (p.toString() + "|seedv2").hashCode();
        if (prefs.getInt("seed_hash", 0) == hash) return;

        try {
            JSONObject id = p.optJSONObject("identity");
            if (id != null) {
                String real = id.optString("name", "");
                String preferred = id.optString("preferred_name", "");
                // Address the user by their PREFERRED name everywhere.
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "name",
                        preferred.isEmpty() ? real : preferred);
                if (!real.isEmpty()) putMemory(context, MemoryStore.CAT_ABOUT_ME, "full name", real);
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "phone", id.optString("phone", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "email", id.optString("email", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "office email", id.optString("office_email", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "blood group", id.optString("blood_group", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "birthday", id.optString("date_of_birth", ""));
            }
            JSONObject work = p.optJSONObject("work");
            if (work != null) {
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "company", work.optString("company", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "role", work.optString("role", ""));
            }
            JSONObject prefsObj = p.optJSONObject("preferences");
            if (prefsObj != null) {
                putMemory(context, MemoryStore.CAT_PREFERENCE, "drink", prefsObj.optString("tea_or_coffee", ""));
                putMemory(context, MemoryStore.CAT_PREFERENCE, "navigation app", prefsObj.optString("navigation_app", ""));
                putMemory(context, MemoryStore.CAT_PREFERENCE, "music app", prefsObj.optString("music_app", ""));
            }
            JSONObject places = p.optJSONObject("places");
            if (places != null) {
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "home", places.optString("home_address", ""));
                putMemory(context, MemoryStore.CAT_ABOUT_ME, "work address", places.optString("work_address", ""));
            }

            // Relationships → ProfileStore so "call my mother" resolves.
            ProfileStore store = new ProfileStore(context);
            JSONObject fam = p.optJSONObject("family");
            if (fam != null) {
                seedRel(store, "mother", fam.optJSONObject("mother"));
                seedRel(store, "father", fam.optJSONObject("father"));
                seedRel(store, "spouse", fam.optJSONObject("spouse"));
                JSONArray sibs = fam.optJSONArray("siblings");
                if (sibs != null) {
                    for (int i = 0; i < sibs.length(); i++) {
                        JSONObject s = sibs.optJSONObject(i);
                        if (s != null) {
                            String rel = s.optString("relation", "");
                            if (!rel.isEmpty()) seedRel(store, rel, s);
                        }
                    }
                }
            }
            seedRelArray(store, p.optJSONArray("relationships"), "label");
            seedRelArray(store, p.optJSONArray("special_contacts"), "label");

            prefs.edit().putInt("seed_hash", hash).apply();
            LogStore.append(context, "PROFILE", "Personal profile loaded and memory seeded");
        } catch (Exception e) {
            android.util.Log.w("IRIS", "Profile seed failed: " + e.getMessage());
        }
    }

    private static void seedRelArray(ProfileStore store, JSONArray arr, String labelKey) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String label = o.optString(labelKey, "");
            if (!label.isEmpty()) seedRel(store, label, o);
        }
    }

    private static void seedRel(ProfileStore store, String label, JSONObject o) {
        if (o == null) return;
        String name = o.optString("name", "");
        String number = o.optString("phone", "");
        if (!name.isEmpty() || !number.isEmpty()) store.setRelationship(label, name, number);
    }

    private static void putMemory(Context context, String category, String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        MemoryStore.Memory m = new MemoryStore.Memory();
        m.category = category;
        m.key = key;
        m.value = value.trim();
        m.source = "profile";
        MemoryStore.add(context, m);
    }

    /** Compact context block for the AI prompt (persona rules + key facts). */
    public static String contextForAI(Context context) {
        JSONObject p = get(context);
        if (p == null) return "";
        StringBuilder sb = new StringBuilder("Owner profile:\n");
        String pn = preferredName(context);
        if (pn != null) sb.append("- Address the user as: ").append(pn).append("\n");
        appendText(sb, "About", p.optString("about_me", ""));
        JSONObject kp = p.optJSONObject("known_preferences_and_rules");
        if (kp != null) {
            appendText(sb, "Tone", kp.optString("tone", ""));
            appendText(sb, "Recommendations", kp.optString("recommendations", ""));
            appendText(sb, "Uncertainty rule", kp.optString("uncertainty", ""));
        }
        JSONObject ip = p.optJSONObject("identity_and_personality");
        if (ip != null) appendText(sb, "Important", ip.optString("important_instruction", ""));
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, String label, String value) {
        if (value != null && !value.trim().isEmpty()) sb.append("- ").append(label).append(": ").append(value.trim()).append("\n");
    }
}
