package com.iris.assistant;

import java.util.regex.Pattern;

/**
 * Detects recipient opt-out or silence commands ("stop", "shut up", "chup", "thamo") across
 * English, Hindi/Hinglish, Odia, and Bengali.
 *
 * Pure and offline-testable with zero Android dependencies.
 *
 * When an incoming message matches, IRIS mutes the conversation for 24 hours, sends a single
 * polite acknowledgement, and refuses all subsequent messages with RECIPIENT_MUTED.
 */
public final class OptOutDetector {

    private OptOutDetector() { }

    // Standalone English stop/please stop commands
    private static final Pattern STANDALONE_STOP = Pattern.compile(
            "(?i)^\\s*(?:please\\s+)?stop[!.]*\\s*$");

    // English multi-word opt-out requests
    private static final Pattern ENGLISH_OPT_OUT = Pattern.compile(
            "(?i)\\b("
            + "(?:please\\s+)?stop\\s+(?:replying|messaging|texting|it|bot|talking)|"
            + "shut\\s*up|"
            + "don'?t\\s*(?:reply|text|message)(?:\\s*me)?(?:\\s*bot)?|"
            + "mute(?:\\s*(?:bot|chat|this|replies))?|"
            + "quiet(?:\\s*bot)?|"
            + "leave\\s*me\\s*alone|"
            + "stfu|"
            + "silence(?:\\s*bot)?"
            + ")\\b");

    // Negative guards to prevent false positives on phrases like "bus stop" or "can't stop"
    private static final Pattern OPT_OUT_NEGATIVES = Pattern.compile(
            "(?i)\\b("
            + "bus\\s*stop|full\\s*stop|non\\s*stop|non-stop|"
            + "can'?t\\s*stop|cannot\\s*stop|don'?t\\s*stop|never\\s*stop|"
            + "pit\\s*stop|one\\s*stop|truck\\s*stop"
            + ")\\b");

    // Hindi / Hinglish opt-out commands
    private static final Pattern HINDI_OPT_OUT = Pattern.compile(
            "(?i)\\b("
            + "chup(?:\\s*(?:kar|karo|raho|ho\\s*ja|baitho|chap))?|"
            + "mat\\s*bolo|"
            + "(?:reply|message|text|baat)\\s*mat\\s*(?:karo|kar|de|dena)|"
            + "(?:bakwas\\s*)?band\\s*(?:karo|kar)|"
            + "shant\\s*(?:raho|ho\\s*ja)|"
            + "rok\\s*do"
            + ")\\b");

    // Odia opt-out commands
    private static final Pattern ODIA_OPT_OUT = Pattern.compile(
            "(?i)\\b("
            + "chup\\s*(?:ruha|raha)|"
            + "katha\\s*(?:huani|kahani|kuhani)|"
            + "reply\\s*(?:karani|diani)|"
            + "band\\s*kara|banda\\s*kara|"
            + "(?:text|message)\\s*(?:karani|pathaani)|"
            + "shanta\\s*(?:ruha|huantu)|"
            + "(?:aar|au)\\s*(?:reply\\s*diani|katha\\s*kahani|katha\\s*kuhani)"
            + ")\\b");

    // Bengali opt-out commands
    private static final Pattern BENGALI_OPT_OUT = Pattern.compile(
            "(?i)\\b("
            + "chup\\s*(?:thak|thako|korun|koro)|"
            + "thamo|thamun|"
            + "(?:ar|aar)\\s*(?:reply|text|message)\\s*(?:diyo\\s*na|dio\\s*na|deben\\s*na|korio\\s*na|korben\\s*na|koro\\s*na)|"
            + "(?:katha|kotha)\\s*(?:bolo\\s*na|bolben\\s*na)|"
            + "(?:bandho|bondho)\\s*koro|"
            + "reply\\s*korar\\s*dorkar\\s*nei|"
            + "(?:katha|kotha)\\s*bolte\\s*chai\\s*na"
            + ")\\b");

    /**
     * Returns true if the message is an opt-out command in English, Hindi, Odia, or Bengali.
     */
    public static boolean isOptOut(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String clean = text.trim();
        if (OPT_OUT_NEGATIVES.matcher(clean).find()) {
            return false;
        }
        return STANDALONE_STOP.matcher(clean).find()
                || ENGLISH_OPT_OUT.matcher(clean).find()
                || HINDI_OPT_OUT.matcher(clean).find()
                || ODIA_OPT_OUT.matcher(clean).find()
                || BENGALI_OPT_OUT.matcher(clean).find();
    }

    /**
     * Standard polite confirmation sent once when a recipient requests silence.
     */
    public static String confirmationReply(String owner) {
        String name = (owner == null || owner.trim().isEmpty()) ? ReplyTemplates.OWNER : owner.trim();
        return "Understood. I've muted auto-replies for this chat. " + name
                + " will see your messages when he's back.";
    }
}
