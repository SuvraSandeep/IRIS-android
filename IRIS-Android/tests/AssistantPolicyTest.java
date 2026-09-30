package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline checks for the three pure policy classes added with the roadmap batch:
 * CallTiming, NotificationSummary and ProactiveSuggestion. No Android, no Context.
 */
public class AssistantPolicyTest {

    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void eq(String actual, String expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError(what + " -> expected '" + expected + "' got '" + actual + "'");
        }
    }

    public static void main(String[] args) {
        callTimingWindow();
        callTimingWarnings();
        summaryGrouping();
        summaryPhrasing();
        summaryRequestPhrases();
        suggestionPriority();
        suggestionRestraint();
        System.out.println("Passed " + checks + " assistant policy checks");
    }

    // ── CallTiming ──

    private static void callTimingWindow() {
        // Daytime is fine.
        for (int h = 8; h <= 21; h++) {
            check(CallTiming.advise(h) == CallTiming.Advice.FINE, "hour " + h + " should be FINE");
            check(!CallTiming.isQuietHour(h), "hour " + h + " not quiet");
        }
        // Late evening.
        check(CallTiming.advise(22) == CallTiming.Advice.LATE_NIGHT, "22 late night");
        check(CallTiming.advise(23) == CallTiming.Advice.LATE_NIGHT, "23 late night");
        // After midnight still reads as night.
        check(CallTiming.advise(0) == CallTiming.Advice.LATE_NIGHT, "midnight late night");
        check(CallTiming.advise(3) == CallTiming.Advice.LATE_NIGHT, "3am late night");
        check(CallTiming.advise(4) == CallTiming.Advice.LATE_NIGHT, "4am late night");
        // Tail of the window is early morning.
        check(CallTiming.advise(5) == CallTiming.Advice.EARLY_MORNING, "5am early");
        check(CallTiming.advise(7) == CallTiming.Advice.EARLY_MORNING, "7am early");
        // Boundaries.
        check(CallTiming.advise(8) == CallTiming.Advice.FINE, "8am fine");
        check(CallTiming.isQuietHour(22) && CallTiming.isQuietHour(3), "quiet hours");
        // Bad input must never warn (a bad clock must not break calling).
        check(CallTiming.advise(-1) == CallTiming.Advice.FINE, "negative hour FINE");
        check(CallTiming.advise(24) == CallTiming.Advice.FINE, "hour 24 FINE");
        check(CallTiming.advise(99) == CallTiming.Advice.FINE, "hour 99 FINE");
        // Custom window.
        check(CallTiming.advise(20, 20, 9) == CallTiming.Advice.LATE_NIGHT, "custom night from 20");
        check(CallTiming.advise(19, 20, 9) == CallTiming.Advice.FINE, "custom 19 fine");
        check(CallTiming.advise(8, 20, 9) == CallTiming.Advice.EARLY_MORNING, "custom 8 early");
        // A degenerate window is treated as disabled rather than warning on everything.
        check(CallTiming.advise(3, 10, 10) == CallTiming.Advice.FINE, "degenerate window disabled");
        check(CallTiming.advise(3, -5, 8) == CallTiming.Advice.FINE, "invalid window disabled");
    }

    private static void callTimingWarnings() {
        check(CallTiming.warning(CallTiming.Advice.FINE, "Ana") == null, "no warning when fine");
        check(CallTiming.warning(null, "Ana") == null, "no warning for null advice");
        String late = CallTiming.warning(CallTiming.Advice.LATE_NIGHT, "Ana");
        check(late != null && late.contains("Ana") && late.contains("asleep"), "late warning names Ana");
        String early = CallTiming.warning(CallTiming.Advice.EARLY_MORNING, "Ana");
        check(early != null && early.contains("early"), "early warning");
        String noName = CallTiming.warning(CallTiming.Advice.LATE_NIGHT, "  ");
        check(noName != null && noName.contains("them"), "blank name falls back to them");
        check(CallTiming.warningFor(23, "Bob") != null, "warningFor late");
        check(CallTiming.warningFor(12, "Bob") == null, "warningFor midday");
    }

    // ── NotificationSummary ──

    private static List<NotificationSummary.Entry> entries(String... appSenderPairs) {
        List<NotificationSummary.Entry> list = new ArrayList<>();
        for (int i = 0; i + 1 < appSenderPairs.length; i += 2) {
            list.add(new NotificationSummary.Entry(appSenderPairs[i], appSenderPairs[i + 1]));
        }
        return list;
    }

    private static void summaryGrouping() {
        eq(NotificationSummary.summarize(null), "Nothing waiting.", "null list");
        eq(NotificationSummary.summarize(entries()), "Nothing waiting.", "empty list");

        String one = NotificationSummary.summarize(entries("WhatsApp", "Ana"));
        check(one.startsWith("1 notification:"), "singular noun: " + one);
        check(one.contains("1 from WhatsApp"), "names the app: " + one);
        // A single item must not claim a dominant sender.
        check(!one.contains("Mostly from"), "no dominant sender for one item: " + one);

        String two = NotificationSummary.summarize(entries("WhatsApp", "Ana", "Gmail", "Bob"));
        check(two.startsWith("2 notifications:"), "plural noun: " + two);

        // Busiest app first regardless of arrival order.
        String many = NotificationSummary.summarize(entries(
                "Gmail", "Bob", "WhatsApp", "Ana", "WhatsApp", "Ana", "WhatsApp", "Ana"));
        check(many.startsWith("4 notifications:"), "total: " + many);
        check(many.indexOf("WhatsApp") < many.indexOf("Gmail"), "busiest app first: " + many);
        check(many.contains("3 from WhatsApp"), "counts per app: " + many);
    }

    private static void summaryPhrasing() {
        // Dominant sender: 3 of 4 from Ana is more than half.
        String dom = NotificationSummary.summarize(entries(
                "WhatsApp", "Ana", "WhatsApp", "Ana", "WhatsApp", "Ana", "Gmail", "Bob"));
        check(dom.contains("Mostly from Ana."), "dominant sender: " + dom);
        // Exactly half is NOT dominant.
        String half = NotificationSummary.summarize(entries(
                "WhatsApp", "Ana", "WhatsApp", "Ana", "Gmail", "Bob", "Gmail", "Carl"));
        check(!half.contains("Mostly from"), "half is not dominant: " + half);
        // Missing app label still produces a readable line.
        String blank = NotificationSummary.summarize(entries("", "Ana"));
        check(blank.contains("another app"), "blank app label: " + blank);
        // More than four distinct apps collapses the tail.
        String tail = NotificationSummary.summarize(entries(
                "A", "1", "B", "2", "C", "3", "D", "4", "E", "5", "F", "6"));
        check(tail.contains("other app"), "collapses tail: " + tail);
        check(tail.startsWith("6 notifications:"), "tail total: " + tail);
    }

    private static void summaryRequestPhrases() {
        check(NotificationSummary.isSummaryRequest("summarize my notifications"), "summarize");
        check(NotificationSummary.isSummaryRequest("summarise my notifications"), "summarise");
        check(NotificationSummary.isSummaryRequest("notification summary"), "notification summary");
        check(NotificationSummary.isSummaryRequest("give me a summary of my notifications"), "give me");
        check(NotificationSummary.isSummaryRequest("what have i missed"), "what have i missed");
        check(NotificationSummary.isSummaryRequest("what did i miss"), "what did i miss");
        check(NotificationSummary.isSummaryRequest("Notification Recap?"), "recap + case");
        check(NotificationSummary.isSummaryRequest("what's waiting"), "whats waiting");
        // Must NOT claim the existing full read-out command.
        check(!NotificationSummary.isSummaryRequest("read my notifications"), "read is not summary");
        check(!NotificationSummary.isSummaryRequest("read my last text"), "sms is not summary");
        check(!NotificationSummary.isSummaryRequest("who called me"), "calls not summary");
        check(!NotificationSummary.isSummaryRequest(null), "null not summary");
        check(!NotificationSummary.isSummaryRequest("what time is it"), "time not summary");
    }

    // ── ProactiveSuggestion ──

    private static void suggestionPriority() {
        ProactiveSuggestion.Context c = new ProactiveSuggestion.Context();
        c.hourOfDay = 14;
        c.minutesSinceLast = -1;
        c.missedCalls = 2;
        c.pendingNotifications = 20;
        c.batteryPercent = 5;
        // Missed calls outrank everything else.
        ProactiveSuggestion s = ProactiveSuggestion.decide(c);
        check(s.kind == ProactiveSuggestion.Kind.MISSED_CALLS, "missed calls win: " + s.kind);
        check(s.text.contains("2 missed calls"), "plural missed: " + s.text);
        check(s.any(), "any() true");

        c.missedCalls = 1;
        check(ProactiveSuggestion.decide(c).text.contains("a missed call"), "singular missed");

        // Then low battery, but only when not charging.
        c.missedCalls = 0;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.LOW_BATTERY, "battery next");
        c.charging = true;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NOTIFICATIONS,
                "charging suppresses battery nudge");

        // Then notifications, and only with a real backlog.
        c.charging = false;
        c.batteryPercent = 80;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NOTIFICATIONS, "notifications");
        c.pendingNotifications = 4;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NONE,
                "small backlog is not news");
    }

    private static void suggestionRestraint() {
        ProactiveSuggestion.Context c = new ProactiveSuggestion.Context();
        c.hourOfDay = 14;
        c.missedCalls = 3;

        // Cooldown.
        c.minutesSinceLast = 10;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NONE, "inside cooldown");
        c.minutesSinceLast = ProactiveSuggestion.COOLDOWN_MINUTES - 1;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NONE, "just inside cooldown");
        c.minutesSinceLast = ProactiveSuggestion.COOLDOWN_MINUTES;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.MISSED_CALLS, "cooldown passed");

        // Silent at night even with news.
        c.minutesSinceLast = -1;
        c.hourOfDay = 2;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NONE, "silent at 2am");
        c.hourOfDay = 23;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.NONE, "silent at 23");
        c.hourOfDay = 9;
        check(ProactiveSuggestion.decide(c).kind == ProactiveSuggestion.Kind.MISSED_CALLS, "speaks at 9am");

        // Nothing to say.
        ProactiveSuggestion.Context quiet = new ProactiveSuggestion.Context();
        quiet.hourOfDay = 12;
        check(ProactiveSuggestion.decide(quiet).kind == ProactiveSuggestion.Kind.NONE, "no news");
        check(!ProactiveSuggestion.decide(quiet).any(), "any() false");
        check(ProactiveSuggestion.decide(null).kind == ProactiveSuggestion.Kind.NONE, "null context");
        // Unknown hour must not block a genuine suggestion.
        ProactiveSuggestion.Context unknownHour = new ProactiveSuggestion.Context();
        unknownHour.missedCalls = 1;
        check(ProactiveSuggestion.decide(unknownHour).kind == ProactiveSuggestion.Kind.MISSED_CALLS,
                "unknown hour still suggests");
    }
}
