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

    // ─────────── Personal vocabulary repair (pure logic; store lives in PersonalVocabulary) ───────────

    /** Only the first few words are the command head; a dictated message follows after that. */
    static final int HEAD_WORDS = 4;

    /** Verbs whose message payload starts almost immediately — keep the head very short. */
    private static final Set<String> PAYLOAD_VERBS = new HashSet<>(Arrays.asList(
            "text", "txt", "message", "msg", "sms", "tell", "send", "write", "compose",
            "whatsapp", "email", "mail", "search", "google", "remember", "remind", "note", "ask"));

    /** Once one of these appears, everything after it is the user's own words. */
    private static final Set<String> PAYLOAD_MARKERS = new HashSet<>(Arrays.asList(
            "saying", "say", "says", "that", "telling", "asking", "about", "regarding"));

    /**
     * How many leading words may be treated as the command head. Stops before a payload marker,
     * and stays very short for message-style verbs, so a dictated message is never rewritten.
     */
    static int headWordCount(String[] words) {
        if (words == null || words.length == 0) return 0;
        int limit = PAYLOAD_VERBS.contains(words[0].toLowerCase(Locale.ROOT)) ? 2 : HEAD_WORDS;
        int count = Math.min(limit, words.length);
        for (int i = 0; i < count; i++) {
            if (PAYLOAD_MARKERS.contains(words[i].toLowerCase(Locale.ROOT))) return i;
        }
        return count;
    }

    /** A learned variant must never hijack one of these structural/command words. */
    static final Set<String> PROTECTED_WORDS = new HashSet<>(Arrays.asList(
            "call", "dial", "phone", "ring", "text", "message", "msg", "send", "tell", "whatsapp",
            "email", "mail", "alarm", "timer", "reminder", "remind", "remember", "weather",
            "battery", "torch", "flashlight", "volume", "mute", "notification", "notifications",
            "location", "open", "search", "google", "time", "stop", "cancel", "set", "turn",
            "switch", "record", "recording", "take", "capture", "screenshot", "screen", "video",
            "voice", "audio", "photo", "camera", "front", "back", "play", "pause", "next",
            "previous", "silent", "vibrate", "normal", "airplane", "aeroplane", "dnd", "status",
            "and", "the", "a", "an", "to", "for", "on", "off", "my", "me", "i", "is", "are",
            "what", "where", "when", "who", "how", "yes", "no", "saying", "that", "it", "again"));

    /** Loose normalisation used as the key for whole-utterance corrections. */
    public static String normalizeLoose(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[.,!?;:\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Apply only what the user explicitly taught:
     *   1. a whole-utterance correction (exact normalised match), else
     *   2. learned name variants, and only inside the command head.
     * Returns {@code text} unchanged when nothing is confidently applicable, so dictated message
     * content is never rewritten.
     */
    public static String personalRepair(String text, Map<String, String> corrections,
                                        Map<String, List<String>> nameVariants) {
        if (text == null || text.trim().isEmpty()) return text;

        String n = normalizeLoose(text);
        if (corrections != null) {
            String exact = corrections.get(n);
            if (exact != null && !exact.trim().isEmpty()) return exact;
        }
        if (nameVariants == null || nameVariants.isEmpty()) return text;

        String[] words = text.trim().split("\\s+");
        int headCount = headWordCount(words);
        if (headCount <= 0) return text;
        String head = String.join(" ", Arrays.copyOfRange(words, 0, headCount));
        String tail = headCount < words.length
                ? " " + String.join(" ", Arrays.copyOfRange(words, headCount, words.length))
                : "";

        // Longest variants first so "soumya jeet" wins over "soumya".
        List<String[]> pairs = new ArrayList<>();          // [variant, canonical]
        for (Map.Entry<String, List<String>> e : nameVariants.entrySet()) {
            if (e.getValue() == null) continue;
            for (String v : e.getValue()) if (v != null && !v.trim().isEmpty()) {
                pairs.add(new String[]{ v.trim().toLowerCase(Locale.ROOT), e.getKey() });
            }
        }
        pairs.sort((a, b) -> Integer.compare(b[0].length(), a[0].length()));

        String repaired = head;
        boolean changed = false;
        for (String[] p : pairs) {
            String variant = p[0], canonical = p[1];
            if (variant.isEmpty() || PROTECTED_WORDS.contains(variant)) continue;
            String rx = "(?i)(?<![\\p{L}])" + Pattern.quote(variant) + "(?![\\p{L}])";
            String next = repaired.replaceAll(rx, Matcher.quoteReplacement(canonical));
            if (!next.equals(repaired)) { repaired = next; changed = true; }
        }
        return changed ? repaired + tail : text;
    }
}
