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

        // Group Chat Silence Rule checks:
        List<String> keywords = Arrays.asList("suvra", "stg");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("@suvra please check", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: @suvra explicit hit");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("suvra what is the plan", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: suvra explicit hit");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("iris what are you doing", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: iris explicit hit");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("hey @you_know__who__i__am look", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: handle explicit hit");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("@+917789893369 check", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: phone explicit hit");

        // Group chatter must stay silent:
        checks++;
        if (GroupMention.isExplicitlyAddressed("15133 rs asiba use heba amazon icici credit card", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: transaction chatter must NOT be addressed");
        checks++;
        if (GroupMention.isExplicitlyAddressed("ok guys see you at 6pm", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: general chatter must NOT be addressed");
        checks++;
        if (GroupMention.isExplicitlyAddressed("who has an icici card?", "Suvra", keywords, H, P))
            throw new AssertionError("FAILED: card question must NOT be addressed");
        // Phone with spaces/separators (WhatsApp format)
        yes("@+91 77898 93369 can you join", "phone with spaces and country code");
        yes("+91 77898 93369", "bare phone with spaces");

        // Handles configured with spaces or @-prefix (e.g. "@You Know Who I Am" or "You Know Who I Am")
        List<String> H2 = Arrays.asList("@You Know Who I Am");
        checks++;
        if (!GroupMention.isMentioned("@You Know Who I Am", H2, P))
            throw new AssertionError("FAILED: @You Know Who I Am exact handle with @");
        checks++;
        if (!GroupMention.isMentioned("Hey @You Know Who I Am wake up", H2, P))
            throw new AssertionError("FAILED: @You Know Who I Am inside sentence");
        checks++;
        if (!GroupMention.isMentioned("@You Know Who I Am: look", H2, P))
            throw new AssertionError("FAILED: @You Know Who I Am with colon");
        checks++;
        if (!GroupMention.isMentioned("You Know Who I Am please check", H2, P))
            throw new AssertionError("FAILED: You Know Who I Am without @");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("@You Know Who I Am", "Suvra", keywords, H2, P))
            throw new AssertionError("FAILED: explicit addressed @You Know Who I Am");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("Hey @You Know Who I Am check this", "Suvra", keywords, H2, P))
            throw new AssertionError("FAILED: explicit addressed sentence with @You Know Who I Am");

        // Underscored handle tagged with spaces and @ in incoming message
        yes("@You Know Who I Am", "underscored handle tagged with spaces and @");
        yes("hey @You Know Who I Am look", "underscored handle tagged with spaces in sentence");

        // Unicode BiDi isolate characters as inserted by WhatsApp Android notifications
        checks++;
        if (!GroupMention.isMentioned("\u2066@You Know Who I Am\u2069", H2, P))
            throw new AssertionError("FAILED: BiDi isolated handle");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("\u2066@You Know Who I Am\u2069: wake up", "Suvra", keywords, H2, P))
            throw new AssertionError("FAILED: BiDi isolated handle in explicit addressed");

        // Comma-separated trigger keywords
        List<String> commaKeywords = Arrays.asList("suvra", "stg", "emergency", "bot");
        checks++;
        if (!GroupMention.hasTriggerWord("this is an emergency guys", commaKeywords))
            throw new AssertionError("FAILED: emergency trigger word");
        checks++;
        if (!GroupMention.hasTriggerWord("hey bot what's up", commaKeywords))
            throw new AssertionError("FAILED: bot trigger word");
        checks++;
        if (!GroupMention.isExplicitlyAddressed("attention bot: respond", "Suvra", commaKeywords, H, P))
            throw new AssertionError("FAILED: bot explicit addressed");
        checks++;
        if (GroupMention.hasTriggerWord("bottle of water", commaKeywords))
            throw new AssertionError("FAILED: 'bottle' should not trigger 'bot'");

        System.out.println("Passed " + checks + " group mention checks");
    }
}
