package com.iris.assistant;

import java.util.*;

/**
 * Phase 1 regression checks for the personal-vocabulary repair.
 * Syntax/logic only — the SharedPreferences store still needs on-device testing.
 */
public final class PersonalVocabularyTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static Map<String, List<String>> names(String canonical, String... variants) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(canonical, Arrays.asList(variants));
        return m;
    }

    private static void repair(String input, Map<String, String> corrections,
                               Map<String, List<String>> names, String expected) {
        String got = SpeechText.personalRepair(input, corrections, names);
        check(expected.equals(got), "\"" + input + "\" -> \"" + got + "\" (expected \"" + expected + "\")");
    }

    public static void main(String[] args) {
        Map<String, String> none = Collections.emptyMap();
        Map<String, List<String>> noNames = Collections.emptyMap();

        // ── nothing learned: text must pass through untouched ──
        repair("call maa", none, noNames, "call maa");
        repair("", none, noNames, "");
        check(SpeechText.personalRepair(null, none, noNames) == null, "null in -> null out");

        // ── whole-utterance corrections ──
        Map<String, String> corr = new LinkedHashMap<>();
        corr.put("call somojit", "call Soumyajit");
        repair("call somojit", corr, noNames, "call Soumyajit");
        repair("Call Somojit", corr, noNames, "call Soumyajit");       // case-insensitive key
        repair("call somojit.", corr, noNames, "call Soumyajit");      // punctuation ignored
        repair("call somojit now", corr, noNames, "call somojit now"); // NOT a whole-utterance match

        // ── name variants inside the command head ──
        Map<String, List<String>> soumya = names("Soumyajit", "somojit", "soumya jeet");
        repair("call somojit", none, soumya, "call Soumyajit");
        repair("phone somojit", none, soumya, "phone Soumyajit");
        repair("call soumya jeet", none, soumya, "call Soumyajit");    // longest variant wins

        // ── a dictated message must never be rewritten ──
        repair("text maa saying somojit is coming", none, soumya,
               "text maa saying somojit is coming");
        Map<String, List<String>> maa = names("Maa", "ma");
        repair("text ma saying i am on my way ma", none, maa,
               "text Maa saying i am on my way ma");                   // head fixed, message intact

        // ── learned variants may never hijack a command word ──
        Map<String, List<String>> evil = names("Ringo", "ring");
        repair("ring maa", none, evil, "ring maa");
        Map<String, List<String>> evil2 = names("Screenshot Guy", "screenshot");
        repair("take a screenshot", none, evil2, "take a screenshot");

        // ── word-boundary safety: no substring damage ──
        Map<String, List<String>> ann = names("Ann", "an");
        repair("open another app", none, ann, "open another app");     // "an" inside "another"

        // ── corrections take precedence over variants ──
        Map<String, String> corr2 = new LinkedHashMap<>();
        corr2.put("call somojit", "call Dad");
        repair("call somojit", corr2, soumya, "call Dad");

        // ── normalizeLoose ──
        check("call maa".equals(SpeechText.normalizeLoose("  Call,  Maa!  ")), "normalizeLoose collapse");
        check("i'm late".equals(SpeechText.normalizeLoose("I\u2019m late")), "normalizeLoose curly apostrophe");

        System.out.println("Passed " + checks + " personal-vocabulary checks.");
    }
}
