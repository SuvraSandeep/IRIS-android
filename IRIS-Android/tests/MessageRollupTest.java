package com.iris.assistant;

/**
 * Offline checks for roll-up detection.
 *
 * The asymmetry matters and is pinned here deliberately. A missed roll-up stores one junk row,
 * which is annoying. A false positive DISCARDS A REAL MESSAGE, and since the archive exists
 * because deleted messages are otherwise unrecoverable, that loss is permanent and silent. These
 * checks therefore spend most of their effort on things that must NOT be treated as roll-ups.
 */
public class MessageRollupTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        realRollupsFromTheProbeAreCaught();
        otherCommonRollupFormsAreCaught();
        realMessagesAreNeverDiscarded();
        edgeInputsFailSafe();
        perMessageDetailAlwaysWins();
        groupTitleIsStrippedOfDecoration();
        groupNeedsRealEvidence();
        System.out.println("Passed " + checks + " message roll-up checks");
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    /**
     * Group titles must reduce to a stable name. The probe showed WhatsApp appends both a message
     * count and the latest sender, so without stripping those the same group would look like a
     * different group every time another message arrived.
     */
    private static void groupTitleIsStrippedOfDecoration() {
        equal(MessageRollup.groupFromTitle(
                        "Swiss Group (3 messages): Rajesh Bhai Eric Kolkata Shapoorji"),
                "Swiss Group", "the probe's real group title reduces to the group name");
        equal(MessageRollup.groupFromTitle("Swiss Group (12 messages): Someone Else"),
                "Swiss Group", "a different count yields the same group name");
        equal(MessageRollup.groupFromTitle("Swiss Group (1 message): Someone"),
                "Swiss Group", "the singular count is stripped too");
        equal(MessageRollup.groupFromTitle("Swiss Group"), "Swiss Group",
                "an undecorated group name is unchanged");
        equal(MessageRollup.groupFromTitle("Bidisha"), "Bidisha",
                "a one-to-one chat title is unchanged");
        equal(MessageRollup.groupFromTitle("Debraj Naskar Eric Kol"), "Debraj Naskar Eric Kol",
                "a multi-word personal name is not truncated");
        equal(MessageRollup.groupFromTitle(""), "", "empty stays empty");
        equal(MessageRollup.groupFromTitle(null), "", "null becomes empty rather than crashing");
        equal(MessageRollup.groupFromTitle("  Swiss Group  "), "Swiss Group",
                "outer whitespace is trimmed");
        // A bracketed phrase that is NOT a count must survive: it is part of the chat's real name.
        equal(MessageRollup.groupFromTitle("Team (Planning)"), "Team (Planning)",
                "a non-count bracket is part of the name and is kept");
    }

    /** The exact string the on-device probe returned from WhatsApp. */
    private static void realRollupsFromTheProbeAreCaught() {
        check(MessageRollup.isRollup("14 messages from 3 chats"),
                "the probe's own roll-up text is recognised");
        check(MessageRollup.isSummaryOnly("14 messages from 3 chats", 0),
                "the probe's roll-up with no per-message detail is skipped");
        check(MessageRollup.isRollup("1 message from 1 chat"),
                "the singular form is recognised too");
    }

    private static void otherCommonRollupFormsAreCaught() {
        check(MessageRollup.isRollup("3 new messages"), "N new messages");
        check(MessageRollup.isRollup("2 messages"), "N messages");
        check(MessageRollup.isRollup("5 new notifications"), "N new notifications");
        check(MessageRollup.isRollup("7 notifications"), "N notifications");
        check(MessageRollup.isRollup("  3 new messages  "), "surrounding whitespace is tolerated");
        check(MessageRollup.isRollup("3 NEW MESSAGES"), "case is ignored");
    }

    /**
     * The direction that must never go wrong. Each of these is something a person could actually
     * send, and treating any of them as a summary would delete it from the record for good.
     */
    private static void realMessagesAreNeverDiscarded() {
        check(!MessageRollup.isRollup("I sent you 3 messages about the car"),
                "a sentence containing a count is not a roll-up");
        check(!MessageRollup.isRollup("3 new messages?"),
                "trailing punctuation means a person typed it");
        check(!MessageRollup.isRollup("did you get my 2 messages"),
                "a question mentioning a count is a real message");
        check(!MessageRollup.isRollup("2 messages from Rajesh were deleted"),
                "a longer sentence starting like a roll-up is still a real message");
        check(!MessageRollup.isRollup("messages"), "a bare noun with no count is not a roll-up");
        check(!MessageRollup.isRollup("14"), "a bare number is not a roll-up");
        check(!MessageRollup.isRollup("Mu mora faeda dekhibii"),
                "a real message from the probe is untouched");
        check(!MessageRollup.isRollup("\uD83D\uDCF7 Sent a photo"),
                "a photo placeholder is a real message and must be kept");
    }

    private static void edgeInputsFailSafe() {
        check(!MessageRollup.isRollup(null), "null is not a roll-up");
        check(!MessageRollup.isRollup(""), "empty is not a roll-up");
        check(!MessageRollup.isRollup("   "), "whitespace is not a roll-up");
        check(!MessageRollup.isSummaryOnly(null, 0), "null text is not a summary");
        check(!MessageRollup.isSummaryOnly("", 0), "empty text is not a summary");
    }

    /**
     * The safety catch. Even if the text looks exactly like a count, the presence of per-message
     * detail proves this is a real notification carrying real content, so it is kept.
     */
    private static void perMessageDetailAlwaysWins() {
        check(!MessageRollup.isSummaryOnly("14 messages from 3 chats", 1),
                "roll-up text WITH one message entry is kept, not skipped");
        check(!MessageRollup.isSummaryOnly("3 new messages", 7),
                "roll-up text WITH seven message entries is kept");
        check(!MessageRollup.isSummaryOnly("Mu mora faeda dekhibii", 0),
                "a real message with no entries is still kept");
        check(MessageRollup.isSummaryOnly("2 messages", -1),
                "a negative count is treated as absent, not as detail");
    }

    /**
     * A group must be positively evidenced.
     *
     * These exist because of a real on-device failure. The responder read "the title is not empty"
     * as proof of a group, and in a one-to-one chat the title IS the other person -- so every
     * conversation was a group, and with groups off by default the responder refused everything.
     * The owner saw it first with a Twilio SMS, which is the fourth check below.
     */
    private static void groupNeedsRealEvidence() {
        // ─── One-to-one, which must never read as a group ───
        check(!MessageRollup.isGroupConversation(false, "Ramesh", "Ramesh", 1),
                "a 1:1 chat where title and sender match is not a group");
        check(!MessageRollup.isGroupConversation(false, "Ramesh", "", 1),
                "a title with no sender is not evidence of a group");
        check(!MessageRollup.isGroupConversation(false, "", "", 0),
                "nothing at all is not a group");
        // The one the owner hit: a Twilio test message arrives titled with the number and
        // carries no sender field.
        check(!MessageRollup.isGroupConversation(false, "+15551234567", "", 1),
                "an SMS titled with a bare number and no sender is NOT a group");
        check(!MessageRollup.isGroupConversation(false, "+15551234567", "+15551234567", 1),
                "an SMS whose sender repeats the number is not a group");
        check(!MessageRollup.isGroupConversation(false, "Alice Smith", "Alice", 1),
                "a shortened sender inside the title is the same person, not a group");
        check(!MessageRollup.isGroupConversation(false, "Alice", "Alice Smith", 1),
                "the reverse containment is also the same person");
        check(!MessageRollup.isGroupConversation(false, "ramesh", "RAMESH", 1),
                "case alone never makes two names different people");
        check(!MessageRollup.isGroupConversation(false, "Dr. Rao", "Dr Rao", 1),
                "punctuation alone never makes two names different people");

        // ─── Genuine groups, which must still be detected ───
        check(MessageRollup.isGroupConversation(true, "", "", 1),
                "the app's own group flag is believed outright");
        check(MessageRollup.isGroupConversation(true, "Ramesh", "Ramesh", 1),
                "the app's group flag beats a title that matches the sender");
        check(MessageRollup.isGroupConversation(false, "Project Falcon", "Alice", 1),
                "a group name different from the sender is a group");
        check(MessageRollup.isGroupConversation(false, "Family", "Ramesh", 3),
                "three distinct senders is a group");
        check(MessageRollup.isGroupConversation(false, "", "Alice", 2),
                "two distinct senders is a group even with no title");
        check(MessageRollup.isGroupConversation(false, "Ramesh", "Ramesh", 2),
                "two senders outweighs a title matching the newest sender");

        // ─── Degenerate input must not throw ───
        check(!MessageRollup.isGroupConversation(false, null, null, 0),
                "nulls fail safe to one-to-one");
        check(!MessageRollup.isGroupConversation(false, "Ramesh", null, 1),
                "a null sender fails safe to one-to-one");
        check(MessageRollup.isGroupConversation(true, null, null, 0),
                "nulls do not defeat the platform hint");
        check(!MessageRollup.isGroupConversation(false, "...", "", 1),
                "a title of only punctuation is not a group name");
        check(!MessageRollup.isGroupConversation(false, "Ramesh", "Ramesh", -1),
                "a negative sender count is not read as evidence");

        // The decorated-title path and this one have to agree, since the live code feeds one
        // into the other.
        check(!MessageRollup.isGroupConversation(false,
                        MessageRollup.groupFromTitle("Ramesh (3 messages)"), "Ramesh", 1),
                "a 1:1 chat with a message count is still not a group");
        check(MessageRollup.isGroupConversation(false,
                        MessageRollup.groupFromTitle("Project Falcon (3 messages)"), "Alice", 1),
                "a group with a message count is still a group");
    }
}
