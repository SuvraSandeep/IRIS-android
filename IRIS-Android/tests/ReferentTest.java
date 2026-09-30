package com.iris.assistant;

/**
 * Offline checks for Referent (pure, no Android).
 *
 * As with PhoneHistoryQueryTest, the negative assertions matter most: the bare redial
 * phrasings already handled by IrisListeningService.REDIAL_PATTERN, and ordinary named
 * call/text commands, must stay unmatched so nothing that already works gets re-routed.
 */
public class ReferentTest {

    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void act(String phrase, Referent.Action expected) {
        Referent r = Referent.parse(phrase);
        check(r.action == expected,
                "\"" + phrase + "\" expected " + expected + " but got " + r.action);
    }

    public static void main(String[] args) {
        callReferents();
        textReferents();
        doesNotStealRedial();
        doesNotStealNamedCommands();
        edgeCases();
        System.out.println("Passed " + checks + " referent follow-up checks");
    }

    private static void callReferents() {
        act("call him", Referent.Action.CALL);
        act("call her", Referent.Action.CALL);
        act("call them", Referent.Action.CALL);
        act("call him back", Referent.Action.CALL);
        act("call her back", Referent.Action.CALL);
        act("call them back", Referent.Action.CALL);
        act("call him again", Referent.Action.CALL);
        act("ring him", Referent.Action.CALL);
        act("ring her back", Referent.Action.CALL);
        act("phone her", Referent.Action.CALL);
        act("dial him", Referent.Action.CALL);
        act("give him a call", Referent.Action.CALL);
        act("give her a ring", Referent.Action.CALL);
        act("call that person", Referent.Action.CALL);
        act("call him now", Referent.Action.CALL);
        act("please call her back", Referent.Action.CALL);
        act("call her back please", Referent.Action.CALL);
        act("Call Him Back!", Referent.Action.CALL);
    }

    private static void textReferents() {
        act("text him", Referent.Action.TEXT);
        act("text her", Referent.Action.TEXT);
        act("text them", Referent.Action.TEXT);
        act("text him back", Referent.Action.TEXT);
        act("message her", Referent.Action.TEXT);
        act("message them back", Referent.Action.TEXT);
        act("sms him", Referent.Action.TEXT);
        act("send him a message", Referent.Action.TEXT);
        act("send her a text", Referent.Action.TEXT);
        act("send them a sms", Referent.Action.TEXT);
        act("reply to him", Referent.Action.TEXT);
        act("reply to her", Referent.Action.TEXT);
        act("respond to them", Referent.Action.TEXT);
        act("write to her", Referent.Action.TEXT);
        act("reply to that message", Referent.Action.TEXT);
        act("reply to the text", Referent.Action.TEXT);
        act("please text her for me", Referent.Action.TEXT);
    }

    /** The redial handler owns these bare phrasings; a pronoun is required here. */
    private static void doesNotStealRedial() {
        act("redial", Referent.Action.NONE);
        act("call back", Referent.Action.NONE);
        act("call again", Referent.Action.NONE);
        act("ring back", Referent.Action.NONE);
        act("ring again", Referent.Action.NONE);
        act("call the last person", Referent.Action.NONE);
        act("call the last contact", Referent.Action.NONE);
    }

    private static void doesNotStealNamedCommands() {
        act("call my mother", Referent.Action.NONE);
        act("call tony", Referent.Action.NONE);
        act("call my brother back", Referent.Action.NONE);
        act("text my brother saying i am late", Referent.Action.NONE);
        act("text tony", Referent.Action.NONE);
        act("send a message to ana", Referent.Action.NONE);
        act("who called me", Referent.Action.NONE);
        act("read my last text", Referent.Action.NONE);
        act("what time is it", Referent.Action.NONE);
        act("call history", Referent.Action.NONE);
        // A pronoun inside a longer sentence is not a bare follow-up command.
        act("tell him i am running late", Referent.Action.NONE);
        act("text him saying i am late", Referent.Action.NONE);
    }

    private static void edgeCases() {
        act(null, Referent.Action.NONE);
        act("", Referent.Action.NONE);
        act("   ", Referent.Action.NONE);
        act("  CALL   HIM   BACK  ", Referent.Action.CALL);
        check(!Referent.parse("what time is it").matched(), "no-match not matched");
        check(Referent.parse("call him").matched(), "match reports matched");
        check(Referent.parse("text her").toString().equals("TEXT"), "toString");
    }
}
