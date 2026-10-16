package com.iris.assistant;

import java.util.Locale;

/** Offline checks for the urgent-flag alert wording. */
public final class FlagAlertTextTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        t(FlagAlertText.title().toLowerCase(Locale.ROOT).contains("urgent"), "title mentions urgent");

        String b = FlagAlertText.body("Anjali", "WhatsApp").replace("{owner}", "Suvra");
        t(b.contains("Anjali"), "body names sender");
        t(b.contains("WhatsApp"), "body names app");
        t(b.contains("Suvra"), "body names owner");
        t(!b.toLowerCase(Locale.ROOT).contains("message body"), "body never leaks content");

        // Null/empty-safe with sensible defaults.
        String d = FlagAlertText.body(null, "").replace("{owner}", "Suvra");
        t(d.contains("Someone"), "defaults sender to Someone");
        t(d.contains("an app"), "defaults app");
        t(d.contains("Suvra"), "default still names owner");

        System.out.println("Passed " + checks + " flag alert text checks");
    }
}
