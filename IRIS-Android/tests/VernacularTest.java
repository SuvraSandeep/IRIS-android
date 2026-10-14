package com.iris.assistant;

import java.util.Locale;

/** Offline checks for the Hinglish/Odia layer, including the real-Odia must-not-match corpus. */
public final class VernacularTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    private static boolean names(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("iris");
    }

    public static void main(String[] args) {
        // Positives: recognised and the reply names IRIS.
        String[] recognised = {
                "kemiti achu", "kaisa hai bhai", "kya chal raha hai", "pranam", "bhai sun na",
                "kiye kahucha", "achu ki", "hau", "dhanyabad", "chalta hu",
                "kaha hai", "kouthi achu", "kya kar raha hai", "kana karuchu", "busy achu ki",
                "ghare achu", "office re achu", "kebe asibu", "pahanch gaya ki", "bahar aa"};
        for (String m : recognised) {
            String r = Vernacular.match(m, ReplyTone.Tone.PROFESSIONAL);
            check(!r.isEmpty(), "recognised: " + m);
            check(names(r), "names IRIS: " + m);
        }

        // Repeated-letter habit normalises.
        check(!Vernacular.match("kemiti achhhu", ReplyTone.Tone.CASUAL).isEmpty(),
                "achhhu squeezes to achu");

        // CRITICAL must-not-match: real Odia the owner received that must stay silent.
        String[] mustNotMatch = {
                "Mu bujhiparuni kichi bhi",
                "Bhala laguni",
                "Besi bhabuchu boliki amiti laguchi ki",
                "Jaha heba dekha jiba kii",
                "Mu mora faeda dekhibii",
                "Time nahi sahi chal raha shapoorjee ka"};
        for (String m : mustNotMatch) {
            check(Vernacular.match(m, ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                    "must NOT match real Odia: " + m);
        }

        // Ordinary English is not claimed here (handled by other layers/categories).
        check(Vernacular.match("the garden looks lovely", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "ordinary English is not vernacular");
        check(Vernacular.match("", ReplyTone.Tone.PROFESSIONAL).isEmpty(), "empty");
        check(Vernacular.match(null, ReplyTone.Tone.PROFESSIONAL).isEmpty(), "null");

        System.out.println("Passed " + checks + " vernacular checks");
    }
}
