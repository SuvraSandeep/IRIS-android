package com.iris.assistant;

/**
 * Offline checks for PhoneHistoryQuery (pure, no Android).
 *
 * The most important assertions here are the NEGATIVE ones: the phrasings already handled by
 * IrisListeningService.HISTORY_PATTERN (outgoing calls, from IRIS's own records) and by the
 * existing notification reader must NOT be claimed by this classifier, or a working command
 * would start routing somewhere else.
 */
public class PhoneHistoryQueryTest {

    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void kind(String phrase, PhoneHistoryQuery.Kind expected) {
        PhoneHistoryQuery q = PhoneHistoryQuery.parse(phrase);
        check(q.kind == expected, "\"" + phrase + "\" expected " + expected + " but got " + q.kind);
    }

    private static void sender(String phrase, String expectedName) {
        PhoneHistoryQuery q = PhoneHistoryQuery.parse(phrase);
        check(q.kind == PhoneHistoryQuery.Kind.SMS_FROM,
                "\"" + phrase + "\" expected SMS_FROM but got " + q.kind);
        check(q.name.equals(expectedName),
                "\"" + phrase + "\" expected sender '" + expectedName + "' but got '" + q.name + "'");
    }

    public static void main(String[] args) {
        incomingCalls();
        missedCalls();
        recentCalls();
        lastSms();
        smsFromSender();
        doesNotStealExistingCommands();
        permissionFlags();
        edgeCases();
        System.out.println("Passed " + checks + " phone-history query checks");
    }

    private static void incomingCalls() {
        kind("who called me", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who called me last", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who just called", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who just called me", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who called", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("Who called me?", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who was that call from", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who rang me", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("who called me just now", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
    }

    private static void missedCalls() {
        kind("any missed calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("did i miss any calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("do i have missed calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("missed calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("missed call", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("my missed calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("are there missed calls", PhoneHistoryQuery.Kind.MISSED_CALLS);
        kind("any missed calls today", PhoneHistoryQuery.Kind.MISSED_CALLS);
    }

    private static void recentCalls() {
        kind("recent calls", PhoneHistoryQuery.Kind.RECENT_CALLS);
        kind("my recent calls", PhoneHistoryQuery.Kind.RECENT_CALLS);
        kind("incoming calls", PhoneHistoryQuery.Kind.RECENT_CALLS);
        kind("who has been calling me", PhoneHistoryQuery.Kind.RECENT_CALLS);
    }

    private static void lastSms() {
        // SMS-inbox reads REQUIRE the word "inbox" so they cannot collide with
        // NOTIFICATION_PATTERN, which owns every "read my messages" phrasing and is dispatched
        // after this class. See the routing note in PhoneHistoryQuery.
        kind("read my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("check my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("open my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("read my sms inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("read my text inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("read my message inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("read me my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("please read my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("Read My Inbox?", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("anything in my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("anything new in my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("is there anything in my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("do i have anything in my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("what's in my inbox", PhoneHistoryQuery.Kind.LAST_SMS);
        kind("whats in my sms inbox", PhoneHistoryQuery.Kind.LAST_SMS);
    }

    private static void smsFromSender() {
        sender("read my inbox from ana", "ana");
        sender("read my inbox for ana", "ana");
        sender("check my inbox from ana maria", "ana maria");
        sender("my inbox from tony", "tony");
        sender("read my sms inbox from dad", "dad");
        sender("anything in my inbox from mom", "mom");
        sender("is there anything in my inbox from mom", "mom");
        sender("what did ana send to my inbox", "ana");
        sender("what did ana send my inbox", "ana");
        sender("read my inbox from ana please", "ana");
    }

    /** Regression guard: existing, working commands must stay unmatched here. */
    private static void doesNotStealExistingCommands() {
        // Outgoing-call history, already answered from IRIS's own records.
        kind("who did i call last", PhoneHistoryQuery.Kind.NONE);
        kind("who did i call", PhoneHistoryQuery.Kind.NONE);
        kind("who did i call today", PhoneHistoryQuery.Kind.NONE);
        kind("how many times did i call tony", PhoneHistoryQuery.Kind.NONE);
        kind("when did i last call tony", PhoneHistoryQuery.Kind.NONE);
        kind("call history", PhoneHistoryQuery.Kind.NONE);
        kind("my calls", PhoneHistoryQuery.Kind.NONE);
        // Notification reading is a separate existing feature.
        kind("read my notifications", PhoneHistoryQuery.Kind.NONE);
        kind("any new notifications", PhoneHistoryQuery.Kind.NONE);
        // REGRESSION GUARD (this was a real bug). IrisListeningService.NOTIFICATION_PATTERN
        // owns all of these and is dispatched AFTER this class, so an earlier version of the
        // SMS patterns silently stole them from the notification handler - which reads captured
        // notifications from every app and needs no READ_SMS. They must stay NONE here.
        kind("read my last text", PhoneHistoryQuery.Kind.NONE);
        kind("read my last message", PhoneHistoryQuery.Kind.NONE);
        kind("read my messages", PhoneHistoryQuery.Kind.NONE);
        kind("read my texts", PhoneHistoryQuery.Kind.NONE);
        kind("read the last sms", PhoneHistoryQuery.Kind.NONE);
        kind("last text", PhoneHistoryQuery.Kind.NONE);
        kind("latest message", PhoneHistoryQuery.Kind.NONE);
        kind("any new messages", PhoneHistoryQuery.Kind.NONE);
        kind("any new texts", PhoneHistoryQuery.Kind.NONE);
        kind("any unread messages", PhoneHistoryQuery.Kind.NONE);
        kind("do i have new messages", PhoneHistoryQuery.Kind.NONE);
        kind("message from john", PhoneHistoryQuery.Kind.NONE);
        kind("messages from mom", PhoneHistoryQuery.Kind.NONE);
        kind("any message from the office", PhoneHistoryQuery.Kind.NONE);
        kind("read the last text from ana", PhoneHistoryQuery.Kind.NONE);
        kind("what did ana text me", PhoneHistoryQuery.Kind.NONE);
        kind("who texted me", PhoneHistoryQuery.Kind.NONE);
        kind("what did i miss", PhoneHistoryQuery.Kind.NONE);
        // Bare nouns must not trigger an SMS read or a permission nag on their own.
        kind("messages", PhoneHistoryQuery.Kind.NONE);
        kind("texts", PhoneHistoryQuery.Kind.NONE);
        kind("sms", PhoneHistoryQuery.Kind.NONE);
        kind("message", PhoneHistoryQuery.Kind.NONE);
        // Sending, calling and unrelated commands.
        kind("call my mother", PhoneHistoryQuery.Kind.NONE);
        kind("text my brother i am late", PhoneHistoryQuery.Kind.NONE);
        kind("send a message to ana", PhoneHistoryQuery.Kind.NONE);
        kind("what time is it", PhoneHistoryQuery.Kind.NONE);
        kind("battery level", PhoneHistoryQuery.Kind.NONE);
        kind("what's the weather", PhoneHistoryQuery.Kind.NONE);
        kind("turn on the flashlight", PhoneHistoryQuery.Kind.NONE);
    }

    private static void permissionFlags() {
        check(PhoneHistoryQuery.parse("who called me").needsCallLog(), "who-called needs call log");
        check(!PhoneHistoryQuery.parse("who called me").needsSms(), "who-called does not need sms");
        check(PhoneHistoryQuery.parse("any missed calls").needsCallLog(), "missed needs call log");
        check(PhoneHistoryQuery.parse("recent calls").needsCallLog(), "recent needs call log");
        check(PhoneHistoryQuery.parse("read my inbox").needsSms(), "last sms needs sms");
        check(!PhoneHistoryQuery.parse("read my inbox").needsCallLog(), "last sms no call log");
        check(PhoneHistoryQuery.parse("read my inbox from ana").needsSms(), "sms-from needs sms");
        check(!PhoneHistoryQuery.parse("what time is it").needsCallLog(), "no-match needs nothing");
        check(!PhoneHistoryQuery.parse("what time is it").needsSms(), "no-match needs nothing");
        check(!PhoneHistoryQuery.parse("what time is it").matched(), "no-match not matched");
    }

    private static void edgeCases() {
        kind(null, PhoneHistoryQuery.Kind.NONE);
        kind("", PhoneHistoryQuery.Kind.NONE);
        kind("   ", PhoneHistoryQuery.Kind.NONE);
        // Extra whitespace and capitals must not change the outcome.
        kind("  WHO   CALLED   ME  ", PhoneHistoryQuery.Kind.WHO_CALLED_ME);
        kind("Any Missed Calls!", PhoneHistoryQuery.Kind.MISSED_CALLS);
        check(PhoneHistoryQuery.parse("who called me").name.isEmpty(), "unnamed query has empty name");
        check(PhoneHistoryQuery.parse("x").toString().equals("NONE"), "toString for no match");
    }
}
