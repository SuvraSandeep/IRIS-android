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
        check(!Emoji.reply("\uD83D\uDC40", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "eyes emoji now gets an \"I see you\" reply");
        check(Emoji.reply("plain", ReplyTone.Tone.PROFESSIONAL).isEmpty(), "no emoji -> no reply");

        // Newly covered emojis must produce a reply, not fall through to the generic ping.
        check(!Emoji.reply("\uD83E\uDD79", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "\uD83E\uDD79 (holding back tears) is recognised");
        check(!Emoji.reply("\uD83D\uDE48\uD83D\uDE49\uD83D\uDE4A", ReplyTone.Tone.CASUAL).isEmpty(),
                "three wise monkeys recognised");
        check(!Emoji.reply("\uD83D\uDC12", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "\uD83D\uDC12 monkey recognised");
        check(!Emoji.reply("\uD83C\uDF19", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "\uD83C\uDF19 moon recognised");
        check(!Emoji.reply("\uD83C\uDF1D", ReplyTone.Tone.BRIEF).isEmpty(),
                "\uD83C\uDF1D full moon recognised");
        check(Emoji.dominant("\uD83C\uDF19") == Emoji.Category.NIGHT, "moon is NIGHT");
        check(!Emoji.reply("\uD83D\uDE18", ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                "\uD83D\uDE18 kiss emoji gets a (deflecting) reply");
        check(Emoji.dominant("\uD83D\uDE09") == Emoji.Category.FLIRT, "wink is FLIRT");

        // Laughing emoji now rotates through many variants (fix: not always the same reply).
        java.util.Set<String> laughs = new java.util.HashSet<>();
        for (int r = 0; r < 15; r++) {
            laughs.add(Emoji.reply("\uD83D\uDE02\uD83D\uDE02", ReplyTone.Tone.SARCASTIC, r));
        }
        check(laughs.size() >= 10, "laugh emoji has at least 10 distinct variants: " + laughs.size());
        for (String l : laughs) {
            check(l.toLowerCase(java.util.Locale.ROOT).contains("iris"), "each laugh variant names IRIS");
        }
        // Same rotation is stable.
        check(Emoji.reply("\uD83D\uDE02", ReplyTone.Tone.CASUAL, 3)
                        .equals(Emoji.reply("\uD83D\uDE02", ReplyTone.Tone.CASUAL, 3)),
                "same rotation gives same reply");
        System.out.println("Passed " + checks + " emoji checks");
    }
}
