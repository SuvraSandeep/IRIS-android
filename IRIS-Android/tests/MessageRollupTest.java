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
}
