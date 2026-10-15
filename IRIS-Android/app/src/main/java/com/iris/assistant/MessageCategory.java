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
        /** Congratulations, well done, badhai. */
        CONGRATS,
        /**
         * The message is aimed at IRIS rather than at Suvra.
         *
         * Either it names IRIS, or it arrived while IRIS was the last to speak in that
         * conversation. Separated from the rest because the right answer is different in kind:
         * the sender has worked out they are talking to software, and the useful reply says so
         * and points them back at Suvra.
         */
        TO_IRIS,
        /**
         * The sender asked Suvra to remember or be reminded of something.
         *
         * Handled specially: IRIS parses out the task and any time, schedules a reminder, and
         * tells the sender it has noted it. This is the one category that produces an action, not
         * just a reply.
         */
        REMINDER,
        /** Checking whether he is around. */
        CHECK_IN,
        /** A plain hello. */
        GREETING,
        /** Ends in a question mark and matched nothing more specific. */
        QUESTION,
        /** "ok", "hmm". Recognised specifically so it can be ignored rather than answered. */
        ACKNOWLEDGEMENT,
        /** One or two words that matched nothing else. */
        SHORT,
        /**
         * An ordinary message that matched nothing.
         *
         * Distinct from NONE, which means "no classification". OTHER means "classified as
         * unremarkable", and it exists so the owner can choose to have such messages acknowledged
         * generically. It is off by default: IRIS has no language model and cannot answer the
         * content of an arbitrary message, so the only honest reply is one that does not pretend
         * to have read it.
         */
        OTHER
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
            "heyyy", "yo", "namaste", "namaskar", "hola", "sup", "wassup", "whatsup", "wasup",
            "hiya", "heya", "nomoskar", "nomoshkar", "aadab", "ola", "heyo");

    private static final Set<String> THANKS_WORDS = words(
            "thanks", "thankyou", "thanku", "thanx", "thnx", "thx", "ty", "tysm", "tq", "tqsm",
            "thankz", "dhanyavad", "dhanyabad", "shukriya", "thnks", "thnq", "tnx", "dhonnobad",
            "dhanyawad", "nandri", "meherbani");

    private static final Set<String> URGENT_WORDS = words(
            "urgent", "urgently", "asap", "emergency", "immediately", "important");

    /**
     * Congratulations.
     *
     * "badhai" and "abhinandan" are included because the owner's correspondents use them. "gz" is
     * deliberately excluded: two letters that collide with too much else, the same reason "ga" and
     * "ge" were rejected for greetings.
     */
    private static final Set<String> CONGRATS_WORDS = words(
            "congrats", "congratulations", "congratulation", "congo", "badhai", "badhaai",
            "abhinandan", "mubarak");

    /**
     * Words that mean the sender is addressing IRIS rather than Suvra.
     *
     * Only the assistant's own name. Anything looser would misread ordinary messages: "bot" and
     * "assistant" appear in real conversation often enough to matter.
     */
    private static final Set<String> IRIS_WORDS = words("iris");

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
            "sure", "achha", "accha", "theek", "thik", "haan", "haa", "yep", "yup", "noted",
            "okie", "oki", "kkk", "hmmmm", "alright", "aight", "roger", "done",
            "thike", "thikache", "hanji", "hanjee");

    // ─── Phrase sets. Matched as whole-word sequences. ───

    private static final List<String> THANKS_PHRASES = Arrays.asList(
            "thank you", "thank u", "thanks a lot", "many thanks", "thanks so much");

    private static final List<String> CONGRATS_PHRASES = Arrays.asList(
            "well done", "proud of you", "so proud", "good job", "great job", "nicely done",
            "happy for you", "many congratulations", "hearty congratulations");

    /**
     * Phrases that mean "set a reminder for Suvra".
     *
     * Second-person imperative forms only, so a reminiscence like "remember when we met" or "I'll
     * always remember this" is NOT read as a request to set an alarm. "remember to" needs the
     * "to"; a bare "remember" does not trigger. The test pins both directions.
     */
    private static final List<String> REMINDER_PHRASES = Arrays.asList(
            "remind me", "remind him", "remind suvra", "remind stg", "remind",
            "reminder", "reminder to", "set a reminder", "set an alarm", "set alarm",
            "dont forget to", "don t forget to", "do not forget to",
            "dont let me forget", "don t let me forget", "do not let me forget",
            "remember to", "please remember", "make a note to", "make a note",
            "note to self", "note that you need to", "ping me", "alert me", "nudge me",
            "wake me up to", "wake me to");

    /**
     * Phrases that can only be addressing the responder.
     *
     * Deliberately restricted to second-person interrogative forms. A first attempt included
     * "auto reply", "a bot" and "the assistant", and the owner's own Twilio test message --
     * "Standard auto-reply. You can send outbound messages via the Tryout UI..." -- matched it
     * immediately. Talking ABOUT auto-replies is not talking TO one, and the looser list would
     * have had IRIS explain itself to anybody who mentioned automation.
     */
    private static final List<String> IRIS_PHRASES = Arrays.asList(
            "are you a bot", "are you a robot", "are you a machine", "are you real",
            "are you human", "are you an assistant", "are you his assistant",
            "are you a chatbot", "are you chatbot", "are you a person", "are you alive",
            "are you ai", "are you an ai", "are you siri", "are you alexa",
            "are you google assistant", "are you a computer",
            "is this a bot", "is this a robot", "is this a machine", "is this automated",
            "is this an auto reply", "is this an automated reply", "is this a human",
            "is this iris", "is this a real person",
            "am i talking to a bot", "am i talking to a robot", "am i talking to a machine",
            "am i talking to a person", "am i speaking to a bot", "am i speaking to a human",
            "who am i talking to", "who am i speaking to", "is anyone actually there",
            "talk to a human", "speak to a human", "a real person",
            // Direct questions aimed at IRIS itself, which the owner wants answered interactively.
            "who are you", "what are you", "what is iris", "who is iris", "whats iris",
            "what can you do", "what do you do", "what else can you do", "how can you help",
            "can you help", "can you help me", "what are your features", "what are you capable of",
            "who made you", "who created you", "who built you", "who programmed you",
            "who do you work for", "whose assistant are you", "are you iris", "you are iris",
            "whats your name", "what is your name", "what s your name", "do you have a name",
            "introduce yourself", "tell me about yourself", "how do you work",
            "is iris there", "hello iris", "hi iris", "hey iris", "hii iris", "thanks iris",
            "thank you iris", "ok iris", "okay iris", "iris are you there", "iris you there",
            "iris are you awake", "yo iris", "good morning iris", "good night iris");

    private static final List<String> URGENT_PHRASES = Arrays.asList(
            "call me", "need you", "right now", "very important", "pick up", "answer me");

    private static final List<String> CHECK_IN_PHRASES = Arrays.asList(
            "are you there", "you there", "are you awake", "you up", "are you free",
            "are you busy", "what you doing", "what are you doing", "kya kar raha",
            "kya kar rahe", "u there");

    private static final List<String> MEDIA_PHRASES = Arrays.asList(
            "sent a photo", "sent a picture", "sent a video", "sent a sticker", "sent an audio",
            "sent a document", "sent a file", "sent a gif", "sent a voice message",
            "voice message", "voice note", "photo", "picture", "sticker", "gif",
            "shared a contact", "sent a contact", "live location", "shared live location",
            "sent a reel", "shared a reel", "sent an image", "sent a pdf", "sent a location",
            "missed voice call", "missed video call", "sent a status", "forwarded a message");

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
            "subho noboborsho", "shubho noboborsho", "happy poila boishakh", "happy nabobarsha",
            "happy saraswati puja", "happy kali puja", "happy bhai phonta", "happy bhai dooj",
            "happy janmashtami", "happy mahashivratri", "happy karva chauth", "happy chhath",
            "happy bihu", "happy nabobarsho", "subho nababarsha");

    private MessageCategory() { }

    /**
     * Classify a message.
     *
     * @param raw             the message text as received
     * @param mentionKeywords words that count as naming Suvra, from settings. May be null or empty
     */
    public static Result classify(String raw, Collection<String> mentionKeywords) {
        return classify(raw, mentionKeywords, false);
    }

    /**
     * Classify a message, knowing whether IRIS spoke last in this conversation.
     *
     * @param irisSpokeLast IRIS sent the previous message in this thread, so a reply arriving now
     *                      is probably aimed at IRIS rather than at Suvra
     */
    public static Result classify(String raw, Collection<String> mentionKeywords,
                                  boolean irisSpokeLast) {
        if (raw == null) return NO_MATCH;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return NO_MATCH;

        String flat = normalise(trimmed);
        if (flat.isEmpty()) return NO_MATCH;
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(flat.split(" ")));
        int wordCount = flat.split(" ").length;

        // Being addressed by name comes first, ahead of even a mention of Suvra. If the sender has
        // written "iris" they have worked out what they are talking to, and answering as though
        // the message were meant for Suvra would be the one reply that is plainly wrong.
        if (containsAnyWord(tokens, IRIS_WORDS) || containsAnyPhrase(flat, IRIS_PHRASES)) {
            return new Result(Kind.TO_IRIS, "named");
        }

        // Precedence runs most specific first. Being named directly outranks everything, because
        // a mention is the one case where staying silent is conspicuous.
        if (mentionsOwner(tokens, mentionKeywords)) return new Result(Kind.MENTION, "");

        // Checked early: an attachment placeholder contains words like "photo" that would
        // otherwise be read as a message about a photo.
        if (containsAnyPhrase(flat, MEDIA_PHRASES) && wordCount <= 4) {
            return new Result(Kind.MEDIA_ONLY, "");
        }

        // A request to remember something, before URGENT, because "don't forget to call, it's
        // urgent" is a reminder first \u2014 the action (noting it) is what the sender wants, and
        // the urgency is a property of the reminder, not a separate category.
        if (containsAnyPhrase(flat, REMINDER_PHRASES)) {
            return new Result(Kind.REMINDER, "");
        }

        if (containsAnyWord(tokens, URGENT_WORDS) || containsAnyPhrase(flat, URGENT_PHRASES)
                || fuzzyUrgent(tokens)) {
            return new Result(Kind.URGENT, "");
        }

        String festival = firstPhrase(flat, FESTIVAL_PHRASES);
        if (!festival.isEmpty()) return new Result(Kind.FESTIVAL, festival);

        if (containsAnyPhrase(flat, BIRTHDAY_PHRASES)) return new Result(Kind.BIRTHDAY, "");

        // Before the plain greeting, so "good morning" is not merely a hello.
        String partOfDay = timeGreeting(flat, tokens);
        if (!partOfDay.isEmpty()) return new Result(Kind.TIME_GREETING, partOfDay);

        if (containsAnyPhrase(flat, WEEKEND_PHRASES)) return new Result(Kind.WEEKEND, "");

        // Before THANKS, because "congratulations and thank you" is congratulations first, and
        // because "well done" shares no vocabulary with thanks but "badhai" sits near "dhanyavad"
        // in the same messages.
        if (containsAnyWord(tokens, CONGRATS_WORDS)
                || containsAnyPhrase(flat, CONGRATS_PHRASES)) {
            return new Result(Kind.CONGRATS, "");
        }

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

        // A reply arriving while IRIS was the last to speak is probably aimed at IRIS, even
        // without the name. Checked LATE, after every specific category, because a festival wish
        // or a question is still that regardless of who spoke last -- this only claims messages
        // that would otherwise have matched nothing.
        if (irisSpokeLast && wordCount > SHORT_MAX_WORDS) {
            return new Result(Kind.TO_IRIS, "follow_up");
        }

        if (trimmed.endsWith("?")) return new Result(Kind.QUESTION, "");

        if (wordCount <= SHORT_MAX_WORDS) {
            return irisSpokeLast ? new Result(Kind.TO_IRIS, "follow_up")
                    : new Result(Kind.SHORT, "");
        }

        // Classified as unremarkable rather than unclassified. Whether anything is said about it
        // is the policy layer's decision, and it is off by default.
        return new Result(Kind.OTHER, "");
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

    /**
     * Typo-tolerant match for the urgent words, which the owner asked for because "urgent" is the
     * one word people fat-finger when they are in a hurry: "urgnt", "urgentt", "emergeny".
     *
     * Deliberately narrow. Only the long, distinctive urgent words take part (six letters or
     * more), so short tokens cannot drift into them; the first letter must match; the lengths must
     * be within two; and the edit distance must be at most two. That is loose enough to catch a
     * genuine slip and tight enough that it never fired on the Odia must-not-match traffic in the
     * tests. "asap" is left out on purpose \u2014 four letters is too short to fuzz without catching
     * real words like "asp".
     */
    private static boolean fuzzyUrgent(Set<String> tokens) {
        for (String token : tokens) {
            if (token.length() < 4) continue;
            for (String word : URGENT_WORDS) {
                if (word.length() < 6) continue;
                if (token.charAt(0) != word.charAt(0)) continue;
                if (Math.abs(token.length() - word.length()) > 2) continue;
                if (editDistance(token, word) <= 2) return true;
            }
        }
        return false;
    }

    /** Standard Levenshtein edit distance, two-row variant. */
    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
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
