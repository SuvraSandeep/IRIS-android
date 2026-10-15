package com.iris.assistant;

/** Offline checks for multi-turn follow-up detection. */
public final class ConversationContextTest {

    private static int checks;

    private static void eq(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAILED: " + what + " expected " + b + " got " + a);
        }
    }

    public static void main(String[] args) {
        // Cancel a just-set reminder.
        eq(ConversationContext.classify("cancel that reminder", true),
                ConversationContext.FollowUp.CANCEL_REMINDER, "cancel that reminder");
        eq(ConversationContext.classify("actually forget the reminder", true),
                ConversationContext.FollowUp.CANCEL_REMINDER, "forget the reminder");

        // Edit the time of a just-set reminder (needs a parseable time).
        eq(ConversationContext.classify("actually make it 6pm", true),
                ConversationContext.FollowUp.EDIT_REMINDER_TIME, "make it 6pm");
        eq(ConversationContext.classify("change it to tomorrow 9am", true),
                ConversationContext.FollowUp.EDIT_REMINDER_TIME, "change it to tomorrow 9am");
        eq(ConversationContext.classify("move it to monday", true),
                ConversationContext.FollowUp.EDIT_REMINDER_TIME, "move it to monday");

        // An edit phrase with no time is not an edit (falls through to a normal reply).
        eq(ConversationContext.classify("actually make it better", true),
                ConversationContext.FollowUp.NONE, "edit phrase without a time is not an edit");

        // No recent reminder -> never a follow-up, so a shop's "cancel that" is untouched.
        eq(ConversationContext.classify("cancel that reminder", false),
                ConversationContext.FollowUp.NONE, "no recent reminder means no follow-up");
        eq(ConversationContext.classify("make it 6pm", false),
                ConversationContext.FollowUp.NONE, "no recent reminder, edit ignored");

        // Ordinary messages are not follow-ups even with a recent reminder.
        eq(ConversationContext.classify("thanks a lot", true),
                ConversationContext.FollowUp.NONE, "thanks is not a follow-up");
        eq(ConversationContext.classify("", true),
                ConversationContext.FollowUp.NONE, "empty is not a follow-up");
        eq(ConversationContext.classify(null, true),
                ConversationContext.FollowUp.NONE, "null is not a follow-up");

        // "make it 6" with a bare hour is an edit (regression: it used to return NONE).
        eq(ConversationContext.classify("make it 6", true),
                ConversationContext.FollowUp.EDIT_REMINDER_TIME, "bare-hour edit is detected");
        eq(ConversationContext.editTime("make it 6").minuteOfDay, 360, "make it 6 -> 06:00");
        eq(ConversationContext.classify("change it to 6:30", true),
                ConversationContext.FollowUp.EDIT_REMINDER_TIME, "change it to 6:30 is an edit");
        // Two-turn escalation offer: accept / refuse / arming.
        eq(ConversationContext.classifyOffer("no no don't", ConversationContext.Offer.OFFERED_ESCALATION),
                ConversationContext.OfferReply.REFUSE, "'no no don't' refuses the offer");
        eq(ConversationContext.classifyOffer("yes please flag it", ConversationContext.Offer.OFFERED_ESCALATION),
                ConversationContext.OfferReply.ACCEPT, "'yes please flag it' accepts");
        eq(ConversationContext.classifyOffer("yes please", ConversationContext.Offer.NONE),
                ConversationContext.OfferReply.NONE, "no pending offer -> NONE");
        eq(ConversationContext.classifyOffer("what's the weather", ConversationContext.Offer.OFFERED_ESCALATION),
                ConversationContext.OfferReply.NONE, "unrelated message -> NONE");
        eq(ConversationContext.armsEscalationOffer("is he busy right now"), true,
                "availability question arms the offer");
        eq(ConversationContext.armsEscalationOffer("happy birthday"), false,
                "a greeting does not arm the offer");
        System.out.println("Passed " + checks + " conversation context checks");
    }
}
