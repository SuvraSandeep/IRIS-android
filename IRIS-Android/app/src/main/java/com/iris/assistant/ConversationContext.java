package com.iris.assistant;

/**
 * Multi-turn follow-up detection: whether an incoming message is editing or cancelling the thing
 * IRIS just did in this conversation, rather than a fresh request.
 *
 * Pure and offline-testable. It only needs to know whether there is a RECENT action to follow up
 * on (the Android layer tracks that and its time window); the text classification itself is here.
 *
 * Kept deliberately narrow for safety: a follow-up is only recognised when there is a recent
 * reminder to act on, so an ordinary "cancel my order" to a shop is never read as cancelling an
 * IRIS reminder.
 */
public final class ConversationContext {

    /** How long after setting a reminder a bare "make it 6" still refers to it. */
    public static final long FOLLOW_UP_WINDOW_MS = 2L * 60 * 60 * 1000;

    public enum FollowUp {
        /** Not a follow-up; handle as a fresh message. */
        NONE,
        /** Change the time of the reminder just set in this conversation. */
        EDIT_REMINDER_TIME,
        /** Cancel the reminder just set in this conversation. */
        CANCEL_REMINDER
    }

    private static final String[] CANCEL = {
            "cancel that", "cancel it", "cancel the reminder", "cancel that reminder",
            "forget that reminder", "forget the reminder", "scrap that", "scrap the reminder",
            "drop that reminder", "remove that reminder", "delete that reminder",
            "never mind the reminder", "no reminder", "dont remind", "do not remind"};

    private static final String[] EDIT = {
            "actually make it", "make it instead", "make it", "change it to", "change that to",
            "move it to", "move it", "push it to", "reschedule it to", "reschedule it",
            "actually at", "no make it", "instead make it", "lets say", "better make it",
            "actually remind", "change the reminder to", "update the reminder"};

    private ConversationContext() { }

    /** The owner-offer IRIS last made in this conversation, that the next message may answer. */
    public enum Offer {
        /** No pending offer. */
        NONE,
        /** IRIS asked "shall I flag this as urgent?" and is waiting for yes/no. */
        OFFERED_ESCALATION
    }

    /** How the sender answered a pending offer. */
    public enum OfferReply { NONE, ACCEPT, REFUSE }

    private static final String[] REFUSE = {
            "no", "nah", "nope", "dont", "no no", "no dont", "dont flag", "dont disturb",
            "not urgent", "no need", "let him work", "let him finish", "its fine", "it s fine",
            "leave it", "rehne do", "rehnde", "chhod", "chhod de", "no thanks", "nvm",
            "never mind", "dont bother"};

    private static final String[] ACCEPT = {
            "yes", "yes please", "yeah", "yep", "yup", "please do", "do it", "flag it",
            "flag it please", "yes flag", "please flag", "go ahead", "haan", "haan kar do",
            "kar do", "escalate", "mark it urgent", "its urgent", "it s urgent"};

    /**
     * Classify the answer to a pending offer. Only meaningful when {@code offer} is not NONE, i.e.
     * IRIS just asked something and this message is the reply. Refuse wins over accept on a mixed
     * message, because the safe direction is NOT to escalate a stranger past the cooldown.
     */
    public static OfferReply classifyOffer(String body, Offer offer) {
        if (offer == null || offer == Offer.NONE || body == null) return OfferReply.NONE;
        String flat = " " + MessageCategory.normalise(body) + " ";
        if (flat.trim().isEmpty()) return OfferReply.NONE;
        if (containsAny(flat, REFUSE)) return OfferReply.REFUSE;
        if (containsAny(flat, ACCEPT)) return OfferReply.ACCEPT;
        return OfferReply.NONE;
    }

    /**
     * Classify a message as a follow-up to a recent reminder, or not.
     *
     * @param body               the incoming message
     * @param hasRecentReminder  the Android layer confirmed a reminder was set in this
     *                           conversation within {@link #FOLLOW_UP_WINDOW_MS}
     */
    public static FollowUp classify(String body, boolean hasRecentReminder) {
        if (!hasRecentReminder || body == null) return FollowUp.NONE;
        String flat = " " + MessageCategory.normalise(body) + " ";
        if (flat.trim().isEmpty()) return FollowUp.NONE;

        // Cancel wins over edit: "cancel that, actually never mind" is a cancel.
        if (containsAny(flat, CANCEL)) return FollowUp.CANCEL_REMINDER;

        // An edit only counts if the message also carries a parseable time, so a bare "change it"
        // with no time falls through to a normal reply rather than silently editing nothing.
        if (containsAny(flat, EDIT) && editTime(body).hasTime()) {
            return FollowUp.EDIT_REMINDER_TIME;
        }
        return FollowUp.NONE;
    }

    /**
     * The reminder time implied by an edit, tolerant of a bare hour. "make it 6" has no "at" and
     * the main parser reads no time from it, so a trailing "6" / "6:30" / "6pm" is re-read as "at
     * 6". This is the exact phrasing the follow-up feature exists for.
     */
    public static ReminderParser.Result editTime(String body) {
        ReminderParser.Result r = ReminderParser.parse(body);
        if (r.hasTime() || body == null) return r;
        java.util.regex.Matcher m = BARE_TIME.matcher(body.trim());
        if (m.find()) return ReminderParser.parse("at " + m.group(1));
        return r;
    }

    private static final java.util.regex.Pattern BARE_TIME = java.util.regex.Pattern.compile(
            "(?i)\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)\\s*$");

    /**
     * Whether an answer to this message should arm an escalation offer -- i.e. the sender is
     * asking after the owner's availability, so IRIS's reply ("he's busy; say 'urgent' if needed")
     * invites a yes/no the next message can answer. A small, self-contained keyword set.
     */
    public static boolean armsEscalationOffer(String body) {
        if (body == null) return false;
        String flat = " " + MessageCategory.normalise(body) + " ";
        return containsAny(flat, ARMS);
    }

    private static final String[] ARMS = {
            "is he busy", "is he free", "is he available", "is he on a call", "on a work call",
            "in a meeting", "is he in a meeting", "can i talk to him", "can he talk",
            "is he there", "is he reachable", "need to reach him", "is he working"};

    private static boolean containsAny(String paddedFlat, String[] phrases) {
        for (String p : phrases) {
            if (paddedFlat.contains(" " + p + " ")) return true;
        }
        return false;
    }
}
