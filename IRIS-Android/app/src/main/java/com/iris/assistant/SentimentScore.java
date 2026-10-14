package com.iris.assistant;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A light, lexicon-based read of a message's emotional weight. Pure and offline-testable.
 *
 * It is NOT sentiment analysis in any deep sense and makes no claim to understand the message. It
 * exists for one careful purpose: to tell the responder when a message is distressed or angry
 * enough that a sarcastic reply would be wrong. The only behaviour wired to it is tone softening
 * (never be sarcastic to someone who is upset); it deliberately does NOT change the urgent path or
 * cause any new send.
 *
 * Handles negation ("not good"), a few intensifiers, emoji, and the common idioms that must NOT
 * read as distress ("dying laughing", "lol").
 */
public final class SentimentScore {

    public static final class Result {
        public final double valence;   // -1 (negative) .. 1 (positive)
        public final double distress;  // 0 .. 1
        public final double anger;     // 0 .. 1
        public final double urgency;   // 0 .. 1

        Result(double valence, double distress, double anger, double urgency) {
            this.valence = valence;
            this.distress = distress;
            this.anger = anger;
            this.urgency = urgency;
        }

        /** Clearly distressed. */
        public boolean distressed() { return distress >= 0.5; }

        /** Upset enough that a sarcastic reply would be inappropriate. */
        public boolean shouldSoften() {
            return distress >= 0.4 || anger >= 0.4 || valence <= -0.4;
        }
    }

    private static final Set<String> DISTRESS = set(
            "help", "emergency", "accident", "hospital", "ambulance", "sick", "hurt", "injured",
            "scared", "afraid", "crying", "cry", "panic", "panicking", "anxious", "worried",
            "stuck", "stranded", "lost", "dying", "danger", "bleeding", "pain", "trouble",
            "alone", "fainted", "collapsed", "unconscious", "fire", "robbed", "attacked");

    private static final Set<String> ANGER = set(
            "angry", "furious", "mad", "pissed", "hate", "worst", "unacceptable", "ridiculous",
            "stupid", "idiot", "useless", "pathetic", "disgusting", "fed", "annoyed", "irritated",
            "wtf", "wth");

    private static final Set<String> NEGATIVE = set(
            "sad", "bad", "terrible", "awful", "horrible", "unhappy", "upset", "disappointed",
            "miserable", "problem", "issue", "wrong", "fail", "failed", "broken", "worried",
            "sorry", "unwell");

    private static final Set<String> POSITIVE = set(
            "happy", "great", "good", "love", "thanks", "thank", "awesome", "excellent", "glad",
            "wonderful", "nice", "congrats", "congratulations", "yay", "excited", "amazing",
            "perfect", "fine", "ok", "okay", "cool");

    private static final Set<String> INTENSIFIERS = set(
            "very", "so", "really", "extremely", "super", "totally", "absolutely", "deeply");

    private static final Set<String> NEGATORS = set(
            "not", "no", "never", "dont", "cant", "cannot", "wont", "aint");

    /** If any of these appear, distress/anger are treated as banter, not real. */
    private static final Set<String> IDIOM = set(
            "lol", "lmao", "lmfao", "rofl", "haha", "hahaha", "hehe", "joking", "kidding",
            "laughing", "jk");

    private static final java.util.List<Marker> URGENCY_PHRASES = markers(
            "right now", "right away", "immediately", "asap", "as soon as possible",
            "call me now", "need you now", "come quick", "come fast", "hurry");

    private SentimentScore() { }

    public static Result score(String raw) {
        if (raw == null) return new Result(0, 0, 0, 0);
        String flat = MessageCategory.normalise(raw);
        if (flat.isEmpty()) return new Result(0, 0, 0, 0);
        String padded = " " + flat + " ";
        String[] tokens = flat.split(" ");
        Set<String> tokenSet = new LinkedHashSet<>(Arrays.asList(tokens));

        boolean banter = containsAny(tokenSet, IDIOM);

        int distressHits = 0, angerHits = 0;
        int posNet = 0, negNet = 0;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            boolean negated = isNegatedBefore(tokens, i);
            double weight = isIntensifiedBefore(tokens, i) ? 2 : 1;
            if (DISTRESS.contains(t) && !negated) distressHits += weight;
            if (ANGER.contains(t) && !negated) angerHits += weight;
            if (POSITIVE.contains(t)) {
                if (negated) negNet += weight; else posNet += weight;
            }
            if (NEGATIVE.contains(t)) {
                if (negated) posNet += weight; else negNet += weight;
            }
        }

        double emojiDistress = countEmoji(raw, "\uD83D\uDE22\uD83D\uDE2D\uD83D\uDE30\uD83D\uDE28"
                + "\uD83D\uDE31\uD83D\uDE13\uD83D\uDE16");
        double emojiAnger = countEmoji(raw, "\uD83D\uDE20\uD83D\uDE21\uD83E\uDD2C");
        double emojiPos = countEmoji(raw, "\uD83D\uDE0A\uD83D\uDE01\uD83D\uDE0D\uD83D\uDC4D"
                + "\uD83C\uDF89\u2764");

        double distress = banter ? 0 : clamp01(0.6 * distressHits + 0.4 * emojiDistress);
        double anger = banter ? 0 : clamp01(0.5 * angerHits + 0.4 * emojiAnger);

        int urgencyHits = 0;
        for (Marker m : URGENCY_PHRASES) if (padded.contains(" " + m.phrase + " ")) urgencyHits++;
        double urgency = clamp01(0.5 * urgencyHits + 0.3 * distressHits);

        double posTotal = posNet + emojiPos;
        double rawVal = (posTotal - negNet - distressHits - angerHits);
        double denom = Math.max(1, posTotal + negNet + distressHits + angerHits);
        double valence = clamp(rawVal / denom, -1, 1);

        return new Result(valence, distress, anger, urgency);
    }

    private static boolean isNegatedBefore(String[] tokens, int i) {
        for (int j = Math.max(0, i - 2); j < i; j++) {
            if (NEGATORS.contains(tokens[j])) return true;
        }
        return false;
    }

    private static boolean isIntensifiedBefore(String[] tokens, int i) {
        return i > 0 && INTENSIFIERS.contains(tokens[i - 1]);
    }

    private static boolean containsAny(Set<String> tokens, Set<String> candidates) {
        for (String t : tokens) if (candidates.contains(t)) return true;
        return false;
    }

    private static double countEmoji(String raw, String emojis) {
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (emojis.indexOf(raw.charAt(i)) >= 0) n++;
        }
        return n;
    }

    private static double clamp01(double v) { return clamp(v, 0, 1); }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Set<String> set(String... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }

    private static final class Marker { final String phrase; Marker(String p) { phrase = p; } }

    private static java.util.List<Marker> markers(String... phrases) {
        java.util.List<Marker> out = new java.util.ArrayList<>();
        for (String p : phrases) out.add(new Marker(p));
        return out;
    }
}
