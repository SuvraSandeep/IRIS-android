package com.iris.assistant;

import java.util.Map;

/**
 * Answers "about the owner" questions from his dedicated share file (iris-share.json), in IRIS's
 * own dry, witty voice. Pure and offline-testable (works over a plain map; ShareProfile builds the
 * map from the file).
 *
 * The owner chooses what is shareable by what he puts in iris-share.json \u2014 role, company, city,
 * skills, education, email, blood group, languages, certifications, past role, plans, projects,
 * hobbies and the name he goes by. Nothing is answered unless he put a value for it. A couple of
 * fields he deliberately opted into (email, blood group, name) are shared because he asked;
 * phone numbers and home address are still never answered, because no question maps to them and
 * they aren't in the file.
 */
public final class ProfileShare {

    private ProfileShare() { }

    /**
     * The shareable field a question asks about, or "". Checked specific-first.
     *
     * Handles BOTH third person ("what does he do") and second person ("what do you do"), because
     * someone texting Suvra's number naturally addresses him as "you". Genuinely bot-directed
     * questions ("who are you", "what can you do", "are you a bot", "what's your name") are left
     * out on purpose, so those still get IRIS's own identity answer rather than Suvra's profile.
     */
    public static String fieldFor(String body) {
        if (body == null) return "";
        String f = " " + MessageCategory.normalise(body) + " ";
        if (any(f, "his email", "your email", "email id", "his mail", "your mail", "mail id",
                "email address", "whats his email", "whats your email", "what is his email",
                "what is your email")) return "email";
        if (any(f, "blood group", "his blood", "your blood", "blood type")) return "blood";
        if (any(f, "what languages", "which languages", "languages does he speak",
                "languages do you speak", "do you speak", "does he speak")) return "languages";
        if (any(f, "his certifications", "your certifications", "what certs", "his credentials",
                "your credentials", "is he certified", "are you certified", "certifications"))
            return "certs";
        if (any(f, "what does he do for fun", "what do you do for fun", "his hobbies",
                "your hobbies", "his hobby", "your hobby", "free time", "his interests",
                "your interests", "rabbit hole")) return "hobby";
        if (any(f, "his past", "your past", "previous job", "what did he do before",
                "what did you do before", "past role", "earlier job")) return "past";
        if (any(f, "five year plan", "his plan", "your plan", "his plans", "your plans",
                "future plan", "his goals", "your goals")) return "plan";
        if (any(f, "his projects", "your projects", "what has he built", "what have you built",
                "cool project", "what did he build", "what did you build")) return "project";
        if (any(f, "where did he study", "where did you study", "what did he study",
                "what did you study", "his education", "your education", "which college",
                "his degree", "your degree", "where did he graduate", "where did you graduate"))
            return "education";
        if (any(f, "what tech", "his skills", "your skills", "what does he know",
                "what do you know", "tech stack", "what does he code", "what do you code",
                "what does he work with", "what do you work with", "what tools")) return "skills";
        if (any(f, "where does he work", "where do you work", "which company", "what company",
                "his company", "your company", "which organisation", "which organization",
                "where is he employed", "where are you employed", "who does he work for",
                "who do you work for")) return "company";
        if (any(f, "which city", "what city", "where is he based", "where are you based",
                "which city is he in", "which city are you in", "which city do you"))
            return "city";
        if (any(f, "his full name", "your full name", "what is his name", "what is your full name"))
            return "name";
        if (any(f, "what does he do", "what do you do", "what is his job", "what is your job",
                "his job", "your job", "his profession", "your profession", "what does he work as",
                "what do you work as", "what is his role", "what is your role",
                "what work does he do", "what work do you do", "what does he do for a living",
                "what do you do for a living")) return "role";
        if (any(f, "tell me about him", "about him", "who is he")) return "about";
        return "";
    }

    /** A witty reply built from the owner's shared value, or empty when he hasn't shared it. */
    public static String reply(String field, Map<String, String> share, String owner) {
        if (field == null || field.isEmpty() || share == null) return "";
        String v = share.get(field);
        if (v == null || v.trim().isEmpty()) return "";
        v = v.trim();
        String o = owner == null ? "" : owner;
        switch (field) {
            case "role":
                return "He's a " + v + ", apparently \u2014 I just file the messages. I'm IRIS, " + o
                        + "'s assistant.";
            case "company":
                return "He's at " + v + ". No, I can't get you free data \u2014 I've asked. I'm IRIS, "
                        + o + "'s assistant.";
            case "city":
                return "Based in " + v + ". I live in a server, so no commute for me. I'm IRIS, "
                        + o + "'s assistant.";
            case "skills":
                return "He wrangles " + v + " \u2014 and I just press the buttons he coded. I'm IRIS, "
                        + o + "'s assistant.";
            case "education":
                return "He survived " + v + ". I'm IRIS, " + o + "'s assistant \u2014 I skipped school "
                        + "and went straight to being smart.";
            case "email":
                return "His email is " + v + " \u2014 spam it at your own risk. I'm IRIS, " + o + "'s "
                        + "assistant.";
            case "blood":
                return "Blood group " + v + " \u2014 generous in theory, selective in practice. I'm "
                        + "IRIS, " + o + "'s assistant, and I run on electricity, thanks.";
            case "languages":
                return "He speaks " + v + " \u2014 and nods politely through the rest. I'm IRIS, " + o
                        + "'s assistant.";
            case "certs":
                return "He's got " + v + " \u2014 the alphabet soup that lets him boss robots like me "
                        + "around. I'm IRIS, " + o + "'s assistant.";
            case "about":
                return v + " \u2014 his words, not mine. I'm IRIS, " + o + "'s assistant.";
            case "past":
                return "Previously: " + v + ". I'm IRIS, " + o + "'s assistant \u2014 I only remember "
                        + "what he commits to git.";
            case "plan":
                return "The five-year plan is " + v + ". Bold. I'm IRIS, " + o + "'s assistant.";
            case "project":
                return "He once built this: " + v + " \u2014 peak laziness as a service. I'm IRIS, "
                        + o + "'s assistant, and arguably his finest work.";
            case "hobby":
                return "In his spare time: " + v + ". I don't judge. I'm IRIS, " + o + "'s "
                        + "assistant.";
            case "name":
                return "He goes by " + v + " \u2014 spell it right or the code won't compile. I'm "
                        + "IRIS, his assistant.";
            default:
                return "";
        }
    }

    private static boolean any(String paddedFlat, String... phrases) {
        for (String p : phrases) {
            if (paddedFlat.contains(" " + p + " ")) return true;
        }
        return false;
    }
}
