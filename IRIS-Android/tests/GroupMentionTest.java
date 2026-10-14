package com.iris.assistant;

import java.util.Arrays;
import java.util.List;

/** Offline checks for @-handle / phone-number mention detection in groups. */
public final class GroupMentionTest {
    private static int checks;
    private static final List<String> H = Arrays.asList("you_know__who__i__am");
    private static final List<String> P = Arrays.asList("7789893369");

    private static void yes(String body, String what) {
        checks++;
        if (!GroupMention.isMentioned(body, H, P)) throw new AssertionError("FAILED (want hit): " + what);
    }
    private static void no(String body, String what) {
        checks++;
        if (GroupMention.isMentioned(body, H, P)) throw new AssertionError("FAILED (want miss): " + what);
    }

    public static void main(String[] args) {
        // Handle, with and without @, and with trailing punctuation.
        yes("hey @you_know__who__i__am are you coming", "handle with @");
        yes("you_know__who__i__am please check this", "handle without @");
        yes("ping @you_know__who__i__am, urgent", "handle with trailing comma");
        yes("YO @YOU_KNOW__WHO__I__AM", "handle case-insensitive");

        // Number in various iterations -- all share the last 10 digits.
        yes("@+917789893369 can you join", "number with +91 and @");
        yes("call 7789893369 please", "bare national number");
        yes("@917789893369", "number with country code, @");
        yes("ref 91-77898-93369 today", "number with separators");

        // Must NOT false-match.
        no("do you know who i am in this group", "the English phrase is not the handle");
        no("@you_know_who_i_am hi", "a different handle (fewer underscores)");
        no("@someone_else check", "someone else's handle");
        no("my number is 9999999999", "a different number");
        no("meeting at 10", "no mention at all");
        no("order 12345 ready", "short number is not a phone");

        // Empty config matches nothing.
        checks++;
        if (GroupMention.isMentioned("@you_know__who__i__am", null, null))
            throw new AssertionError("empty config matches nothing");

        System.out.println("Passed " + checks + " group mention checks");
    }
}
