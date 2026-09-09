package com.iris.assistant;

import java.util.*;
import java.util.regex.*;

/** Conservative command cleanup. Never rewrite the contents of a dictated message. */
public final class SpeechText {
    private SpeechText() { }
    private static final Pattern WRAPPER = Pattern.compile(
            "(?i)^(?:hey[,\\s]+|ok(?:ay)?[,\\s]+|iris[,\\s]+|please\\s+|kindly\\s+|just\\s+"
            + "|(?:can|could|would|will)\\s+(?:you|u)\\s+|(?:i want|i need|i would like|i'd like)\\s+you\\s+to\\s+)");
    private static final Pattern PAYLOAD = Pattern.compile(
            "(?i)^(?:text|txt|message|msg|sms|tell|send|write|compose|whatsapp|email|search|remember|remind|add|create)\\b.*");
    private static final Set<String> HEADS = new HashSet<>(Arrays.asList(
            "call", "dial", "phone", "ring", "text", "message", "send", "tell", "whatsapp",
            "email", "alarm", "timer", "reminder", "weather", "battery", "flashlight",
            "volume", "notifications", "location", "open", "search", "time", "stop", "cancel",
            "set", "turn", "switch", "record", "take", "capture", "remember", "remind",
            "what", "where", "when", "who", "how", "is", "are", "do", "don't", "do not"));

    public static String command(String input) {
        String s = input == null ? "" : input.trim().replace('\u2019', '\'');
        while (true) {
            String next = WRAPPER.matcher(s).replaceFirst("").trim();
            if (next.equals(s)) break;
            s = next;
        }
        // Preserve punctuation and "please" in messages, searches and memories.
        if (PAYLOAD.matcher(s).matches()) return s;
        s = s.replaceFirst("[.!?]+$", "").trim()
                .replaceFirst("(?i)[,\\s]+(?:please|for me)$", "").trim();
        s = s.replaceFirst("(?i)^(?:make|place)\\s+(?:a\\s+)?call\\s+to\\s+", "call ");
        Matcher call = Pattern.compile("(?i)^give\\s+(.+?)\\s+a\\s+call$").matcher(s);
        if (call.matches()) return "call " + call.group(1);
        Matcher torch = Pattern.compile("(?i)^(?:switch|turn|put)\\s+(?:(on|off)\\s+(?:the\\s+)?(?:torch|flashlight|flash light)"
                + "|(?:the\\s+)?(?:torch|flashlight|flash light)\\s+(on|off))$").matcher(s);
        if (torch.matches()) return "torch " + (torch.group(1) != null ? torch.group(1) : torch.group(2)).toLowerCase(Locale.ROOT);
        if (s.matches("(?i)^(?:increase|raise|turn up)\\s+(?:the\\s+)?(?:volume|sound)$")) return "volume up";
        if (s.matches("(?i)^(?:decrease|lower|reduce|turn down)\\s+(?:the\\s+)?(?:volume|sound)$")) return "volume down";
        if (s.matches("(?i)^(?:take|click|capture|save)\\s+(?:a|one|the)?\\s*screen\\s?shot$")) return "take a screenshot";
        return s;
    }

    /** Only exact, unambiguous learned prefixes. No fuzzy edits inside names or payloads. */
    public static String aliases(String text, Map<String, List<String>> aliases) {
        if (text == null || text.isEmpty() || aliases == null || aliases.isEmpty()) return text;
        String first = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (HEADS.contains(first)) return text;
        String best = null; int length = 0; boolean ambiguous = false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            if (!HEADS.contains(entry.getKey()) || entry.getValue() == null) continue;
            for (String raw : entry.getValue()) {
                if (raw == null) continue;
                String alias = raw.trim().toLowerCase(Locale.ROOT);
                if (alias.isEmpty() || !(lower.equals(alias) || lower.startsWith(alias + " "))) continue;
                if (alias.length() > length) { length = alias.length(); best = entry.getKey(); ambiguous = false; }
                else if (alias.length() == length && !entry.getKey().equals(best)) ambiguous = true;
            }
        }
        return best == null || ambiguous ? text : best + text.substring(length);
    }

    /** Information questions only, not "text Dad the battery is low" or "remind me ... time". */
    public static boolean quickInfo(String n) {
        if (n == null) return false;
        n = n.toLowerCase(Locale.ROOT).trim();
        if (n.matches("^(?:time(?: now)?|battery(?: level| percentage| percent| status)?|charging status)$")) return true;
        return n.matches("^(?:what(?:s| is|'s)?|how|is|am|are|tell me|check)\\b.*")
                && n.matches(".*\\b(?:time|battery|charging|charged|plugged)\\b.*");
    }

    public static boolean lowConfidence(float score) {
        return Float.isFinite(score) && score >= 0 && score < 0.35f;
    }
}
