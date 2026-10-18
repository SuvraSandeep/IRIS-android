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
            check(!l.toLowerCase(java.util.Locale.ROOT).contains("jealous"), "laugh variant does not accuse of jealousy: " + l);
            check(!l.toLowerCase(java.util.Locale.ROOT).contains("funny one"), "laugh variant does not claim to be the funny one: " + l);
            check(!l.toLowerCase(java.util.Locale.ROOT).contains("auto-reply"), "laugh variant does not mock auto-reply: " + l);
            check(!l.toLowerCase(java.util.Locale.ROOT).contains("auto-responder"), "laugh variant does not mock auto-responder: " + l);
        }
        // Same rotation is stable.
        check(Emoji.reply("\uD83D\uDE02", ReplyTone.Tone.CASUAL, 3)
                        .equals(Emoji.reply("\uD83D\uDE02", ReplyTone.Tone.CASUAL, 3)),
                "same rotation gives same reply");

        // isLaughEmoji helper (v16.52.0)
        check(Emoji.isLaughEmoji("\uD83D\uDE02"), "isLaughEmoji matches 😂");
        check(Emoji.isLaughEmoji("\uD83E\uDD23"), "isLaughEmoji matches 🤣");
        check(Emoji.isLaughEmoji("\uD83D\uDE02\uD83D\uDE02"), "isLaughEmoji matches multiple laughs");
        check(Emoji.isLaughEmoji("  \uD83D\uDE02  "), "isLaughEmoji trims whitespace");
        check(!Emoji.isLaughEmoji("haha \uD83D\uDE02"), "isLaughEmoji rejects mixed words");
        check(!Emoji.isLaughEmoji("\u2764\uFE0F"), "isLaughEmoji rejects heart");
        check(!Emoji.isLaughEmoji("hello"), "isLaughEmoji rejects text");
        check(!Emoji.isLaughEmoji(""), "isLaughEmoji rejects empty");
        check(!Emoji.isLaughEmoji(null), "isLaughEmoji rejects null");

        // Smile in pain / dead inside emoji (🥲, 🙃) rotates through 15 variants
        check(Emoji.dominant("\uD83E\uDD72") == Emoji.Category.SMILE_IN_PAIN, "smile with tear is SMILE_IN_PAIN");
        check(Emoji.dominant("\uD83D\uDE43") == Emoji.Category.SMILE_IN_PAIN, "upside down face is SMILE_IN_PAIN");
        java.util.Set<String> painSmiles = new java.util.HashSet<>();
        for (int r = 0; r < 15; r++) {
            painSmiles.add(Emoji.reply("\uD83E\uDD72", ReplyTone.Tone.SARCASTIC, r));
        }
        check(painSmiles.size() >= 10, "smile in pain has at least 10 distinct variants: " + painSmiles.size());
        for (String p : painSmiles) {
            check(p.toLowerCase(java.util.Locale.ROOT).contains("iris"), "each pain smile names IRIS");
        }

        // Pleading / teary / puppy eyes emoji (🥹, 🥺) rotates through 15 variants
        check(Emoji.dominant("\uD83E\uDD79") == Emoji.Category.MOVED, "holding back tears is MOVED");
        check(Emoji.dominant("\uD83E\uDD7A") == Emoji.Category.MOVED, "pleading eyes is MOVED");
        java.util.Set<String> pleading = new java.util.HashSet<>();
        for (int r = 0; r < 15; r++) {
            pleading.add(Emoji.reply("\uD83E\uDD79", ReplyTone.Tone.SARCASTIC, r));
        }
        check(pleading.size() >= 10, "pleading teary has at least 10 distinct variants: " + pleading.size());
        for (String p : pleading) {
            check(p.toLowerCase(java.util.Locale.ROOT).contains("iris"), "each pleading variant names IRIS");
        }
        // Offensive / dismissive emoji get a sarcastic, rotating, passive-aggressive reply.
        check(Emoji.dominant("\uD83D\uDD95") == Emoji.Category.OFFENSIVE, "middle finger is OFFENSIVE");
        check(Emoji.dominant("\uD83D\uDCA9") == Emoji.Category.OFFENSIVE, "poop is OFFENSIVE");
        check(Emoji.dominant("\uD83E\uDD21") == Emoji.Category.OFFENSIVE, "clown is OFFENSIVE");
        check(Emoji.dominant("\uD83D\uDC4E") == Emoji.Category.OFFENSIVE, "thumbs down is OFFENSIVE");
        for (String e : new String[]{"\uD83E\uDDE2", "\uD83E\uDD71", "\uD83D\uDE12", "\uD83D\uDE44"}) {
            check(Emoji.dominant(e) == Emoji.Category.OFFENSIVE, "dismissive emoji is OFFENSIVE: " + e);
            check(!Emoji.reply(e, ReplyTone.Tone.PROFESSIONAL).isEmpty(), "offensive emoji replies: " + e);
        }
        java.util.Set<String> rude = new java.util.HashSet<>();
        for (int r = 0; r < 15; r++) {
            rude.add(Emoji.reply("\uD83D\uDD95", ReplyTone.Tone.PROFESSIONAL, r));
        }
        check(rude.size() >= 10, "offensive emoji has >=10 distinct comebacks: " + rude.size());
        for (String l : rude) {
            check(l.toLowerCase(java.util.Locale.ROOT).contains("iris"), "each offensive variant names IRIS");
        }
        // An offensive emoji is NOT answered warmly regardless of tone (tone ignored by the pool).
        check(Emoji.reply("\uD83D\uDD95", ReplyTone.Tone.CASUAL, 2)
                        .equals(Emoji.reply("\uD83D\uDD95", ReplyTone.Tone.PROFESSIONAL, 2)),
                "offensive reply is tone-agnostic");

        // Newly added glyphs across friendly categories are recognised.
        check(Emoji.dominant("\uD83D\uDC93") == Emoji.Category.LOVE, "beating heart is LOVE");
        check(Emoji.dominant("\uD83D\uDC51") == Emoji.Category.PRAISE, "crown is PRAISE");
        check(Emoji.dominant("\uD83E\uDD1D") == Emoji.Category.OK || Emoji.dominant("\uD83E\uDD1D") == Emoji.Category.GREET, "handshake recognized");
        check(Emoji.dominant("\uD83D\uDE47") == Emoji.Category.THANKS, "bowing is THANKS");
        check(Emoji.dominant("\uD83C\uDF88") == Emoji.Category.CELEBRATE, "balloon is CELEBRATE");

        // Comprehensive 10-category emotional-intelligence engine checks (10 variants each)
        String[][] testCategories = {
                {"\uD83D\uDE21", "ANGER"},
                {"\u2764\uFE0F", "LOVE"},
                {"\uD83D\uDC4D", "APPROVAL"},
                {"\uD83D\uDD25", "HYPE"},
                {"\uD83E\uDD21", "MOCKERY"},
                {"\uD83E\uDD14", "THINKING"},
                {"\uD83D\uDC40", "STARING"},
                {"\uD83E\uDD71", "BOREDOM"},
                {"\uD83D\uDE4F", "PRAYING"},
                {"\uD83E\uDD76", "FREEZING"}
        };
        for (String[] tc : testCategories) {
            java.util.Set<String> variants = new java.util.HashSet<>();
            for (int r = 0; r < 10; r++) {
                String rep = Emoji.reply(tc[0], ReplyTone.Tone.SARCASTIC, r);
                variants.add(rep);
                check(rep.contains("IRIS"), tc[1] + " variant names IRIS: " + rep);
            }
            check(variants.size() == 10, tc[1] + " has 10 distinct variants: " + variants.size());
        }

        System.out.println("Passed " + checks + " emoji checks");
    }
}
