package com.iris.assistant;

import java.util.HashMap;
import java.util.Map;

/** Offline checks for opt-in, privacy-gated profile sharing. */
public final class ProfileShareTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Question -> field mapping.
        check(ProfileShare.fieldFor("what does he do").equals("role"), "what does he do -> role");
        check(ProfileShare.fieldFor("where does he work").equals("company"),
                "where does he work -> company");
        check(ProfileShare.fieldFor("which city is he in").equals("city"), "which city -> city");
        check(ProfileShare.fieldFor("what tech does he use").equals("skills"),
                "what tech -> skills");
        check(ProfileShare.fieldFor("where did he study").equals("education"),
                "where did he study -> education");
        check(ProfileShare.fieldFor("whats his email").equals("email"), "email question -> email");
        check(ProfileShare.fieldFor("his full name").equals("name"), "full name -> name");
        check(ProfileShare.fieldFor("what does he do for fun").equals("hobby"),
                "for fun -> hobby, not role");
        check(ProfileShare.fieldFor("what languages does he speak").equals("languages"),
                "languages -> languages");
        check(ProfileShare.fieldFor("his certifications").equals("certs"), "certs -> certs");
        check(ProfileShare.fieldFor("his five year plan").equals("plan"), "plan -> plan");
        // Second person ("you") works too, because people text Suvra directly.
        check(ProfileShare.fieldFor("what do you do").equals("role"), "what do you do -> role");
        check(ProfileShare.fieldFor("where do you work").equals("company"),
                "where do you work -> company");
        check(ProfileShare.fieldFor("whats your email").equals("email"), "your email -> email");
        check(ProfileShare.fieldFor("what do you do for fun").equals("hobby"),
                "you + for fun -> hobby");
        check(ProfileShare.fieldFor("where did you study").equals("education"),
                "where did you study -> education");
        // Genuinely bot-directed questions are NOT profile fields (IRIS identity handles them).
        check(ProfileShare.fieldFor("what can you do").isEmpty(),
                "what CAN you do is a bot question, not a profile field");
        check(ProfileShare.fieldFor("who are you").isEmpty(), "who are you is a bot question");
        check(ProfileShare.fieldFor("whats your name").isEmpty(),
                "whats your name is left to IRIS identity");
        check(ProfileShare.fieldFor("where does he live").isEmpty(),
                "where does he LIVE maps to nothing (address is never shareable)");
        check(ProfileShare.fieldFor("what is his number").isEmpty(),
                "his number maps to nothing");
        check(ProfileShare.fieldFor("the weather is nice").isEmpty(), "unrelated -> nothing");

        Map<String, String> share = new HashMap<>();
        // Nothing shared -> nothing disclosed.
        check(ProfileShare.reply("role", share, "Suvra").isEmpty(),
                "no share value -> no disclosure");

        share.put("role", "a telecom engineer");
        share.put("company", "Ericsson");
        share.put("city", "Bengaluru");
        check(ProfileShare.reply("role", share, "Suvra").contains("telecom engineer"),
                "role disclosed when shared");
        check(ProfileShare.reply("role", share, "Suvra").contains("IRIS"), "names IRIS");
        check(ProfileShare.reply("company", share, "Suvra").contains("Ericsson"),
                "company disclosed when shared");
        check(ProfileShare.reply("city", share, "Suvra").contains("Bengaluru"),
                "city disclosed when shared");
        share.put("skills", "Python, Kubernetes");
        share.put("education", "B.Tech from IGIT");
        check(ProfileShare.reply("skills", share, "Suvra").contains("Python"),
                "skills disclosed when shared");
        check(ProfileShare.reply("education", share, "Suvra").contains("B.Tech"),
                "education disclosed when shared");
        share.put("email", "suvra@example.com");
        share.put("name", "Suvra");
        share.put("hobby", "collapsing pyramid schemes mathematically");
        check(ProfileShare.reply("email", share, "Suvra").contains("suvra@example.com"),
                "email disclosed when shared (owner opted in)");
        check(ProfileShare.reply("name", share, "Suvra").contains("Suvra"), "name disclosed");
        check(ProfileShare.reply("hobby", share, "Suvra").contains("pyramid"), "hobby disclosed");
        check(ProfileShare.reply("email", share, "Suvra").contains("IRIS"), "email reply names IRIS");

        // Even if a sensitive field is put under share, no question maps to it, so it is never
        // produced; and reply() only ever emits role/company/city.
        share.put("phone", "9876543210");
        check(ProfileShare.reply("phone", share, "Suvra").isEmpty(),
                "a phone value is never produced by reply()");
        check(ProfileShare.fieldFor("what is his phone").isEmpty(),
                "no question maps to phone");

        System.out.println("Passed " + checks + " profile share checks");
    }
}
