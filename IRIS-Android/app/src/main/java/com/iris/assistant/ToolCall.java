package com.iris.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2 — one validated step of a plan (AGENT-ARCHITECTURE.md §7).
 *
 * A tool call is just a name plus string arguments. It carries no ability to do anything: the
 * executor in {@link IrisListeningService} decides whether a call is allowed and how to run it.
 * Anything not on {@link #KNOWN} is rejected, so a planner can never invent a capability.
 */
public final class ToolCall {

    /** The only tool names that may ever appear in a plan. */
    public static final Set<String> KNOWN = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
            java.util.Arrays.asList(
                    "call_contact", "compose_message",
                    "create_alarm", "create_timer", "create_reminder",
                    "take_screenshot", "record_screen", "record_camera_video", "take_photo",
                    "record_voice",
                    "stop_recording",
                    "set_torch", "set_volume",
                    "open_app", "search_web",
                    "search_app", "share_text_to_app", "share_recent_media",
                    "phone_status", "read_notifications")));

    private final String tool;
    private final Map<String, String> arguments;

    public ToolCall(String tool, Map<String, String> arguments) {
        this.tool = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        this.arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }

    public static ToolCall of(String tool, String... keyValues) {
        Map<String, String> args = new LinkedHashMap<>();
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                if (keyValues[i] != null && keyValues[i + 1] != null) args.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return new ToolCall(tool, args);
    }

    public String tool() { return tool; }

    public Map<String, String> arguments() { return Collections.unmodifiableMap(arguments); }

    public String arg(String key) {
        String v = arguments.get(key);
        return v == null ? "" : v;
    }

    public boolean has(String key) { return !arg(key).trim().isEmpty(); }

    /** A tool call is valid only when the name is one IRIS actually implements. */
    public boolean isKnown() { return KNOWN.contains(tool); }

    @Override public String toString() {
        return tool + arguments;
    }
}
