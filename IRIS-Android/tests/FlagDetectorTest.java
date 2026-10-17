package com.iris.assistant;

import java.util.Locale;

public final class FlagDetectorTest {

    private static int checks = 0;

    public static void main(String[] args) {
        testOfferReplyAccept();
        testSenderExplicitPin();
        testSenderExplicitFlag();
        testNegationsDoNotFlag();
        testAtmPinDoesNotPin();
        testSmartRepliesFlaggedPhrases();
        testSmartRepliesPinnedPhrases();
        testVernacularFlaggedPhrases();
        testNormalConversationDoesNotFlag();
        testHostileEmergencyDoesNotFlag();
        System.out.println("Passed " + checks + " flag detector checks");
    }

    private static void testOfferReplyAccept() {
        FlagDetector.Result r = FlagDetector.detect("yes please",
                "Done — I've flagged this as high-priority for Suvra.",
                ConversationContext.OfferReply.ACCEPT);
        t(r.isFlag(), "offer reply accept triggers FLAG");
        t(r.reason.toLowerCase(Locale.ROOT).contains("escalation"), "reason mentions escalation");
    }

    private static void testSenderExplicitPin() {
        FlagDetector.Result r1 = FlagDetector.detect("pin this message",
                "Got it!", ConversationContext.OfferReply.NONE);
        t(r1.isPin(), "sender 'pin this message' triggers PIN");

        FlagDetector.Result r2 = FlagDetector.detect("pin my message for Suvra",
                "Saved!", ConversationContext.OfferReply.NONE);
        t(r2.isPin(), "sender 'pin my message' triggers PIN");
    }

    private static void testSenderExplicitFlag() {
        FlagDetector.Result r1 = FlagDetector.detect("urgent please flag this",
                "Got it", ConversationContext.OfferReply.NONE);
        t(r1.isFlag(), "sender 'urgent please flag this' triggers FLAG");

        FlagDetector.Result r2 = FlagDetector.detect("flag as urgent",
                "Understood", ConversationContext.OfferReply.NONE);
        t(r2.isFlag(), "sender 'flag as urgent' triggers FLAG");

        FlagDetector.Result r3 = FlagDetector.detect("flag a message for me",
                "Understood", ConversationContext.OfferReply.NONE);
        t(r3.isFlag(), "sender 'flag a message for me' triggers FLAG");

        FlagDetector.Result r4 = FlagDetector.detect("can you flag this",
                "Understood", ConversationContext.OfferReply.NONE);
        t(r4.isFlag(), "sender 'can you flag this' triggers FLAG");

        t(FlagDetector.isExplicitFlagCommand("flag this"), "isExplicitFlagCommand('flag this')");
        t(FlagDetector.isExplicitFlagCommand("Flag a message"), "isExplicitFlagCommand('Flag a message')");
        t(FlagDetector.isExplicitFlagCommand("flag a message for me"), "isExplicitFlagCommand('flag a message for me')");
        t(!FlagDetector.isExplicitFlagCommand("no don't flag it"), "!isExplicitFlagCommand with negation");
        t(!FlagDetector.isExplicitFlagCommand("flag this bhenchod"), "!isExplicitFlagCommand with profanity");
    }

    private static void testNegationsDoNotFlag() {
        FlagDetector.Result r1 = FlagDetector.detect("no don't flag it",
                "Understood — won't disturb Suvra.", ConversationContext.OfferReply.REFUSE);
        t(!r1.isFlag() && !r1.isPin(), "'no don't flag it' does not trigger flag");

        FlagDetector.Result r2 = FlagDetector.detect("wont flag",
                "Won't disturb Suvra with an urgent alert.", ConversationContext.OfferReply.NONE);
        t(!r2.isFlag() && !r2.isPin(), "'wont flag' does not trigger flag");
    }

    private static void testAtmPinDoesNotPin() {
        FlagDetector.Result r1 = FlagDetector.detect("what is your atm pin",
                "I don't share passwords or screen PINs", ConversationContext.OfferReply.NONE);
        t(!r1.isPin(), "'atm pin' does not trigger PIN");

        FlagDetector.Result r2 = FlagDetector.detect("enter your upi pin",
                "Why would a cloud script know his screen PIN?", ConversationContext.OfferReply.NONE);
        t(!r2.isPin(), "'upi pin' does not trigger PIN");
    }

    private static void testSmartRepliesFlaggedPhrases() {
        FlagDetector.Result r1 = FlagDetector.detect("sent the 500 rs",
                "Flagged the payment note for Suvra. I'm IRIS.", ConversationContext.OfferReply.NONE);
        t(r1.isFlag(), "payment note reply triggers FLAG");

        FlagDetector.Result r2 = FlagDetector.detect("prod is down",
                "Uh oh, prod alert! I've flagged this high-priority for Suvra to jump on. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r2.isFlag(), "prod alert reply triggers FLAG");

        FlagDetector.Result r3 = FlagDetector.detect("need to talk urgently about project",
                "Got it! Flagged this as a priority work ping for Suvra. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r3.isFlag(), "priority work ping triggers FLAG");

        FlagDetector.Result r4 = FlagDetector.detect("he is in hospital",
                "Dropping the snark — health goes straight to the top of Suvra's list. Flagged.",
                ConversationContext.OfferReply.NONE);
        t(r4.isFlag(), "health ping triggers FLAG");

        FlagDetector.Result r5 = FlagDetector.detect("flag this",
                "Done — I've flagged this as high-priority for Suvra. I'm IRIS, his assistant.",
                ConversationContext.OfferReply.NONE);
        t(r5.isFlag(), "flag confirmation reply triggers FLAG");

        FlagDetector.Result r6 = FlagDetector.detect("flag a message",
                "Flagged! It's marked at the top of Suvra's priority queue in his Comms Deck. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r6.isFlag(), "queue flag confirmation triggers FLAG");

        FlagDetector.Result r7 = FlagDetector.detect("flag this for me",
                "Done. I've pinned this to Suvra's flagged dashboard. He will review it when he checks his Comms Deck. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r7.isFlag(), "flagged dashboard confirmation triggers FLAG");
    }

    private static void testSmartRepliesPinnedPhrases() {
        FlagDetector.Result r1 = FlagDetector.detect("where did you pin it",
                "Pinned in Suvra's private dashboard, not WhatsApp's UI. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r1.isPin(), "dashboard pin reply triggers PIN");

        FlagDetector.Result r2 = FlagDetector.detect("check this zomato link",
                "Food link — pinned so he doesn't miss the order",
                ConversationContext.OfferReply.NONE);
        t(r2.isPin(), "food link pinned triggers PIN");
    }

    private static void testVernacularFlaggedPhrases() {
        FlagDetector.Result r1 = FlagDetector.detect("tame kuade gale",
                "No ETA — flagged for Suvra to update you! IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r1.isFlag(), "vernacular ETA reply triggers FLAG");

        FlagDetector.Result r2 = FlagDetector.detect("bhai call uthao",
                "Suvra is away from his phone — flagged your call! IRIS.",
                ConversationContext.OfferReply.NONE);
        t(r2.isFlag(), "missed call reply triggers FLAG");
    }

    private static void testNormalConversationDoesNotFlag() {
        FlagDetector.Result r = FlagDetector.detect("how are you",
                "Doing great, zero bugs today! Suvra's busy — what can I pass on?",
                ConversationContext.OfferReply.NONE);
        t(!r.isFlag() && !r.isPin(), "normal banter triggers neither");
    }

    private static void testHostileEmergencyDoesNotFlag() {
        FlagDetector.Result r1 = FlagDetector.detect("yes its urgent madarchoddd",
                "You can't declare an emergency and swear at the dispatcher at the same time. Urgent escalation denied. I'm IRIS, Suvra's assistant.",
                ConversationContext.OfferReply.NONE);
        t(!r1.isFlag() && !r1.isPin(), "hostile emergency denial does not trigger flag");

        FlagDetector.Result r2 = FlagDetector.detect("yes its urgent madarchoddd",
                "Profanity revokes your VIP privileges. Escalation denied — I'm IRIS.",
                ConversationContext.OfferReply.ACCEPT);
        t(!r2.isFlag() && !r2.isPin(), "hostile emergency with ACCEPT does not trigger flag due to profanity and denial");

        FlagDetector.Result r3 = FlagDetector.detect("flag this urgent bhenchod",
                "Mind the language — logged for Suvra. I'm IRIS.",
                ConversationContext.OfferReply.NONE);
        t(!r3.isFlag() && !r3.isPin(), "flag request with profanity is suppressed and does not flag");
    }

    private static void t(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("FAIL (" + checks + "): " + msg);
        }
    }
}
