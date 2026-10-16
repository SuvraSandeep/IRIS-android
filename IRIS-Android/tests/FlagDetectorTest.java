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
    }

    private static void testNegationsDoNotFlag() {
        FlagDetector.Result r1 = FlagDetector.detect("no don't flag it",
                "Understood — won't disturb Suvra.", ConversationContext.OfferReply.DECLINE);
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

    private static void t(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("FAIL (" + checks + "): " + msg);
        }
    }
}
