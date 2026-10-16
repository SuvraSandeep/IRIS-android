package com.iris.assistant;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Detects an urgent message that never uses the word "urgent" -- panic punctuation, shouting, a
 * rapid burst of messages, or distress vocabulary. Pure and offline-testable.
 *
 * This complements {@link MessageCategory}'s keyword/fuzzy URGENT detection: that catches "urgent"
 * and its typos, this catches "ACCIDENT PLS CALL" or four messages fired in ten seconds. A score
 * at or above {@link #THRESHOLD} is treated as implicitly urgent.
 *
 * Signals are additive and deliberately conservative, because a false urgent escalates a stranger
 * past the owner's cooldown. Distress words alone clear the bar; shouting or panic punctuation
 * alone do not (teenagers type like that at rest).
 */
public final class UrgencyScore {

    public static final int THRESHOLD = 60;

    /** Burst: this many messages within the window counts as frantic. */
    public static final int BURST_COUNT = 4;
    public static final long BURST_WINDOW_MS = 12_000L;

    /**
     * Hard emergencies: these count even alongside laughing emojis. Someone typing "accident 😂"
     * is vanishingly rare and the cost of a missed real emergency is far higher than a false one.
     */
    private static final List<String> HARD = Arrays.asList(
            "accident", "hospital", "ambulance", "blood", "police", "fire", "emergency",
            "collapsed", "unconscious", "bleeding", "robbed", "fainted", "danger");

    /**
     * Soft distress: real when alone, but routinely used as hyperbole ("that username scared me
     * 😂😂", "I'm stuck lol"). These are suppressed when laugh/meme markers are present.
     */
    private static final List<String> SOFT = Arrays.asList(
            "locked out", "stranded", "lost my", "fever", "scared", "help me", "stuck");

    /** Laugh / meme / "it's a joke" markers that turn soft distress into harmless hyperbole. */
    private static final List<String> LAUGH_MEME = Arrays.asList(
            "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDC80", "\uD83D\uDE06", "\uD83D\uDE05",
            "\uD83E\uDD2D", "lol", "lmao", "lmfao", "rofl", "haha", "hehe",
            "for a second", "for a sec", "meme", "reel", "joke", "kidding", "jk");

    private UrgencyScore() { }

    /** True if a laugh/meme marker is present — soft distress should then be read as hyperbole. */
    public static boolean hasLaughContext(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String m : LAUGH_MEME) if (lower.contains(m)) return true;
        return false;
    }

    /** Convenience: is this message implicitly urgent? */
    public static boolean isUrgent(String text, int recentBurstCount) {
        return score(text, recentBurstCount) >= THRESHOLD;
    }

    /**
     * The urgency score.
     *
     * @param text             the raw incoming message
     * @param recentBurstCount how many messages (including this one) arrived from this sender
     *                         within {@link #BURST_WINDOW_MS}; the caller counts timestamps
     */
    public static int score(String text, int recentBurstCount) {
        if (text == null) return 0;
        int s = 0;
        String lower = text.toLowerCase(Locale.ROOT);

        // Distress vocabulary -- the strongest single signal, enough on its own. Hard emergencies
        // always count; soft distress ("scared", "stuck") is treated as hyperbole and ignored when
        // laughing/meme markers are present, so "the username scared me 😂😂" is NOT an emergency.
        boolean laugh = hasLaughContext(text);
        boolean hit = false;
        for (String w : HARD) {
            if (lower.contains(w)) { hit = true; break; }
        }
        if (!hit && !laugh) {
            for (String w : SOFT) {
                if (lower.contains(w)) { hit = true; break; }
            }
        }
        if (hit) s += 65;

        // A frantic burst of messages.
        if (recentBurstCount >= BURST_COUNT) s += 50;

        // Shouting: mostly capitals, and enough letters that it isn't just "OK".
        int letters = 0, caps = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) caps++;
            }
        }
        if (letters >= 8 && caps * 100 / letters >= 80) s += 35;

        // Panic punctuation.
        if (text.contains("!!!") || text.contains("???")) s += 25;

        return s;
    }
}
