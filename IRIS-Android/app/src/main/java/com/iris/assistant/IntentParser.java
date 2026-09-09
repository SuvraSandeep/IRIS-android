package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 — deterministic structured understanding (AGENT-ARCHITECTURE.md §3, §11).
 *
 * Turns a transcript into a {@link Plan}: intent + entities + tool steps + confidence + the fields
 * that are still missing. Pure Java (no Android) so it is unit-testable.
 *
 * This is the rule-based planner. A local LLM may later produce the same {@link Plan} shape via
 * {@link Plan#fromJson}, and this parser stays as the deterministic fallback for the critical
 * intents — alarms, calls, camera and screenshots — exactly as the roadmap requires.
 *
 * It deliberately does NOT try to understand everything. When it is not sure, it returns
 * {@link Plan#unknown()} so the existing keyword router keeps handling the request.
 */
public final class IntentParser {
    private IntentParser() { }

    // ── intent triggers ────────────────────────────────────────────────
    private static final Pattern ALARM = Pattern.compile(
            "^(?:set\\s+(?:an?\\s+)?alarm|wake\\s+me(?:\\s+up)?|alarm)\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMER = Pattern.compile(
            "^(?:set\\s+(?:a\\s+)?timer|timer)\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile(
            "^(?:call|dial|ring|phone)\\b(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCREENSHOT = Pattern.compile(
            "^(?:(?:take|grab|capture|get|click)\\s+(?:a\\s+|the\\s+|one\\s+|my\\s+)?screen\\s?shot"
            + "|screen\\s?shot)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCREEN_REC = Pattern.compile(
            "^(?:(?:record|start)\\s+(?:the\\s+|my\\s+)?screen(?:\\s+recording)?|screen\\s+record(?:ing)?)\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO = Pattern.compile(
            "^(?:record|start|take|capture)\\s+(?:a\\s+)?(?:(front|selfie|back|rear)\\s+)?(?:camera\\s+)?video\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOICE = Pattern.compile(
            "^(?:record|start|take|capture)?\\s*(?:a\\s+)?(?:voice|audio)(?:\\s+memo)?(?:\\s+recording)?\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TORCH = Pattern.compile(
            "^torch\\s+(on|off)$", Pattern.CASE_INSENSITIVE);

    // ── entity extraction ──────────────────────────────────────────────
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|am|pm)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIME = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b"
            + "(?:\\s*(?:o'?clock)?)?\\s*(a\\.?m\\.?|p\\.?m\\.?|am|pm|morning|evening|night|noon)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile(
            "\\b(\\d+|an|a|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|fifty|sixty|ninety)"
            + "\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Understand a normalised command. Returns {@link Plan#unknown()} when this parser is not
     * confident, so the caller falls back to the existing router.
     */
    public static Plan parse(String text) {
        if (text == null) return Plan.unknown();
        String s = text.trim();
        if (s.isEmpty()) return Plan.unknown();
        String low = s.toLowerCase(Locale.ROOT);

        // ── screenshot: no arguments, so it is always complete ──
        if (SCREENSHOT.matcher(low).matches()) {
            return Plan.of(IrisIntent.TAKE_SCREENSHOT)
                    .goal("Take a screenshot")
                    .step(ToolCall.of("take_screenshot"))
                    .confidence(0.95f).build();
        }

        // ── torch ──
        Matcher torch = TORCH.matcher(low);
        if (torch.matches()) {
            String state = torch.group(1).toLowerCase(Locale.ROOT);
            return Plan.of(IrisIntent.TORCH)
                    .goal("Turn the torch " + state)
                    .entity("state", state)
                    .step(ToolCall.of("set_torch", "state", state))
                    .confidence(0.95f).build();
        }

        // ── alarm: needs a time; ask when it is absent ──
        Matcher alarm = ALARM.matcher(low);
        if (alarm.matches()) {
            String rest = alarm.group(1) == null ? "" : alarm.group(1);
            String time = extractTime(rest);
            Plan.Builder b = Plan.of(IrisIntent.SET_ALARM).goal("Set an alarm");
            if (time.isEmpty()) {
                return b.missing("time").confidence(0.8f).build();
            }
            return b.entity("time", time)
                    .step(ToolCall.of("create_alarm", "time", time))
                    .confidence(0.9f).build();
        }

        // ── timer: needs a duration ──
        Matcher timer = TIMER.matcher(low);
        if (timer.matches()) {
            String rest = timer.group(1) == null ? "" : timer.group(1);
            String duration = extractDuration(rest);
            Plan.Builder b = Plan.of(IrisIntent.SET_TIMER).goal("Set a timer");
            if (duration.isEmpty()) {
                return b.missing("duration").confidence(0.8f).build();
            }
            return b.entity("duration", duration)
                    .step(ToolCall.of("create_timer", "duration", duration))
                    .confidence(0.9f).build();
        }

        // ── call: needs a recipient, and is always sensitive (confirm) ──
        Matcher call = CALL.matcher(low);
        if (call.matches()) {
            String rest = call.group(1) == null ? "" : call.group(1).trim();
            rest = rest.replaceFirst("^(?:to|up)\\s+", "").trim();
            Plan.Builder b = Plan.of(IrisIntent.CALL_CONTACT).goal("Place a call");
            if (rest.isEmpty()) {
                return b.missing("recipient").confidence(0.85f).build();
            }
            // Use the original casing for the name, not the lower-cased copy.
            String name = tailOf(s, rest);
            return b.entity("recipient", name)
                    .step(ToolCall.of("call_contact", "name", name))
                    .confidence(0.9f).build();
        }

        // ── screen recording ──
        Matcher screenRec = SCREEN_REC.matcher(low);
        if (screenRec.matches()) {
            String duration = extractDuration(screenRec.group(1) == null ? "" : screenRec.group(1));
            Plan.Builder b = Plan.of(IrisIntent.RECORD_SCREEN).goal("Record the screen");
            if (!duration.isEmpty()) b.entity("duration", duration);
            return b.step(ToolCall.of("record_screen", "duration", duration)).confidence(0.9f).build();
        }

        // ── camera video ──
        Matcher video = VIDEO.matcher(low);
        if (video.matches()) {
            String cam = video.group(1) == null ? "" : video.group(1).toLowerCase(Locale.ROOT);
            boolean front = cam.startsWith("front") || cam.startsWith("selfie");
            String duration = extractDuration(video.group(2) == null ? "" : video.group(2));
            Plan.Builder b = Plan.of(IrisIntent.RECORD_VIDEO)
                    .goal("Record a video")
                    .entity("camera", front ? "front" : "back");
            if (!duration.isEmpty()) b.entity("duration", duration);
            return b.step(ToolCall.of("record_camera_video",
                            "camera", front ? "front" : "back", "duration", duration))
                    .confidence(0.9f).build();
        }

        // ── voice memo ──
        Matcher voice = VOICE.matcher(low);
        if (voice.matches() && (low.startsWith("record") || low.startsWith("voice") || low.startsWith("audio")
                || low.startsWith("take") || low.startsWith("capture") || low.startsWith("start"))) {
            String duration = extractDuration(voice.group(1) == null ? "" : voice.group(1));
            Plan.Builder b = Plan.of(IrisIntent.RECORD_VOICE).goal("Record a voice memo");
            if (!duration.isEmpty()) b.entity("duration", duration);
            return b.step(ToolCall.of("record_voice", "duration", duration)).confidence(0.9f).build();
        }

        return Plan.unknown();
    }

    // ─────────────────────────── entity helpers ───────────────────────────

    /** A clock time such as "7", "7 am", "6:30 pm", "seven o'clock". Empty when absent. */
    static String extractTime(String text) {
        if (text == null) return "";
        String t = text.replaceFirst("^(?:\\s*(?:for|at|to)\\s+)", " ").trim();
        if (t.isEmpty()) return "";
        Matcher d = CLOCK_TIME.matcher(t);
        if (d.find()) {
            StringBuilder sb = new StringBuilder(d.group(1));
            if (d.group(2) != null) sb.append(':').append(d.group(2));
            if (d.group(3) != null) sb.append(' ').append(d.group(3).replace(".", "").toLowerCase(Locale.ROOT));
            else if (t.matches(".*\\b(morning|evening|night|noon)\\b.*")) {
                sb.append(t.matches(".*\\bmorning\\b.*") ? " am" : " pm");
            }
            return sb.toString();
        }
        Matcher w = WORD_TIME.matcher(t);
        if (w.find()) {
            String out = w.group(1);
            String suffix = w.group(2);
            if (suffix != null) {
                String x = suffix.replace(".", "").toLowerCase(Locale.ROOT);
                if (x.startsWith("morning")) x = "am";
                else if (x.startsWith("evening") || x.startsWith("night")) x = "pm";
                else if (x.startsWith("noon")) x = "pm";
                out += " " + x;
            }
            return out;
        }
        return "";
    }

    /** A duration such as "20 seconds", "2 minutes", "an hour". Empty when absent. */
    static String extractDuration(String text) {
        if (text == null) return "";
        String t = text.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\bhalf\\s+an?\\s+hour\\b.*")) return "30 minutes";
        Matcher m = DURATION.matcher(t);
        if (m.find()) return (m.group(1) + " " + m.group(2)).trim();
        return "";
    }

    /** Recover the original-case tail of {@code original} matching the lower-cased {@code tail}. */
    private static String tailOf(String original, String tail) {
        if (original == null || tail == null || tail.isEmpty()) return tail == null ? "" : tail;
        int idx = original.toLowerCase(Locale.ROOT).lastIndexOf(tail);
        return idx >= 0 ? original.substring(idx) : tail;
    }

    /** The question to ask when a field is missing (§11 clarification flow). */
    public static String clarifyQuestion(Plan plan) {
        if (plan == null) return "";
        switch (plan.firstMissing()) {
            case "time":      return "What time should I set the alarm for?";
            case "duration":  return "How long should it be?";
            case "recipient": return "Who should I call?";
            case "message":   return "What should the message say?";
            default:          return "";
        }
    }
}
