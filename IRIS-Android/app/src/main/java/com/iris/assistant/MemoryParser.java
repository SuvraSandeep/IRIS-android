package com.iris.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses natural language into structured memory entries.
 * Rule-based extraction — no LLM required.
 *
 * Handles patterns like:
 * - "My name is Sandeep" → about_me / name / Sandeep
 * - "Rahul's birthday is March 15" → people / rahul birthday / March 15
 * - "Don't call after 10 PM" → preference / no calls after / 10 PM
 * - "I work at Ericsson" → about_me / work / Ericsson
 * - "Remember wife is Priya" → people / wife / Priya
 */
public final class MemoryParser {

    public static class ParsedMemory {
        public String category;
        public String key;
        public String value;
        public float confidence;

        ParsedMemory(String category, String key, String value, float confidence) {
            this.category = category;
            this.key = key;
            this.value = value;
            this.confidence = confidence;
        }
    }

    // Patterns for different memory types
    private static final Pattern ABOUT_ME_NAME = Pattern.compile(
            "(?:my\\s+name\\s+is|i\\s+am|call\\s+me)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOUT_ME_GENERAL = Pattern.compile(
            "(?:i\\s+(?:work|live|stay|study|am from)\\s+(?:at|in|near)?)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOUT_ME_ATTR = Pattern.compile(
            "(?:my\\s+(age|job|city|address|email|language|hobby)\\s+is)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEOPLE_RELATION = Pattern.compile(
            "(?:my\\s+)?(wife|husband|brother|sister|mom|mother|dad|father|boss|doctor|friend|son|daughter|partner)(?:\\s+is|\\s+name\\s+is|\\s*=\\s*)\\s*(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PEOPLE_POSSESSIVE = Pattern.compile(
            "(.+?)(?:'s|s')\\s+(birthday|number|phone|email|address|nickname)\\s+(?:is)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERENCE_NO = Pattern.compile(
            "(?:don'?t|do not|never|no)\\s+(.+?)\\s+(?:after|before|on|during)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERENCE_ALWAYS = Pattern.compile(
            "(?:always|every\\s+time)\\s+(.+?)\\s+(?:for|before|when|with)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERENCE_GENERAL = Pattern.compile(
            "(?:i\\s+(?:prefer|like|want|need))\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULE_ONLY = Pattern.compile(
            "(?:only)\\s+(.+?)\\s+(?:can|should|after|before|when)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_IS = Pattern.compile(
            "(.+?)\\s+(?:is|are|means|equals|=)\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private MemoryParser() { }

    /**
     * Parse a natural language statement into a structured memory.
     * Returns null if the input cannot be parsed.
     */
    public static ParsedMemory parse(String input) {
        if (input == null || input.trim().length() < 3) return null;
        String text = input.trim();
        // Strip leading "remember that", "note that", etc.
        text = text.replaceAll("(?i)^(?:remember|note|save|store)\\s+(?:that\\s+)?", "").trim();
        if (text.isEmpty()) return null;

        Matcher m;

        // 1. About me: name
        m = ABOUT_ME_NAME.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_ABOUT_ME, "name", m.group(1).trim(), 0.95f);

        // 2. About me: work/live/study
        m = ABOUT_ME_GENERAL.matcher(text);
        if (m.matches()) {
            String verb = text.split("\\s+")[1].toLowerCase();
            return new ParsedMemory(MemoryStore.CAT_ABOUT_ME, verb, m.group(1).trim(), 0.90f);
        }

        // 3. About me: attributes
        m = ABOUT_ME_ATTR.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_ABOUT_ME, m.group(1).trim().toLowerCase(), m.group(2).trim(), 0.92f);

        // 4. People: relationship
        m = PEOPLE_RELATION.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_PEOPLE, m.group(1).trim().toLowerCase(), m.group(2).trim(), 0.95f);

        // 5. People: possessive (X's birthday is Y)
        m = PEOPLE_POSSESSIVE.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_PEOPLE,
                m.group(1).trim().toLowerCase() + " " + m.group(2).trim().toLowerCase(),
                m.group(3).trim(), 0.90f);

        // 6. Preference: don't/never
        m = PREFERENCE_NO.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_PREFERENCE,
                "no " + m.group(1).trim().toLowerCase(), m.group(2).trim(), 0.88f);

        // 7. Preference: always
        m = PREFERENCE_ALWAYS.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_PREFERENCE,
                "always " + m.group(1).trim().toLowerCase(), m.group(2).trim(), 0.85f);

        // 8. Preference: I prefer/like/want
        m = PREFERENCE_GENERAL.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_PREFERENCE,
                "preference", m.group(1).trim(), 0.80f);

        // 9. Rule: only X can/should Y
        m = RULE_ONLY.matcher(text);
        if (m.matches()) return new ParsedMemory(MemoryStore.CAT_RULE,
                m.group(1).trim().toLowerCase(), m.group(2).trim(), 0.85f);

        // 10. Generic: X is Y (fallback)
        m = GENERIC_IS.matcher(text);
        if (m.matches()) {
            String key = m.group(1).trim();
            String value = m.group(2).trim();
            if (key.length() > 1 && value.length() > 0) {
                String category = guessCategory(key);
                return new ParsedMemory(category, key.toLowerCase(), value, 0.70f);
            }
        }

        // Couldn't parse
        return null;
    }

    /** Guess category from key content. */
    private static String guessCategory(String key) {
        String k = key.toLowerCase();
        if (k.matches(".*\\b(name|age|job|work|city|live|language|hobby|i am|my)\\b.*"))
            return MemoryStore.CAT_ABOUT_ME;
        if (k.matches(".*\\b(wife|husband|brother|sister|mom|dad|boss|friend|doctor|son|daughter)\\b.*"))
            return MemoryStore.CAT_PEOPLE;
        if (k.matches(".*\\b(don't|never|always|prefer|after|before)\\b.*"))
            return MemoryStore.CAT_PREFERENCE;
        if (k.matches(".*\\b(only|block|allow|restrict)\\b.*"))
            return MemoryStore.CAT_RULE;
        return MemoryStore.CAT_ABOUT_ME; // default
    }
}
