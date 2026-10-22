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
    private static final Pattern PHOTO = Pattern.compile(
            "^(?:take|click|capture|snap)\\s+(?:a\\s+|my\\s+|the\\s+)?"
            + "(?:(front|selfie|back|rear)\\s+)?(?:camera\\s+)?(?:photo|picture|pic|selfie)\\b.*$",
            Pattern.CASE_INSENSITIVE);
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
            "^(?:(?:turn\\s+)?(?:the\\s+)?(?:torch|flashlight)\\s+(on|off)|turn\\s+(on|off)\\s+(?:the\\s+)?(?:torch|flashlight)|torch\\s+(on|off))$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_RECORD = Pattern.compile(
            "^(?:stop\\s+(?:the\\s+)?(?:voice\\s+|screen\\s+|video\\s+|audio\\s+)?recording|stop\\s+record(?:ing)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLUME = Pattern.compile(
            "^(?:set\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3}|full|max|maximum|half|zero|mute)\\s*(?:percent|%)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_APP = Pattern.compile(
            "^(?:open|launch)\\s+([a-zA-Z0-9\\s]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_SEARCH = Pattern.compile(
            "^(?:search\\s+(?:the\\s+web\\s+|google\\s+|online\\s+)?for|google)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_STATUS = Pattern.compile(
            "^(?:phone\\s+status|battery\\s+status|battery\\s+level|device\\s+status)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_NOTIF = Pattern.compile(
            "^(?:read|check)\\s+(?:my\\s+|the\\s+)?notifications?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSE = Pattern.compile(
            "^(?:send\\s+(?:an?\\s+)?(?:sms|text|message)\\s+to|text)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:saying|that|message)\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE);

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

        // ── still photo (not video) ──
        Matcher photo = PHOTO.matcher(low);
        if (photo.matches()) {
            String cam = photo.group(1) == null ? "" : photo.group(1).toLowerCase(Locale.ROOT);
            boolean front = cam.startsWith("front") || cam.startsWith("selfie");
            return Plan.of(IrisIntent.TAKE_PHOTO)
                    .goal("Take a photo")
                    .entity("camera", front ? "front" : "back")
                    .step(ToolCall.of("take_photo", "camera", front ? "front" : "back"))
                    .confidence(0.9f).build();
        }

        // ── torch ──
        Matcher torch = TORCH.matcher(low);
        if (torch.matches()) {
            String state = torch.group(1);
            if (state == null) state = torch.group(2);
            if (state == null) state = torch.group(3);
            if (state == null) state = "on";
            state = state.toLowerCase(Locale.ROOT);
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
            // Use the original casing for the name, not the lower-cased copy. Anchored on the
            // regex match's own captured-group start offset (call.start(1)) instead of
            // re-searching the whole utterance for `rest` via lastIndexOf -- a lastIndexOf
            // search picks the LAST occurrence of the fragment anywhere in the string, so a
            // name that repeats a stripped word elsewhere in the utterance (e.g. "call to tony
            // to confirm") could resolve to the wrong span. toLowerCase(Locale.ROOT) does not
            // change character offsets for this parser's English ASCII input, so group(1)'s
            // start position in `low` is exactly its start position in `s`; re-apply the same
            // "to"/"up" strip to the ORIGINAL-case slice instead of computing a manual length
            // delta, so the two strips can never drift out of sync with each other.
            String name = call.start(1) >= 0
                ? s.substring(call.start(1)).trim().replaceFirst("(?i)^(?:to|up)\\s+", "").trim()
                : tailOf(s, rest);
            if (name.isEmpty()) name = tailOf(s, rest);
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

        // ── stop recording ──
        if (STOP_RECORD.matcher(low).matches()) {
            return Plan.of(IrisIntent.RECORD_VOICE)
                    .goal("Stop recording")
                    .step(ToolCall.of("stop_recording"))
                    .confidence(0.95f).build();
        }

        // ── volume ──
        Matcher volume = VOLUME.matcher(low);
        if (volume.matches()) {
            String lvl = volume.group(1).toLowerCase(Locale.ROOT);
            return Plan.of(IrisIntent.SET_VOLUME)
                    .goal("Set volume to " + lvl)
                    .entity("level", lvl)
                    .step(ToolCall.of("set_volume", "level", lvl))
                    .confidence(0.95f).build();
        }

        // ── open app ──
        Matcher openApp = OPEN_APP.matcher(low);
        if (openApp.matches()) {
            String app = openApp.group(1).trim();
            if (!app.isEmpty() && !app.equals("the torch") && !app.equals("torch")) {
                return Plan.of(IrisIntent.OPEN_APP)
                        .goal("Open " + app)
                        .entity("app", app)
                        .step(ToolCall.of("open_app", "app", app))
                        .confidence(0.9f).build();
            }
        }

        // ── web search ──
        Matcher webSearch = WEB_SEARCH.matcher(low);
        if (webSearch.matches()) {
            String query = webSearch.group(1).trim();
            if (!query.isEmpty()) {
                return Plan.of(IrisIntent.WEB_SEARCH)
                        .goal("Search the web for " + query)
                        .entity("query", query)
                        .step(ToolCall.of("search_web", "query", query))
                        .confidence(0.9f).build();
            }
        }

        // ── phone status ──
        if (PHONE_STATUS.matcher(low).matches()) {
            return Plan.of(IrisIntent.PHONE_STATUS)
                    .goal("Check phone status")
                    .step(ToolCall.of("phone_status"))
                    .confidence(0.95f).build();
        }

        // ── read notifications ──
        if (READ_NOTIF.matcher(low).matches()) {
            return Plan.of(IrisIntent.READ_NOTIFICATIONS)
                    .goal("Read notifications")
                    .step(ToolCall.of("read_notifications"))
                    .confidence(0.95f).build();
        }

        // ── compose message ──
        Matcher compose = COMPOSE.matcher(low);
        if (compose.matches()) {
            String recipient = compose.group(1).trim();
            String message = compose.group(2) == null ? "" : compose.group(2).trim();
            Plan.Builder b = Plan.of(IrisIntent.COMPOSE_MESSAGE).goal("Compose message")
                    .entity("recipient", recipient)
                    .needsConfirmation(true);
            if (message.isEmpty()) {
                return b.missing("message").confidence(0.85f).build();
            }
            return b.entity("message", message)
                    .step(ToolCall.of("compose_message", "name", recipient, "text", message))
                    .confidence(0.9f).build();
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
