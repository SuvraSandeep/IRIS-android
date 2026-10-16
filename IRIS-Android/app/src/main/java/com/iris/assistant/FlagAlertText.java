package com.iris.assistant;

/**
 * The wording for the owner-facing alert posted when a sender accepts IRIS's "shall I flag this
 * urgent?" offer. Pure and offline-testable, so the Android alert layer stays thin.
 *
 * This exists to fix a real behaviour bug: IRIS used to SAY "Done — I've flagged this" without
 * ever flagging anything, because the archive's flag column only represents deletions. Accepting
 * the offer now posts this alert to the owner, so the promise is actually kept.
 */
public final class FlagAlertText {

    private FlagAlertText() { }

    public static String title() {
        return "Flagged urgent";
    }

    /** Names the sender and app, never the message body (it may show on the lock screen).
     *  {owner} is left literal for the caller's fill(), keeping this class dependency-free. */
    public static String body(String sender, String appLabel) {
        String who = sender == null || sender.trim().isEmpty() ? "Someone" : sender.trim();
        String app = appLabel == null || appLabel.trim().isEmpty() ? "an app" : appLabel.trim();
        return who + " flagged a message on " + app + " as urgent for {owner}. Open IRIS to see it.";
    }
}
