package com.iris.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The owner's SHARE profile: a dedicated file, separate from iris-me.json, holding only the facts
 * he is willing for the auto-responder to tell people when they ask about him.
 *
 * Kept apart from iris-me.json on purpose \u2014 iris-me.json is the owner's full private profile
 * (identity, wake, relationships) and must never be a source the responder can disclose from. This
 * file is the opt-in surface: if it is absent, nothing is shared. {@link ProfileShare} further
 * restricts which keys (role / company / city) are ever actually spoken.
 *
 * Loaded like iris-me.json: an editable on-device override at getExternalFilesDir()/iris-share.json
 * wins, otherwise the bundled asset (copied from the repo-root file at build time).
 */
public final class ShareProfile {

    private static final String FILE = "iris-share.json";
    private static volatile Map<String, String> cached;

    private ShareProfile() { }

    /**
     * The shareable facts as a canonical map, keyed only by the safe concepts the responder can
     * answer: role, company, city, skills, education. Everything else in the file \u2014 name, email,
     * blood group, phone, anything \u2014 is IGNORED and can never be disclosed. Accepts both a flat
     * {role,company,city} file and the richer nested profile.
     */
    public static Map<String, String> fields(Context context) {
        if (cached != null) return cached;
        synchronized (ShareProfile.class) {
            if (cached != null) return cached;
            Map<String, String> out = new HashMap<>();
            String raw = readOverride(context);
            if (raw == null) raw = readAsset(context);
            if (raw != null) {
                try {
                    JSONObject o = new JSONObject(raw);
                    JSONObject share = o.optJSONObject("share");
                    JSONObject root = share != null ? share : o;
                    JSONObject corp = root.optJSONObject("corporate_persona");
                    JSONObject basics = root.optJSONObject("the_basics");
                    JSONObject nerd = root.optJSONObject("nerd_credentials");

                    put(out, "company", firstClean(
                            str(root, "company"), str(root, "employer"), str(corp, "employer")));
                    put(out, "role", firstClean(
                            str(root, "role"), str(root, "title"), str(corp, "title")));
                    put(out, "city", firstClean(
                            str(root, "city"), str(root, "current_base"), str(basics, "current_base")));
                    put(out, "skills", firstClean(
                            str(root, "skills"), joinArray(root.optJSONArray("skills")),
                            joinArray(root.optJSONArray("tech_stack")),
                            joinArray(nerd == null ? null : nerd.optJSONArray("tech_stack"))));
                    put(out, "education", firstClean(str(root, "education"), education(root)));
                    // Opt-in extras the owner chose to make shareable.
                    put(out, "email", firstClean(str(root, "email"), str(basics, "email")));
                    put(out, "blood", firstClean(
                            str(root, "blood_group"), str(basics, "blood_group")));
                    put(out, "languages", firstClean(
                            joinArray(root.optJSONArray("languages")),
                            joinArray(basics == null ? null
                                    : basics.optJSONArray("languages_supported"))));
                    put(out, "certs", firstClean(
                            joinArray(root.optJSONArray("certifications")),
                            joinArray(corp == null ? null
                                    : corp.optJSONArray("alphabet_soup_credentials"))));
                    put(out, "about", firstClean(
                            str(root, "about"), str(corp, "what_i_actually_do")));
                    put(out, "past", firstClean(str(root, "past"), str(corp, "past_life")));
                    put(out, "plan", firstClean(str(root, "plan"), str(corp, "five_year_plan")));
                    put(out, "project", firstClean(
                            str(root, "project"), str(nerd, "peak_lazy_engineering")));
                    put(out, "hobby", firstClean(
                            str(root, "hobby"), str(nerd, "random_rabbit_holes")));
                    put(out, "name", firstClean(
                            str(root, "name"), str(basics, "goes_by"), str(basics, "full_name")));
                } catch (Exception e) {
                    android.util.Log.w("IRIS", "iris-share.json invalid JSON: " + e.getMessage());
                }
            }
            cached = out;
            return cached;
        }
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null && !value.isEmpty()) m.put(key, value);
    }

    private static String str(JSONObject o, String key) {
        return o == null ? "" : o.optString(key, "");
    }

    /** First non-placeholder, non-empty value, cleaned of jokey parentheticals and length-capped. */
    private static String firstClean(String... values) {
        for (String v : values) {
            String c = clean(v);
            if (!c.isEmpty()) return c;
        }
        return "";
    }

    private static String clean(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.isEmpty() || t.toUpperCase(Locale.ROOT).contains("UNKNOWN")) return "";
        // Drop parenthetical asides so a shared fact reads cleanly, then cap the length.
        t = t.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        return t.length() > 160 ? t.substring(0, 157) + "\u2026" : t;
    }

    /** Join a few array items (skills), parentheticals stripped. */
    private static String joinArray(org.json.JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (int i = 0; i < arr.length() && added < 4; i++) {
            String item = clean(arr.optString(i, ""));
            if (item.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
            added++;
        }
        return sb.toString();
    }

    /** First academic entry as "<achievement> from <institute>", or empty. */
    private static String education(JSONObject root) {
        org.json.JSONArray arr = root.optJSONArray("academic_survivor_stats");
        if (arr == null) arr = root.optJSONArray("education_history");
        if (arr == null || arr.length() == 0) return "";
        JSONObject first = arr.optJSONObject(0);
        if (first == null) return "";
        String ach = clean(first.optString("achievement",
                first.optString("degree", "")));
        String where = clean(first.optString("spawn_point",
                first.optString("institute", "")));
        if (ach.isEmpty()) return "";
        return where.isEmpty() ? ach : ach + " from " + where;
    }

    /** Forget the cache so an edited file is re-read (e.g. after the owner updates the override). */
    public static void invalidate() {
        cached = null;
    }

    private static String readOverride(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(dir, FILE);
            if (!f.exists()) return null;
            return readStream(new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAsset(Context context) {
        try {
            return readStream(context.getAssets().open(FILE));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(InputStream in) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
