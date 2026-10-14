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
