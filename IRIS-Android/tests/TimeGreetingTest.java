package com.iris.assistant;

import java.util.Locale;

/** Offline checks for wrong-time greeting detection. */
public final class TimeGreetingTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        t(TimeGreeting.mistimed("morning", "night"), "good morning at night is mistimed");
        t(TimeGreeting.mistimed("morning", "evening"), "good morning in evening is mistimed");
        t(TimeGreeting.mistimed("night", "morning"), "good night in morning is mistimed");
        t(TimeGreeting.mistimed("night", "afternoon"), "good night in afternoon is mistimed");
        t(!TimeGreeting.mistimed("morning", "morning"), "same part of day is fine");
        t(!TimeGreeting.mistimed("evening", "night"), "evening at night is not flagged");
        t(!TimeGreeting.mistimed("afternoon", "evening"), "afternoon in evening is not flagged");
        t(!TimeGreeting.mistimed("", "night"), "empty wished is not mistimed");

        // The reply names IRIS and mentions the actual part of day, in every tone.
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            String r = TimeGreeting.reply("morning", "night", tone).toLowerCase(Locale.ROOT);
            t(r.contains("iris"), "reply names IRIS in " + tone);
            t(r.contains("night"), "reply mentions the real time in " + tone);
        }
        System.out.println("Passed " + checks + " time greeting checks");
    }
}
