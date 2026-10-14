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

        System.out.println("Passed " + checks + " conversation context checks");
    }
}
