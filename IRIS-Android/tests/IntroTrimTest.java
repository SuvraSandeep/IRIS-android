package com.iris.assistant;

import java.util.Locale;

/** Offline checks for dropping the IRIS intro in a continuing conversation. */
public final class IntroTrimTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Not introduced yet -> unchanged (first reply keeps the intro).
        String first = "I'm IRIS, Suvra's assistant \u2014 he's away right now.";
        t(IntroTrim.trim(first, false, "Suvra").equals(first), "first reply keeps intro");

        // Leading intro removed when already introduced.
        String led = IntroTrim.trim(first, true, "Suvra");
        t(!led.toLowerCase(Locale.ROOT).startsWith("i'm iris"), "leading intro removed");
        t(led.toLowerCase(Locale.ROOT).contains("away"), "content preserved");
        t(Character.isUpperCase(led.charAt(0)), "re-capitalised");

        // Trailing sign-off removed.
        String tail = IntroTrim.trim("Noted \u2014 saved for Suvra. I'm IRIS.", true, "Suvra");
        t(!tail.toLowerCase(Locale.ROOT).endsWith("i'm iris."), "trailing sign-off removed");
        t(tail.toLowerCase(Locale.ROOT).contains("saved for suvra"), "trailing content kept");

        // "IRIS here!" opener removed.
        String here = IntroTrim.trim("IRIS here! Your message is saved.", true, "Suvra");
        t(here.toLowerCase(Locale.ROOT).startsWith("your message"), "IRIS-here opener removed");

        // Never returns empty: a reply that is ONLY the intro falls back to the original.
        String only = "I'm IRIS, Suvra's assistant.";
        t(!IntroTrim.trim(only, true, "Suvra").isEmpty(), "intro-only reply not emptied");

        // Sender-knows-iris detector.
        t(IntroTrim.senderKnowsIris("hey iris you there"), "detects iris in sender text");
        t(!IntroTrim.senderKnowsIris("hello there"), "no false iris detection");

        // Cold "can't come to the phone" opener is stripped once introduced.
        String cold = IntroTrim.trim(
                "Hi. Suvra can't come to the phone, so you've got IRIS, his assistant \u2014 "
                        + "message noted.", true, "Suvra");
        t(!cold.toLowerCase(Locale.ROOT).contains("can't come to the phone"),
                "cold opener removed");
        t(cold.toLowerCase(Locale.ROOT).contains("message noted"), "cold opener content kept");

        // Middle clause tests
        String mid1 = IntroTrim.trim(
                "I can't move money (no UPI access here!) \u2014 I'm IRIS, Suvra's assistant. "
                        + "I've flagged it for Suvra.", true, "Suvra");
        t(!mid1.toLowerCase(Locale.ROOT).contains("iris"), "middle dash-period intro removed");
        t(mid1.toLowerCase(Locale.ROOT).contains("flagged it for suvra"), "middle clause keeps content");

        String mid2 = IntroTrim.trim(
                "No UPI here \u2014 I'm IRIS, Suvra's assistant \u2014 so I've flagged it for Suvra.",
                true, "Suvra");
        t(!mid2.toLowerCase(Locale.ROOT).contains("iris"), "middle dash-dash intro removed");

        System.out.println("Passed " + checks + " intro trim checks");
    }
}
