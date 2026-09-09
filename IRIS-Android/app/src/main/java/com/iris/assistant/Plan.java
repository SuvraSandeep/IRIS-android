package com.iris.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 2 — a structured, validated plan (AGENT-ARCHITECTURE.md §2–§3).
 *
 * A plan is what "understanding" produces: an intent, the entities extracted from the sentence,
 * the tool steps to run, a confidence, and any fields still missing. It is inert data — the
 * executor decides whether to run it, ask a clarifying question, or confirm first.
 *
 * The core rule this class exists to enforce: <b>the AI may propose, IRIS code must decide.</b>
 * {@link #fromJson} is deliberately strict so a future local LLM cannot smuggle in an unknown
 * tool, a malformed step, or a silent sensitive action.
 */
public final class Plan {

    /** Confidence bands used to decide execute / read-back / ask (§4). */
    public static final float HIGH = 0.75f;
    public static final float MEDIUM = 0.45f;

    private final IrisIntent intent;
    private final String goal;
    private final Map<String, String> entities;
    private final List<ToolCall> steps;
    private final List<String> missing;
    private final float confidence;
    private final boolean needsConfirmation;

    private Plan(IrisIntent intent, String goal, Map<String, String> entities, List<ToolCall> steps,
                 List<String> missing, float confidence, boolean needsConfirmation) {
        this.intent = intent == null ? IrisIntent.UNKNOWN : intent;
        this.goal = goal == null ? "" : goal;
        this.entities = entities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entities);
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        this.missing = missing == null ? new ArrayList<>() : new ArrayList<>(missing);
        this.confidence = Float.isFinite(confidence) ? Math.max(0f, Math.min(1f, confidence)) : 0f;
        this.needsConfirmation = needsConfirmation || this.intent.isSensitive();
    }

    public static Plan unknown() {
        return new Plan(IrisIntent.UNKNOWN, "", null, null, null, 0f, false);
    }

    public static Builder of(IrisIntent intent) { return new Builder(intent); }

    // ─────────────────────────── accessors ───────────────────────────

    public IrisIntent intent() { return intent; }
    public String goal() { return goal; }
    public Map<String, String> entities() { return Collections.unmodifiableMap(entities); }
    public List<ToolCall> steps() { return Collections.unmodifiableList(steps); }
    public List<String> missing() { return Collections.unmodifiableList(missing); }
    public float confidence() { return confidence; }
    public boolean needsConfirmation() { return needsConfirmation; }

    public String entity(String key) {
        String v = entities.get(key);
        return v == null ? "" : v;
    }

    public boolean isUnknown() { return intent == IrisIntent.UNKNOWN; }
    public boolean isComplete() { return missing.isEmpty(); }
    public boolean isConfident() { return confidence >= HIGH; }

    /** Safe to run without asking: understood, complete, confident and not sensitive. */
    public boolean isExecutable() {
        return !isUnknown() && isComplete() && isConfident() && !needsConfirmation && allStepsKnown();
    }

    /** Every step must name a tool IRIS actually implements. */
    public boolean allStepsKnown() {
        if (steps.isEmpty()) return false;
        for (ToolCall c : steps) if (!c.isKnown()) return false;
        return true;
    }

    /** The single field to ask about first, or "" when nothing is missing. */
    public String firstMissing() { return missing.isEmpty() ? "" : missing.get(0); }

    @Override public String toString() {
        return "Plan{" + intent + " conf=" + confidence + " steps=" + steps
                + (missing.isEmpty() ? "" : " missing=" + missing) + '}';
    }

    // ─────────────────────────── builder ───────────────────────────

    public static final class Builder {
        private final IrisIntent intent;
        private String goal = "";
        private final Map<String, String> entities = new LinkedHashMap<>();
        private final List<ToolCall> steps = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private float confidence = 0.9f;
        private boolean needsConfirmation;

        Builder(IrisIntent intent) { this.intent = intent; }

        public Builder goal(String g) { this.goal = g; return this; }

        public Builder entity(String key, String value) {
            if (key != null && value != null && !value.trim().isEmpty()) entities.put(key, value.trim());
            return this;
        }

        public Builder step(ToolCall call) { if (call != null) steps.add(call); return this; }

        public Builder missing(String field) {
            if (field != null && !field.trim().isEmpty() && !missing.contains(field)) missing.add(field);
            return this;
        }

        public Builder confidence(float c) { this.confidence = c; return this; }
        public Builder needsConfirmation(boolean b) { this.needsConfirmation = b; return this; }

        public Plan build() {
            return new Plan(intent, goal, entities, steps, missing, confidence, needsConfirmation);
        }
    }

    // ─────────────────────────── strict JSON ───────────────────────────

    /**
     * Parse a planner's JSON, rejecting anything malformed. Returns {@link #unknown()} on any
     * problem — never a partially-trusted plan. Uses a tiny dependency-free reader so this class
     * stays Android- and library-free (and therefore unit-testable).
     *
     * Expected shape:
     * <pre>
     * { "intent":"SET_ALARM", "goal":"...", "confidence":0.9,
     *   "needs_confirmation":false,
     *   "entities":{"time":"7 am"},
     *   "missing":["time"],
     *   "steps":[{"tool":"create_alarm","arguments":{"time":"7 am"}}] }
     * </pre>
     */
    public static Plan fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return unknown();
        try {
            String body = json.trim();
            // Tolerate a model wrapping JSON in prose or code fences.
            int start = body.indexOf('{'), end = body.lastIndexOf('}');
            if (start < 0 || end <= start) return unknown();
            body = body.substring(start, end + 1);

            IrisIntent intent = IrisIntent.from(Json.string(body, "intent"));
            if (intent == IrisIntent.UNKNOWN) return unknown();

            Builder b = of(intent)
                    .goal(Json.string(body, "goal"))
                    .confidence(Json.number(body, "confidence", 0f))
                    .needsConfirmation(Json.bool(body, "needs_confirmation"));

            for (Map.Entry<String, String> e : Json.object(body, "entities").entrySet()) {
                b.entity(e.getKey(), e.getValue());
            }
            for (String m : Json.array(body, "missing")) b.missing(m);

            List<String> stepBlobs = Json.objectsInArray(body, "steps");
            for (String s : stepBlobs) {
                String tool = Json.string(s, "tool");
                if (tool.trim().isEmpty()) return unknown();                  // malformed step
                ToolCall call = new ToolCall(tool, Json.object(s, "arguments"));
                if (!call.isKnown()) return unknown();                        // unknown capability
                b.step(call);
            }
            Plan plan = b.build();
            // A plan with no steps and nothing missing is meaningless.
            if (plan.steps().isEmpty() && plan.isComplete()) return unknown();
            return plan;
        } catch (Throwable t) {
            return unknown();
        }
    }

    /** Minimal, forgiving JSON field reader — enough for a strict, flat plan schema. */
    static final class Json {
        private Json() { }

        static String string(String src, String key) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(src);
            return m.find() ? unescape(m.group(1)) : "";
        }

        static float number(String src, String key, float fallback) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                    .matcher(src);
            if (!m.find()) return fallback;
            try { return Float.parseFloat(m.group(1)); } catch (Exception e) { return fallback; }
        }

        static boolean bool(String src, String key) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                    .matcher(src);
            return m.find() && "true".equals(m.group(1));
        }

        /** Flat string->string object. */
        static Map<String, String> object(String src, String key) {
            Map<String, String> out = new LinkedHashMap<>();
            String blob = blockAfter(src, key, '{', '}');
            if (blob == null) return out;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?\\d+(?:\\.\\d+)?|true|false))")
                    .matcher(blob);
            while (m.find()) {
                String v = m.group(2) != null ? unescape(m.group(2)) : m.group(3);
                out.put(unescape(m.group(1)), v == null ? "" : v);
            }
            return out;
        }

        /** Array of plain strings. */
        static List<String> array(String src, String key) {
            List<String> out = new ArrayList<>();
            String blob = blockAfter(src, key, '[', ']');
            if (blob == null) return out;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(blob);
            while (m.find()) out.add(unescape(m.group(1)));
            return out;
        }

        /** Array of objects, returned as raw JSON blobs. */
        static List<String> objectsInArray(String src, String key) {
            List<String> out = new ArrayList<>();
            String blob = blockAfter(src, key, '[', ']');
            if (blob == null) return out;
            int depth = 0, from = -1;
            for (int i = 0; i < blob.length(); i++) {
                char c = blob.charAt(i);
                if (c == '{') { if (depth++ == 0) from = i; }
                else if (c == '}') { if (--depth == 0 && from >= 0) { out.add(blob.substring(from, i + 1)); from = -1; } }
            }
            return out;
        }

        /** The balanced {...} or [...] block that follows "key": */
        private static String blockAfter(String src, String key, char open, char close) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\\" + open)
                    .matcher(src);
            if (!m.find()) return null;
            int start = m.end() - 1, depth = 0;
            boolean inString = false, escaped = false;
            for (int i = start; i < src.length(); i++) {
                char c = src.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') { inString = !inString; continue; }
                if (inString) continue;
                if (c == open) depth++;
                else if (c == close && --depth == 0) return src.substring(start, i + 1);
            }
            return null;
        }

        private static String unescape(String s) {
            if (s == null) return "";
            return s.replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\t", "\t").replace("\\/", "/");
        }
    }

    /** Lower-cased helper used by callers building goals. */
    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
