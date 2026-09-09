package com.iris.assistant;

import java.util.List;
import java.util.Locale;

/**
 * Phase 5 — the local LLM planner layer (AGENT-ARCHITECTURE.md §5).
 *
 * Responsibilities, deliberately narrow:
 *   1. Build a strict system prompt containing ONLY the allowed tools and safety rules.
 *   2. Ask a local model for a single JSON plan.
 *   3. Validate it via {@link Plan#fromJson} — unknown intents/tools, malformed steps and
 *      low-confidence output are all rejected.
 *   4. Return {@link Plan#unknown()} on anything doubtful so the caller falls back to the
 *      deterministic {@link IntentParser} / keyword router.
 *
 * The engine is injected, so today it runs on the already-integrated {@link LlmAgent}
 * (MediaPipe, opt-in) and a future {@code llama.cpp} build can be dropped in without touching
 * this class or the executor.
 *
 * <b>The model only ever proposes. It receives no permissions, no shell, no code execution, and
 * the plan it returns is inert data until IRIS's own code decides to run it.</b>
 */
public final class LocalPlanner {

    /** Anything that can turn a prompt into text. Keeps this class engine-agnostic and testable. */
    public interface Engine {
        boolean ready();
        String generate(String prompt);
    }

    /** Hard ceiling on how long we let a local model think before giving up on it. */
    public static final long BUDGET_MS = 6000;

    private final Engine engine;

    public LocalPlanner(Engine engine) { this.engine = engine; }

    public boolean available() { return engine != null && engine.ready(); }

    /**
     * Ask the model for a plan. Never throws, never blocks past the budget's intent, and returns
     * {@link Plan#unknown()} whenever the result is not trustworthy.
     */
    public Plan plan(String transcript, String contextSummary) {
        if (!available() || transcript == null || transcript.trim().isEmpty()) return Plan.unknown();
        try {
            String raw = engine.generate(buildPrompt(transcript, contextSummary));
            if (raw == null || raw.trim().isEmpty()) return Plan.unknown();
            Plan plan = Plan.fromJson(raw);
            // Second gate: even a well-formed plan must clear our own bar.
            if (plan.isUnknown()) return Plan.unknown();
            if (plan.confidence() < Plan.MEDIUM) return Plan.unknown();
            if (!plan.allStepsKnown() && plan.isComplete()) return Plan.unknown();
            return plan;
        } catch (Throwable t) {
            return Plan.unknown();
        }
    }

    /** The strict prompt. Tool list is generated from the whitelist so the two can never drift. */
    static String buildPrompt(String transcript, String contextSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("You convert a phone user's request into ONE JSON plan. Reply with JSON only.\n\n");
        sb.append("ALLOWED TOOLS (use no others):\n");
        for (String tool : ToolCall.KNOWN) sb.append("- ").append(tool).append(argHint(tool)).append('\n');
        sb.append("\nALLOWED INTENTS (use no others):\n");
        for (IrisIntent i : IrisIntent.values()) {
            if (i != IrisIntent.UNKNOWN) sb.append("- ").append(i.name()).append('\n');
        }
        sb.append("\nRULES:\n")
          .append("1. Output exactly one JSON object. No prose, no markdown.\n")
          .append("2. Never invent a tool, an intent, a contact, or a result.\n")
          .append("3. If a required detail is absent, leave it out and list it in \"missing\".\n")
          .append("4. Calls and messages are sensitive: set \"needs_confirmation\": true.\n")
          .append("5. Copy message text exactly as the user said it. Never rewrite it.\n")
          .append("6. If you are unsure what is wanted, use confidence below 0.4.\n")
          .append("7. App actions support whatsapp, telegram, spotify, youtube, youtube music, maps, chrome, gmail only.\n")
          .append("8. APP_SEARCH uses search_app. APP_SHARE uses share_text_to_app or share_recent_media and needs_confirmation true.\n")
          .append("9. App shares only open a review screen. Never claim delivery or invent a recipient. Use exactly one app step.\n\n");
        sb.append("SCHEMA:\n")
          .append("{\"intent\":\"<INTENT>\",\"goal\":\"<short>\",\"confidence\":<0..1>,")
          .append("\"needs_confirmation\":<bool>,\"entities\":{},\"missing\":[],")
          .append("\"steps\":[{\"tool\":\"<tool>\",\"arguments\":{}}]}\n\n");
        sb.append("EXAMPLE\nUser: set an alarm for 7 am\n")
          .append("{\"intent\":\"SET_ALARM\",\"goal\":\"Set an alarm\",\"confidence\":0.95,")
          .append("\"needs_confirmation\":false,\"entities\":{\"time\":\"7 am\"},\"missing\":[],")
          .append("\"steps\":[{\"tool\":\"create_alarm\",\"arguments\":{\"time\":\"7 am\"}}]}\n\n");
        sb.append("EXAMPLE\nUser: set an alarm\n")
          .append("{\"intent\":\"SET_ALARM\",\"goal\":\"Set an alarm\",\"confidence\":0.9,")
          .append("\"needs_confirmation\":false,\"entities\":{},\"missing\":[\"time\"],\"steps\":[]}\n\n");
        if (contextSummary != null && !contextSummary.trim().isEmpty()) {
            sb.append("RECENT CONTEXT (for references like \"that\" or \"the last one\"):\n")
              .append(contextSummary.trim()).append("\n\n");
        }
        sb.append("User: ").append(transcript.trim()).append('\n');
        return sb.toString();
    }

    /** Short argument hints so a small model has a chance of filling them correctly. */
    private static String argHint(String tool) {
        switch (tool) {
            case "call_contact":        return "(name)";
            case "compose_message":     return "(name, text)";
            case "create_alarm":        return "(time)";
            case "create_timer":        return "(duration)";
            case "create_reminder":     return "(task, time)";
            case "record_screen":       return "(duration)";
            case "record_camera_video": return "(camera: front|back, duration)";
            case "record_voice":        return "(duration, mic)";
            case "set_torch":           return "(state: on|off)";
            case "set_volume":          return "(level: 0-100)";
            case "open_app":            return "(name)";
            case "search_web":          return "(query)";
            case "search_app":          return "(app, value: exact search query)";
            case "share_text_to_app":   return "(app, value: exact dictated text)";
            case "share_recent_media":  return "(app, value: photo|image|picture|video|audio|voice memo)";
            default:                    return "()";
        }
    }

    /** Compact recent-action context for the prompt. Caller supplies plain lines (§5: last 3–5). */
    static String contextFrom(List<String> recentLines) {
        if (recentLines == null || recentLines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : recentLines) {
            if (line == null || line.trim().isEmpty()) continue;
            sb.append("- ").append(line.trim()).append('\n');
            if (++n >= 5) break;
        }
        return sb.toString();
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
