package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded app handoff. Payload text is preserved and never executed as another command. */
public final class AppRequest {
    public final String action, app, value;
    public AppRequest(String action, String app, String value) {
        this.action = action == null ? "" : action;
        this.app = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
        this.value = value == null ? "" : value.trim();
    }
    public static String packageFor(String app) {
        switch (app.toLowerCase(Locale.ROOT)) {
            case "whatsapp": case "whats app": return "com.whatsapp";
            case "telegram": return "org.telegram.messenger";
            case "spotify": return "com.spotify.music";
            case "youtube": return "com.google.android.youtube";
            case "youtube music": return "com.google.android.apps.youtube.music";
            case "maps": case "google maps": return "com.google.android.apps.maps";
            case "chrome": return "com.android.chrome";
            case "gmail": return "com.google.android.gm";
            default: return "";
        }
    }
    public boolean valid() {
        if (packageFor(app).isEmpty() || value.isEmpty() || value.length() > 2000) return false;
        if (action.equals("search")) return true;
        if (action.equals("share_text")) return true;
        return action.equals("share_media") && value.matches("photo|image|picture|video|audio|voice memo");
    }
    public static AppRequest parse(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?i)^(?:search|find|look for)\\s+(.+?)\\s+(?:on|in|using)\\s+([a-z ]+)$").matcher(text.trim());
        if (m.matches()) return checked(new AppRequest("search", m.group(2), m.group(1)));
        m = Pattern.compile("(?i)^(?:share|send)\\s+(?:the\\s+)?(?:last|latest)\\s+(photo|image|picture|video|audio|voice memo)\\s+(?:on|via|using)\\s+([a-z ]+)$").matcher(text.trim());
        if (m.matches()) return checked(new AppRequest("share_media", m.group(2), m.group(1).toLowerCase(Locale.ROOT)));
        // App before body avoids splitting dictated messages at a later 'on WhatsApp'.
        m = Pattern.compile("(?i)^share\\s+(?:text\\s+)?(?:on|via|using)\\s+(whatsapp|whats app|telegram|gmail)(?:\\s*:\\s*|\\s+saying\\s+)(.+)$", Pattern.DOTALL).matcher(text.trim());
        if (m.matches()) return checked(new AppRequest("share_text", m.group(1), m.group(2)));
        return null;
    }
    private static AppRequest checked(AppRequest r) { return r.valid() ? r : null; }

    public static AppRequest fromPlan(Plan plan) {
        if (plan == null || !plan.isConfident() || !plan.isComplete() || plan.steps().size() != 1) return null;
        if (plan.intent() != IrisIntent.APP_SEARCH && plan.intent() != IrisIntent.APP_SHARE) return null;
        ToolCall tool = plan.steps().get(0);
        String action = tool.tool().equals("search_app") ? "search"
                : tool.tool().equals("share_text_to_app") ? "share_text"
                : tool.tool().equals("share_recent_media") ? "share_media" : "";
        if ((plan.intent() == IrisIntent.APP_SEARCH) != action.equals("search")) return null;
        if (tool.arguments().size() != 2 || !tool.has("app") || !tool.has("value")) return null;
        return checked(new AppRequest(action, tool.arg("app"), tool.arg("value")));
    }
}
