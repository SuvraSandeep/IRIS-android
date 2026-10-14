package com.iris.assistant;

/** Offline checks for emoji meaning, sentiment categories and emoji-only replies. */
public final class EmojiTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        check(Emoji.dominant("\uD83D\uDE02") == Emoji.Category.LAUGH, "laughing is LAUGH");
        check(Emoji.dominant("\u2764\uFE0F") == Emoji.Category.LOVE, "heart is LOVE");
        check(Emoji.dominant("\uD83D\uDE2D") == Emoji.Category.SAD, "sobbing is SAD");
        check(Emoji.dominant("\uD83D\uDE21") == Emoji.Category.ANGRY, "angry is ANGRY");
        check(Emoji.dominant("\uD83C\uDF89") == Emoji.Category.CELEBRATE, "party is CELEBRATE");
        check(Emoji.dominant("\uD83D\uDC4D") == Emoji.Category.OK, "thumbs up is OK");
        check(Emoji.dominant("\uD83D\uDE4F") == Emoji.Category.THANKS, "folded hands is THANKS");
        check(Emoji.dominant("hello there") == Emoji.Category.NONE, "plain text has no emoji");

        // Dominant = most frequent.
        check(Emoji.dominant("\uD83D\uDE02\uD83D\uDE02\u2764\uFE0F") == Emoji.Category.LAUGH,
                "two laughs beat one heart");

        check(Emoji.countIn("\uD83D\uDE2D\uD83D\uDE2D", Emoji.Category.SAD) == 2, "counts sad");
        check(Emoji.hasEmoji("ok \uD83D\uDC4D"), "detects embedded emoji");
        check(!Emoji.hasEmoji("just text"), "no emoji in plain text");

        // Replies: present and name IRIS for clear categories, empty for neutral.
        check(Emoji.reply("\u2764\uFE0F", ReplyTone.Tone.PROFESSIONAL).contains("IRIS"),
                "love reply names IRIS");
        check(Emoji.reply("\u2764\uFE0F", ReplyTone.Tone.PROFESSIONAL).contains("{owner}"),
                "love reply keeps {owner} for fill");
        check(Emoji.reply("\uD83D\uDE2D", ReplyTone.Tone.SARCASTIC).toLowerCase(
                        java.util.Locale.ROOT).contains("sorry")
                || Emoji.reply("\uD83D\uDE2D", ReplyTone.Tone.SARCASTIC).contains("flagged"),
                "sad reply is gentle even in sarcastic tone");
        check(Emoji.reply("\uD83D\uDC40", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "eyes emoji has no clear reply (falls back to a plain ping)");
        check(Emoji.reply("plain", ReplyTone.Tone.PROFESSIONAL).isEmpty(), "no emoji -> no reply");

        System.out.println("Passed " + checks + " emoji checks");
    }
}
