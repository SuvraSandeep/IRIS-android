package com.iris.assistant;

import java.util.Locale;

/**
 * Pure detector for messages that commit IRIS to pinning or flagging a message for the owner.
 *
 * SCOPE: No Android dependencies so that all detection, negations, and categorization
 * can be thoroughly verified in offline regression tests.
 */
public final class FlagDetector {

    public enum Action {
        NONE,
        FLAG,
        PIN
    }

    public static final class Result {
        public final Action action;
        public final String reason;

        public Result(Action action, String reason) {
            this.action = action == null ? Action.NONE : action;
            this.reason = reason == null ? "" : reason;
        }

        public boolean isFlag() { return action == Action.FLAG; }
        public boolean isPin() { return action == Action.PIN; }
    }

    private FlagDetector() { }

    /**
     * True when incoming text explicitly requests IRIS to flag a message for the owner,
     * provided it does not contain negations or profanity.
     */
    public static boolean isExplicitFlagCommand(String incomingText) {
        if (incomingText == null) return false;
        String normIncoming = incomingText.toLowerCase(Locale.ROOT).trim();
        if (normIncoming.isEmpty()) return false;

        boolean negated = normIncoming.contains("don't flag")
                || normIncoming.contains("dont flag")
                || normIncoming.contains("no flag")
                || normIncoming.contains("won't flag")
                || normIncoming.contains("wont flag")
                || normIncoming.contains("not flag")
                || normIncoming.contains("never flag");
        if (negated) return false;

        boolean profanity = normIncoming.contains("madarchod")
                || normIncoming.contains("bhenchod")
                || normIncoming.contains("chutiya")
                || normIncoming.contains("bhosd")
                || normIncoming.contains("gandu")
                || normIncoming.contains("fuck")
                || normIncoming.contains("fuxk")
                || normIncoming.contains("shit")
                || normIncoming.contains("bitch")
                || normIncoming.contains("asshole")
                || normIncoming.contains("dick")
                || normIncoming.contains("maghia")
                || normIncoming.contains("maghiya")
                || normIncoming.contains("bandapua")
                || normIncoming.contains("banda maghiya")
                || normIncoming.contains("ma ki chut")
                || normIncoming.contains("maa ki chut")
                || normIncoming.contains("badk")
                || normIncoming.contains("bhadwa");
        if (profanity) return false;

        return normIncoming.contains("flag this")
                || normIncoming.contains("flag it")
                || normIncoming.contains("flag urgent")
                || normIncoming.contains("flag as urgent")
                || normIncoming.contains("please flag")
                || normIncoming.contains("urgent flag")
                || normIncoming.contains("flag my message")
                || normIncoming.contains("flag my msg")
                || normIncoming.contains("flag a message")
                || normIncoming.contains("flag a message for me")
                || normIncoming.contains("flag this for me")
                || normIncoming.contains("can you flag this")
                || normIncoming.contains("please flag this")
                || normIncoming.contains("flag message");
    }

    /**
     * Evaluates incoming message text, outgoing reply text, and conversation offer state
     * to determine if this interaction commits IRIS to flagging or pinning a message.
     */
    public static Result detect(String incomingText, String replyText,
                                ConversationContext.OfferReply offerReply) {
        String normIncoming = incomingText == null ? "" : incomingText.toLowerCase(Locale.ROOT).trim();
        String normReply = replyText == null ? "" : replyText.toLowerCase(Locale.ROOT).trim();

        // Check if outgoing reply indicates refusal or declining to disturb, or hostile emergency denial
        boolean replyDeclinedDisturb = normReply.contains("won't disturb")
                || normReply.contains("wont disturb")
                || normReply.contains("won't flag")
                || normReply.contains("wont flag")
                || normReply.contains("not disturbing")
                || normReply.contains("escalation denied")
                || normReply.contains("denied")
                || normReply.contains("privileges revoked")
                || normReply.contains("status revoked")
                || normReply.contains("can't declare an emergency and swear")
                || normReply.contains("urgent alert cancelled");

        // Profanity or hostile override attempt in incoming text: never flag or escalate
        boolean incomingProfanity = normIncoming.contains("madarchod")
                || normIncoming.contains("bhenchod")
                || normIncoming.contains("chutiya")
                || normIncoming.contains("bhosd")
                || normIncoming.contains("gandu")
                || normIncoming.contains("fuck")
                || normIncoming.contains("fuxk")
                || normIncoming.contains("shit")
                || normIncoming.contains("bitch")
                || normIncoming.contains("asshole")
                || normIncoming.contains("dick")
                || normIncoming.contains("maghia")
                || normIncoming.contains("maghiya")
                || normIncoming.contains("bandapua")
                || normIncoming.contains("banda maghiya")
                || normIncoming.contains("ma ki chut")
                || normIncoming.contains("maa ki chut")
                || normIncoming.contains("badk")
                || normIncoming.contains("bhadwa");

        // 1. Explicit two-turn escalation offer accepted by sender (revoked if hostile or declined)
        if (offerReply == ConversationContext.OfferReply.ACCEPT
                && !replyDeclinedDisturb && !incomingProfanity) {
            return new Result(Action.FLAG, "Escalation accepted: sender requested urgent flag");
        }

        // Check if incoming text is explicitly a negation against flagging (e.g. "no don't flag", "don't flag it")
        boolean incomingNegatedFlag = incomingProfanity
                || normIncoming.contains("don't flag")
                || normIncoming.contains("dont flag")
                || normIncoming.contains("no flag")
                || normIncoming.contains("won't flag")
                || normIncoming.contains("wont flag")
                || normIncoming.contains("not flag")
                || normIncoming.contains("never flag");

        // 2. PIN detection
        // Guard against false positives like ATM PIN, UPI PIN, screen PIN, unlock PIN
        boolean isSecurityPin = normIncoming.contains("atm pin")
                || normIncoming.contains("upi pin")
                || normIncoming.contains("screen pin")
                || normIncoming.contains("unlock pin")
                || normIncoming.contains("device pin")
                || normIncoming.contains("bank account")
                || normReply.contains("screen pin")
                || normReply.contains("passwords or pins")
                || normReply.contains("cloud script know his screen pin");

        if (!isSecurityPin) {
            // Did sender ask to pin?
            if (normIncoming.contains("pin this")
                    || normIncoming.contains("pin it")
                    || normIncoming.contains("pin my message")
                    || normIncoming.contains("pin to chat")
                    || normIncoming.contains("pin to notes")
                    || normIncoming.contains("pin to dashboard")
                    || normIncoming.contains("pin for suvra")) {
                return new Result(Action.PIN, "Sender requested pin to assistant queue");
            }

            // Did IRIS reply state that it pinned the item?
            if (normReply.contains("when i say pinned")
                    || normReply.contains("pinned on {owner}'s bot summary")
                    || normReply.contains("pinned on suvra's bot summary")
                    || normReply.contains("pinned in {owner}'s private dashboard")
                    || normReply.contains("pinned in suvra's private dashboard")
                    || normReply.contains("pin it to {owner}'s notes")
                    || normReply.contains("pin it to suvra's notes")
                    || normReply.contains("food link \u2014 pinned")
                    || normReply.contains("food link - pinned")
                    || normReply.contains("meeting link \u2014 elevated")
                    || normReply.contains("meeting link - elevated")) {
                if (normReply.contains("food link")) {
                    return new Result(Action.PIN, "Food order link pinned");
                } else if (normReply.contains("meeting link")) {
                    return new Result(Action.PIN, "Meeting link pinned to priority queue");
                }
                return new Result(Action.PIN, "Pinned to assistant priority queue");
            }
        }

        // 3. FLAG detection
        if (!incomingNegatedFlag && !replyDeclinedDisturb) {
            // Did sender ask to flag?
            if (isExplicitFlagCommand(normIncoming)) {
                return new Result(Action.FLAG, "Sender requested urgent flag");
            }

            // Did IRIS reply state that it flagged the message?
            if (normReply.contains("flagged the payment note")
                    || normReply.contains("flagged payment note")) {
                return new Result(Action.FLAG, "Payment note flagged");
            }
            if (normReply.contains("flagged your file request")
                    || normReply.contains("flagged for {owner} to send it himself")
                    || normReply.contains("flagged suvra to send it himself")) {
                return new Result(Action.FLAG, "File / credential request flagged");
            }
            if (normReply.contains("tech alert logged")
                    || normReply.contains("tech alert flagged")
                    || normReply.contains("prod alert! i've flagged")) {
                return new Result(Action.FLAG, "High-priority tech / prod alert flagged");
            }
            if (normReply.contains("priority work ping")
                    || normReply.contains("work queue")) {
                return new Result(Action.FLAG, "Priority work ping flagged");
            }
            if (normReply.contains("health goes straight to the top")
                    || normReply.contains("hope all's well \u2014 flagged urgent")
                    || normReply.contains("hope all's well - flagged urgent")) {
                return new Result(Action.FLAG, "Urgent health / emergency ping flagged");
            }
            if (normReply.contains("flagged him to call back")
                    || normReply.contains("flagged your call")) {
                return new Result(Action.FLAG, "Missed call callback flagged");
            }
            if (normReply.contains("flagging this urgent so")
                    || normReply.contains("you're outside?! flagging this urgent")) {
                return new Result(Action.FLAG, "Arrival / delivery outside flagged");
            }
            if (normReply.contains("flagged urgent to help wake")) {
                return new Result(Action.FLAG, "Wake-up alert flagged");
            }
            if (normReply.contains("urgent flag triggered")) {
                return new Result(Action.FLAG, "Urgent escalation flagged");
            }
            if (normReply.contains("done \u2014 i've flagged this as high-priority")
                    || normReply.contains("done - i've flagged this as high-priority")
                    || normReply.contains("done \u2014 i've flagged this")
                    || normReply.contains("done - i've flagged this")
                    || normReply.contains("flagged! it's marked at the top")
                    || normReply.contains("pinned to {owner}'s flagged dashboard")
                    || normReply.contains("pinned to suvra's flagged dashboard")
                    || normReply.contains("flagged dashboard")
                    || normReply.contains("raised an urgent alert")
                    || normReply.contains("i've flagged your message")) {
                return new Result(Action.FLAG, "High-priority message flagged");
            }
            if (normReply.contains("tell me what happened and i'll flag")
                    || normReply.contains("spill it and i'll flag")) {
                return new Result(Action.FLAG, "Emergency incident alert flagged");
            }
            if (normReply.contains("flagged so {owner} sees it first thing")
                    || normReply.contains("flagged so suvra sees it first thing")) {
                return new Result(Action.FLAG, "Priority message flagged for morning queue");
            }
            if (normReply.contains("flagged for {owner}")
                    || normReply.contains("flagged for suvra")
                    || normReply.contains("flagged for him")) {
                return new Result(Action.FLAG, "Priority message flagged for owner");
            }
            if (normReply.contains("flagged")
                    || normReply.contains("flagging")
                    || normReply.contains("i'll flag")
                    || normReply.contains("urgent flag")) {
                return new Result(Action.FLAG, "Priority message flagged");
            }
        }

        return new Result(Action.NONE, "");
    }
}
