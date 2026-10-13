package com.iris.assistant;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies an incoming message so the responder can choose what to say.
 *
 * Pure, with no Android imports, so every pattern is checked offline. Customisable values arrive as
 * parameters rather than being read from settings here, per the plan: nothing in the rules engine
 * may consult storage directly.
 *
 * Two rules govern the whole file.
 *
 * **Whole-word matching only.** The owner asked for shortforms — ty, gm, gn — and those are exactly
 * the patterns that misfire on substrings. A two-letter token found anywhere inside a sentence is
 * almost always a coincidence, so every word match is tokenised rather than searched.
 *
 * **Other languages must not be mistaken for English.** The on-device probe returned Odia
 * transliterated into Latin script: "Mu mora faeda dekhibii", "Bhala laguni", "Besi bhabuchu
 * boliki amiti laguchi ki". Those are real messages from a real contact, and they are used as
 * must-not-match regression cases in the tests. A classifier that reads "ki" as "k" or finds "ty"
 * inside "amiti" would answer a message it did not understand.
 *
 * When nothing matches the answer is NONE, which means silence. IRIS has no language model and
 * cannot compose a reply to an arbitrary message, so not recognising something must never produce
 * a generic answer.
 */
public final class MessageCategory {

    /** What kind of message this is. Order of the enum is not significant; see classify(). */
    public enum Kind {
        /** Nothing recognised. Terminal: the responder stays silent. */
        NONE,
        /** Suvra was named or tagged. */
        MENTION,
        /** An attachment with no words of its own. */
        MEDIA_ONLY,
        /** Explicitly pressing for attention. */
        URGENT,
        /** A festival or holiday wish. */
        FESTIVAL,
        /** A birthday wish. */
        BIRTHDAY,
        /** Good morning / afternoon / evening / night, including gm and gn. */
        TIME_GREETING,
        /** A weekend or Friday wish. */
        WEEKEND,
        /** Thanks, including ty and thx. */
        THANKS,
        /** Checking whether he is around. */
        CHECK_IN,
        /** A plain hello. */
        GREETING,
        /** Ends in a question mark and matched nothing more specific. */
        QUESTION,
        /** "ok", "hmm". Recognised specifically so it can be ignored rather than answered. */
        ACKNOWLEDGEMENT,
        /** One or two words that matched nothing else. */
        SHORT
    }

    /** The classification, plus any detail a reply might want to use. */
    public static final class Result {
        public final Kind kind;
        /** For TIME_GREETING the part of day; for FESTIVAL the festival. Empty otherwise. */
        public final String detail;

        Result(Kind kind, String detail) {
            this.kind = kind;
            this.detail = detail == null ? "" : detail;
        }

        @Override public String toString() {
            return detail.isEmpty() ? kind.name() : kind.name() + ":" + detail;
        }
    }

    private static final Result NO_MATCH = new Result(Kind.NONE, "");

    /** Longest message, in words, that SHORT will claim. */
    public static final int SHORT_MAX_WORDS = 2;

    /** Longest message, in words, that CHECK_IN will claim. Keeps "I was online then" out. */
    public static final int CHECK_IN_MAX_WORDS = 5;

    // ─── Word sets. Matched whole-word only. ───

    private static final Set<String> GREETING_WORDS = words(
            "hi", "hii", "hiii", "hiiii", "hello", "helo", "hellooo", "hlo", "hey", "heyy",
            "heyyy", "yo", "namaste", "namaskar", "hola", "sup", "wassup", "whatsup", "wasup");

    private static final Set<String> THANKS_WORDS = words(
            "thanks", "thankyou", "thanku", "thanx", "thnx", "thx", "ty", "tysm", "tq", "tqsm",
            "thankz", "dhanyavad", "dhanyabad", "shukriya");

    private static final Set<String> URGENT_WORDS = words(
            "urgent", "urgently", "asap", "emergency", "immediately", "important");

    private static final Set<String> CHECK_IN_WORDS = words(
            "online", "awake", "available", "wyd", "there");

    /**
     * Recognised so it can be deliberately NOT answered.
     *
     * Without this, "ok" would fall through to SHORT and get a full "Suvra is asleep" reply, which
     * is noise nobody wants. Defaults to no reply at the policy layer.
     */
    private static final Set<String> ACK_WORDS = words(
            "ok", "okay", "okey", "k", "kk", "okk", "hmm", "hm", "hmmm", "fine", "cool", "nice",
            "sure", "achha", "accha", "theek", "thik", "haan", "haa", "yep", "yup", "noted");

    // ─── Phrase sets. Matched as whole-word sequences. ───

    private static final List<String> THANKS_PHRASES = Arrays.asList(
            "thank you", "thank u", "thanks a lot", "many thanks", "thanks so much");

    private static final List<String> URGENT_PHRASES = Arrays.asList(
            "call me", "need you", "right now", "very important", "pick up", "answer me");

    private static final List<String> CHECK_IN_PHRASES = Arrays.asList(
            "are you there", "you there", "are you awake", "you up", "are you free",
            "are you busy", "what you doing", "what are you doing", "kya kar raha",
            "kya kar rahe", "u there");

    private static final List<String> MEDIA_PHRASES = Arrays.asList(
            "sent a photo", "sent a video", "sent a sticker", "sent an audio",
            "sent a document", "sent a gif", "sent a voice message", "voice message",
            "photo", "sticker");

    private static final List<String> WEEKEND_PHRASES = Arrays.asList(
            "happy weekend", "good weekend", "happy friday", "enjoy your weekend",
            "have a good weekend", "have a great weekend");

    private static final List<String> BIRTHDAY_PHRASES = Arrays.asList(
            "happy birthday", "happy bday", "hbd", "many more happy returns",
            "many happy returns", "happy returns of the day");

    /**
     * Festivals, matched by PHRASE and never by date.
     *
     * Diwali, Eid and Durga Puja all move in the Gregorian calendar. A date table would need
     * maintaining forever and would be silently wrong the first year it lapsed, so the greeting is
     * recognised whenever it is sent. Replying warmly to "happy diwali" in March costs nothing;
     * failing to reply on the actual day because a table was stale is the real failure.
     */
    private static final List<String> FESTIVAL_PHRASES = Arrays.asList(
            "happy diwali", "happy deepavali", "happy dipawali", "shubh diwali",
            "happy holi", "happy new year", "hny", "happy navratri", "happy dussehra",
            "happy dashain", "subho bijoya", "shubho bijoya", "subho bijaya",
            "happy durga puja", "happy pujo", "eid mubarak", "happy eid", "ramadan mubarak",
            "merry christmas", "happy christmas", "happy pongal", "happy onam",
            "happy rakhi", "happy raksha bandhan", "happy ganesh chaturthi",
            "happy makar sankranti", "happy ugadi", "happy baisakhi", "happy vaisakhi",
            "happy lohri", "happy independence day", "happy republic day", "happy gandhi jayanti",
            "happy bihu", "happy nabobarsho", "subho nababarsha");

    private MessageCategory() { }

    /**
     * Classify a message.
     *
     * @param raw             the message text as received
     * @param mentionKeywords words that count as naming Suvra, from settings. May be null or empty
     */
    public static Result classify(String raw, Collection<String> mentionKeywords) {
        if (raw == null) return NO_MATCH;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return NO_MATCH;

        String flat = normalise(trimmed);
        if (flat.isEmpty()) return NO_MATCH;
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(flat.split(" ")));
        int wordCount = flat.split(" ").length;

        // Precedence runs most specific first. Being named directly outranks everything, because
        // a mention is the one case where staying silent is conspicuous.
        if (mentionsOwner(tokens, mentionKeywords)) return new Result(Kind.MENTION, "");

        // Checked early: an attachment placeholder contains words like "photo" that would
        // otherwise be read as a message about a photo.
        if (containsAnyPhrase(flat, MEDIA_PHRASES) && wordCount <= 4) {
            return new Result(Kind.MEDIA_ONLY, "");
        }

        if (containsAnyWord(tokens, URGENT_WORDS) || containsAnyPhrase(flat, URGENT_PHRASES)) {
            return new Result(Kind.URGENT, "");
        }

        String festival = firstPhrase(flat, FESTIVAL_PHRASES);
        if (!festival.isEmpty()) return new Result(Kind.FESTIVAL, festival);

        if (containsAnyPhrase(flat, BIRTHDAY_PHRASES)) return new Result(Kind.BIRTHDAY, "");

        // Before the plain greeting, so "good morning" is not merely a hello.
        String partOfDay = timeGreeting(flat, tokens);
        if (!partOfDay.isEmpty()) return new Result(Kind.TIME_GREETING, partOfDay);

        if (containsAnyPhrase(flat, WEEKEND_PHRASES)) return new Result(Kind.WEEKEND, "");

        if (containsAnyWord(tokens, THANKS_WORDS) || containsAnyPhrase(flat, THANKS_PHRASES)) {
            return new Result(Kind.THANKS, "");
        }

        // Before QUESTION, since "are you there?" is a check-in first and a question second.
        if (wordCount <= CHECK_IN_MAX_WORDS
                && (containsAnyWord(tokens, CHECK_IN_WORDS)
                    || containsAnyPhrase(flat, CHECK_IN_PHRASES))) {
            return new Result(Kind.CHECK_IN, "");
        }

        if (containsAnyWord(tokens, GREETING_WORDS)) return new Result(Kind.GREETING, "");

        // Recognised before SHORT so a bare "ok" can be ignored rather than answered.
        if (wordCount <= SHORT_MAX_WORDS && containsAnyWord(tokens, ACK_WORDS)) {
            return new Result(Kind.ACKNOWLEDGEMENT, "");
        }

        if (trimmed.endsWith("?")) return new Result(Kind.QUESTION, "");

        if (wordCount <= SHORT_MAX_WORDS) return new Result(Kind.SHORT, "");

        return NO_MATCH;
    }

    /** Convenience overload for callers with no mention keywords configured. */
    public static Result classify(String raw) {
        return classify(raw, null);
    }

    /**
     * Whether Suvra is named.
     *
     * Whole-word only, so a keyword of "stg" cannot match inside an unrelated word. Keywords are
     * supplied by the caller because they are an editable setting.
     */
    public static boolean mentionsOwner(Set<String> tokens, Collection<String> mentionKeywords) {
        if (mentionKeywords == null || tokens == null) return false;
        for (String keyword : mentionKeywords) {
            if (keyword == null) continue;
            String clean = normalise(keyword);
            if (clean.isEmpty()) continue;
            // A multi-word keyword has to be matched as a sequence, not as loose tokens.
            if (clean.contains(" ")) {
                if (containsAnyPhrase(String.join(" ", tokens), Arrays.asList(clean))) return true;
            } else if (tokens.contains(clean)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which part of day was wished, or empty.
     *
     * Handles the shortforms the owner asked for: gm, gn, gn8, gnite. Those are deliberately kept
     * to the two that are universally understood -- "ga" and "ge" for afternoon and evening were
     * considered and rejected as too ambiguous to be worth the false positives.
     */
    private static String timeGreeting(String flat, Set<String> tokens) {
        if (tokens.contains("gm") || tokens.contains("gud") && flat.contains("mrng")) return "morning";
        if (tokens.contains("gn") || tokens.contains("gn8") || tokens.contains("gnite")
                || tokens.contains("goodnight")) {
            return "night";
        }
        if (tokens.contains("suprabhat") || tokens.contains("subhoprabhat")) return "morning";
        if (tokens.contains("shubhratri")) return "night";

        for (String prefix : new String[]{"good", "gud", "gd"}) {
            if (containsAnyPhrase(flat, Arrays.asList(prefix + " morning", prefix + " mrng",
                    prefix + " mrg"))) return "morning";
            if (containsAnyPhrase(flat, Arrays.asList(prefix + " afternoon",
                    prefix + " aftn"))) return "afternoon";
            if (containsAnyPhrase(flat, Arrays.asList(prefix + " evening",
                    prefix + " eve"))) return "evening";
            if (containsAnyPhrase(flat, Arrays.asList(prefix + " night", prefix + " nite",
                    prefix + " nyt"))) return "night";
        }
        return "";
    }

    /**
     * Lowercase, strip everything that is not a letter, digit or space, collapse runs of spaces.
     *
     * Punctuation and emoji become separators rather than being deleted, so "hi!there" becomes two
     * words instead of one nonsense token, and "📷 Sent a photo" reduces cleanly to "sent a photo".
     */
    public static String normalise(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAnyWord(Set<String> tokens, Set<String> candidates) {
        for (String token : tokens) {
            if (candidates.contains(token)) return true;
        }
        return false;
    }

    /** Whole-word sequence match, so "happy holi" cannot be found inside "happy holiday". */
    private static boolean containsAnyPhrase(String flat, List<String> phrases) {
        return !firstPhrase(flat, phrases).isEmpty();
    }

    private static String firstPhrase(String flat, List<String> phrases) {
        String padded = " " + flat + " ";
        for (String phrase : phrases) {
            if (padded.contains(" " + phrase + " ")) return phrase;
        }
        return "";
    }

    private static Set<String> words(String... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }
}
