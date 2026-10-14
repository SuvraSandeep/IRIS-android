package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the triage digest. */
public final class NotificationTriageTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    private static NotificationTriage.Item it(String app, String sender, String text, String pkg) {
        return new NotificationTriage.Item(app, sender, text, pkg);
    }

    public static void main(String[] args) {
        check(NotificationTriage.summarize(null).equals("Nothing waiting."), "null -> nothing");
        check(NotificationTriage.summarize(new ArrayList<>()).equals("Nothing waiting."),
                "empty -> nothing");

        List<NotificationTriage.Item> list = new ArrayList<>();
        list.add(it("WhatsApp", "Mom", "where are you", "com.whatsapp"));
        list.add(it("WhatsApp", "Mom", "call when free", "com.whatsapp"));
        list.add(it("Messages", "Boss", "need this right now, it's an emergency", "com.google.android.apps.messaging"));
        list.add(it("Gmail", "", "Your statement is ready", "com.google.android.gm"));
        list.add(it("Messages", "VM-HDFCBK", "Your OTP is 4455", "com.google.android.apps.messaging"));
        String s = NotificationTriage.summarize(list);

        check(s.startsWith("5 notifications."), "counts all five: " + s);
        check(s.contains("urgent") && s.contains("Boss"), "flags Boss as urgent: " + s);
        check(s.contains("messages from") && s.contains("people"), "clusters people: " + s);
        check(s.contains("most from Mom (2)"), "Mom is the busiest sender: " + s);
        // Gmail (no sender) and the HDFC shortcode are app alerts, not people.
        check(s.contains("app alert"), "non-person notifications are app alerts: " + s);
        check(!s.contains("VM-HDFCBK"), "an SMS shortcode is never counted as a person: " + s);

        // No people -> app grouping fallback.
        List<NotificationTriage.Item> appsOnly = new ArrayList<>();
        appsOnly.add(it("Gmail", "", "newsletter", "com.google.android.gm"));
        appsOnly.add(it("Gmail", "", "promo", "com.google.android.gm"));
        appsOnly.add(it("Play", "", "update available", "com.android.vending"));
        String a = NotificationTriage.summarize(appsOnly);
        check(a.contains("2 from Gmail"), "app grouping fallback: " + a);

        System.out.println("Passed " + checks + " triage checks");
    }
}
