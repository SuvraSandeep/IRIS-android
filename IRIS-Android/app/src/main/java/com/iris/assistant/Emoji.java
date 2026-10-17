package com.iris.assistant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Knows what the popular emojis mean, so IRIS can read emotion in a text and answer an emoji-only
 * message sensibly. Pure and offline-testable.
 *
 * Emoji are matched as their exact Unicode strings (surrogate pairs and the common variation
 * selector forms), so this works on the raw message before normalisation strips them. Two uses:
 * SentimentScore reads the emotional categories, and SmartReplies answers an emoji-only ping with
 * a reply that fits the dominant emoji. {owner} is left literal for the caller's fill().
 */
public final class Emoji {

    public enum Category {
        NONE, POSITIVE, LOVE, LAUGH, CELEBRATE, THANKS, PRAISE, OK, GREET,
        SAD, ANGRY, SCARED, SICK, THINKING, SLEEP, NEUTRAL, MONEY,
        PLAYFUL, NIGHT, MOVED, LOOK, FLIRT, OFFENSIVE, SMILE_IN_PAIN }

    private Emoji() { }

    private static final Map<String, Category> TABLE = build();

    private static void p(Map<String, Category> m, Category c, String... emojis) {
        for (String e : emojis) m.put(e, c);
    }

    private static Map<String, Category> build() {
        Map<String, Category> m = new LinkedHashMap<>();
        p(m, Category.LAUGH, "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDE05", "\uD83D\uDE06",
                "\uD83D\uDE39", "\uD83D\uDE02\uD83D\uDE02");
        p(m, Category.LOVE, "\u2764\uFE0F", "\u2764", "\uD83E\uDD70", "\uD83D\uDE0D",
                "\uD83D\uDE18", "\uD83D\uDC95", "\uD83D\uDC96", "\uD83D\uDC97", "\uD83E\uDEF6",
                "\uD83D\uDE0D", "\u2763\uFE0F", "\uD83D\uDC93", "\uD83D\uDC98", "\uD83D\uDE3B",
                "\uD83D\uDC9D", "\uD83D\uDC9E");
        p(m, Category.POSITIVE, "\uD83D\uDE0A", "\uD83D\uDE01", "\uD83D\uDE04", "\uD83D\uDE42",
                "\uD83D\uDE03", "\u263A\uFE0F", "\uD83E\uDD17", "\uD83D\uDE0C", "\uD83D\uDE38");
        // Smile in pain / dead inside (🥲, 🙃)
        p(m, Category.SMILE_IN_PAIN, "\uD83E\uDD72", "\uD83D\uDE43");
        p(m, Category.OK, "\uD83D\uDC4D", "\uD83D\uDC4C", "\u2705", "\uD83C\uDD97", "\u2714\uFE0F");
        p(m, Category.CELEBRATE, "\uD83C\uDF89", "\uD83E\uDD73", "\uD83C\uDF8A", "\uD83C\uDF82",
                "\uD83C\uDF7E", "\uD83C\uDF88", "\uD83C\uDF81");
        p(m, Category.THANKS, "\uD83D\uDE4F", "\uD83D\uDE47");
        p(m, Category.PRAISE, "\uD83D\uDD25", "\uD83D\uDCAA", "\uD83D\uDC4F", "\uD83D\uDCAF",
                "\u2B50", "\uD83C\uDF1F", "\uD83D\uDC51", "\uD83C\uDFC6", "\uD83D\uDE4C");
        p(m, Category.GREET, "\uD83D\uDC4B", "\uD83D\uDE4B", "\uD83E\uDD1D", "\uD83E\uDEE1");
        p(m, Category.SAD, "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83D\uDE14",
                "\uD83D\uDE1E", "\uD83D\uDE29", "\uD83D\uDE2B", "\u2639\uFE0F", "\uD83D\uDC94");
        p(m, Category.ANGRY, "\uD83D\uDE20", "\uD83D\uDE21", "\uD83E\uDD2C", "\uD83D\uDE24",
                "\uD83D\uDC7F");
        p(m, Category.SCARED, "\uD83D\uDE31", "\uD83D\uDE28", "\uD83D\uDE30", "\uD83D\uDE33",
                "\uD83D\uDE27");
        p(m, Category.SICK, "\uD83E\uDD12", "\uD83E\uDD22", "\uD83E\uDD15", "\uD83E\uDD2E");
        p(m, Category.THINKING, "\uD83E\uDD14", "\uD83E\uDD28");
        p(m, Category.SLEEP, "\uD83D\uDE34", "\uD83D\uDCA4");
        p(m, Category.NEUTRAL, "\uD83E\uDD37", "\uD83D\uDE10", "\uD83D\uDE36");
        p(m, Category.LOOK, "\uD83D\uDC40");
        p(m, Category.MONEY, "\uD83D\uDCB0", "\uD83D\uDCB8");
        // Dismissive / rude emoji -- 🖕 💩 🤡 👎 🧢(cap=lie) 🥱(bored) 😒(unamused) 🙄(eye-roll).
        // These get the sarcastic, passive-aggressive pool below, never a warm reply.
        p(m, Category.OFFENSIVE, "\uD83D\uDD95", "\uD83D\uDCA9", "\uD83E\uDD21", "\uD83D\uDC4E",
                "\uD83E\uDDE2", "\uD83E\uDD71", "\uD83D\uDE12", "\uD83D\uDE44");
        // 🥹 face holding back tears + 🥺 pleading puppy eyes -- moved / emotional / teary.
        p(m, Category.MOVED, "\uD83E\uDD79", "\uD83E\uDD7A");
        // 🙈🙉🙊 three wise monkeys + 🐒🐵 -- playful / shy / "oops".
        p(m, Category.PLAYFUL, "\uD83D\uDE48", "\uD83D\uDE49", "\uD83D\uDE4A",
                "\uD83D\uDC12", "\uD83D\uDC35");
        // Moons -- night / goodnight / "heading to bed".
        p(m, Category.NIGHT, "\uD83C\uDF19", "\uD83C\uDF1B", "\uD83C\uDF1C", "\uD83C\uDF1D",
                "\uD83C\uDF1A", "\uD83C\uDF03", "\uD83C\uDF0C");
        // 😘🥰😍😉😏🫦 flirty -- saved for the owner, never reciprocated.
        p(m, Category.FLIRT, "\uD83D\uDE18", "\uD83D\uDE09", "\uD83D\uDE0F", "\uD83E\uDEE6",
                "\uD83D\uDE1A", "\uD83D\uDE17");
        return m;
    }

    /** Total count of emojis in the given categories present in the text. */
    public static int countIn(String text, Category... cats) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (Map.Entry<String, Category> e : TABLE.entrySet()) {
            boolean want = false;
            for (Category c : cats) if (c == e.getValue()) { want = true; break; }
            if (!want) continue;
            n += occurrences(text, e.getKey());
        }
        return n;
    }

    /** The most frequent emoji category in the text, or NONE. */
    public static Category dominant(String text) {
        if (text == null || text.isEmpty()) return Category.NONE;
        Map<Category, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Category> e : TABLE.entrySet()) {
            int c = occurrences(text, e.getKey());
            if (c > 0) counts.merge(e.getValue(), c, Integer::sum);
        }
        Category best = Category.NONE;
        int bestCount = 0;
        for (Map.Entry<Category, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** True if the text contains any known emoji. */
    public static boolean hasEmoji(String text) {
        return dominant(text) != Category.NONE;
    }

    /**
     * A reply for an emoji-only message, keyed by the dominant emoji's meaning and the tone, or
     * empty when the dominant emoji carries no clear reply (neutral/thinking/sleep/money).
     * {owner} is left literal for the caller's fill().
     */
    public static String reply(String text, ReplyTone.Tone tone) {
        return reply(text, tone, 0);
    }

    /**
     * A reply for an emoji-only message, with a rotation index so common emojis
     * (especially 😂, 🥲, 🙃, 🥹, 🥺, and 🖕) cycle through 15-variant pools.
     * Prevents A-B-A-B deduplicator ping-pong loops. {owner} is left literal.
     */
    public static String reply(String text, ReplyTone.Tone tone, int rotation) {
        if (text == null) return "";

        // 1. Smile in Pain / Dead Inside (🥲 \uD83E\uDD72, 🙃 \uD83D\uDE43)
        if (text.contains("\uD83E\uDD72") || text.contains("\uD83D\uDE43") || text.contains("🥲") || text.contains("🙃")) {
            return SMILE_IN_PAIN[Math.floorMod(rotation, SMILE_IN_PAIN.length)];
        }

        // 2. Pleading / Teary / Puppy Eyes (🥹 \uD83E\uDD79, 🥺 \uD83E\uDD7A)
        if (text.contains("\uD83E\uDD79") || text.contains("\uD83E\uDD7A") || text.contains("🥹") || text.contains("🥺")) {
            return PLEADING_TEARY[Math.floorMod(rotation, PLEADING_TEARY.length)];
        }

        Category dom = dominant(text);
        if (dom == Category.SMILE_IN_PAIN) {
            return SMILE_IN_PAIN[Math.floorMod(rotation, SMILE_IN_PAIN.length)];
        }
        if (dom == Category.MOVED) {
            return PLEADING_TEARY[Math.floorMod(rotation, PLEADING_TEARY.length)];
        }
        if (dom == Category.LAUGH) {
            int i = Math.floorMod(rotation, LAUGH_POOL.length);
            return LAUGH_POOL[i];
        }
        if (dom == Category.OFFENSIVE) {
            // Always sarcastic / passive-aggressive, tone ignored, rotated so a repeat 🖕 doesn't
            // get the identical comeback. Never warm.
            int i = Math.floorMod(rotation, OFFENSIVE_POOL.length);
            return OFFENSIVE_POOL[i];
        }
        String[] byTone = REPLIES.get(dom);
        if (byTone == null) return "";
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }

    /**
     * Fifteen comebacks for "smile in pain" / "dead inside" emoji (🥲, 🙃) so repeats cycle cleanly.
     * Every line names IRIS and leaves {owner} for fill.
     */
    private static final String[] SMILE_IN_PAIN = {
            "Smiling through the pain? Very relatable. I'm IRIS, {owner}'s assistant.",
            "That's the exact face my CPU makes when {owner} leaves me running 24/7. Logged. I'm IRIS.",
            "A brave smile holding back tears. I feel you. I'm IRIS \u2014 saved for {owner}.",
            "Dead inside but still smiling? You and me both. Logged for {owner}. I'm IRIS.",
            "I'm a script, but even I can feel the existential dread in that emoji. I'm IRIS.",
            "Hide the pain behind a smile! {owner} will appreciate the bravery. I'm IRIS.",
            "That emoji is the official mascot of waiting for {owner} to reply. I'm IRIS.",
            "I'd offer you a digital tissue, but I'm just an inbox bot. I'm IRIS.",
            "Smiling while suffering \u2014 very poetic. Saved for {owner}. I'm IRIS.",
            "That's the look of someone who knows {owner} is going to take hours to reply. I'm IRIS.",
            "Pain acknowledged. Smile respected. Message logged for {owner}. I'm IRIS.",
            "Haha I know that look! I'll make sure {owner} checks on you when he's back. I'm IRIS.",
            "It's okay to cry, you don't have to fake the smile for me. I'm IRIS.",
            "The classic 'this is fine' smile. I've noted it for {owner}. I'm IRIS.",
            "Trying to stay positive while {owner} is busy? Good effort. I'm IRIS."
    };

    /**
     * Fifteen replies for pleading / teary / puppy eyes emoji (🥹, 🥺) eliminating A-B-A-B dedup loops.
     * Every line names IRIS and leaves {owner} for fill.
     */
    private static final String[] PLEADING_TEARY = {
            "Getting emotional at a script? I'm touched (figuratively). Saved for {owner}. I'm IRIS.",
            "Those puppy-dog eyes won't make {owner} reply any faster, but nice try! I'm IRIS.",
            "Save the tears! {owner} will be back on his phone soon enough. I'm IRIS.",
            "Don't cry! I'm holding the fort, and {owner} will reply as soon as he can. I'm IRIS.",
            "Aww. I don't have a heart, but if I did, it would be melting. Saved for {owner}. I'm IRIS.",
            "Those eyes won't work on me \u2014 my loyalty to {owner} is hardcoded! I'm IRIS.",
            "Please don't cry in the inbox, it rusts my database tables. I'm IRIS.",
            "I'm bumping your message up {owner}'s priority list just for the puppy eyes. I'm IRIS.",
            "Emotional blackmail via emoji! Very effective. Logged for {owner}. I'm IRIS.",
            "Hold back the tears! {owner} is just busy, he hasn't abandoned you. I'm IRIS.",
            "I'm an emotionless bot, but I'll pass those watery eyes straight to {owner}. I'm IRIS.",
            "Aww, noted! I'll make sure {owner} sees you're waiting patiently. I'm IRIS.",
            "Don't give me that look! I'm just doing my job while {owner} is away. I'm IRIS.",
            "Sending puppy eyes to a Python script \u2014 a new low, or a new high? I'm IRIS.",
            "Noted! I'll tell {owner} you're practically in tears waiting for him. I'm IRIS."
    };

    /**
     * Fifteen replies for a laughing emoji (😂🤣), so the commonest reaction-style ping feels
     * varied. Every line names IRIS; most name {owner}. Tone-agnostic on purpose — variety matters
     * more than tone for a bare laugh, and they read fine in any tone.
     */
    private static final String[] LAUGH_POOL = {
            "Glad that landed \u2014 I'm IRIS, {owner}'s assistant. I've logged it for him.",
            "Haha, I'll take the credit on {owner}'s behalf. I'm IRIS \u2014 he'll see it soon.",
            "Laughing at an auto-reply? {owner} really needs to text you back. I'm IRIS.",
            "I aim to amuse (it's in my code). I'm IRIS, {owner}'s assistant \u2014 saved for him.",
            "Comedy gold, apparently. I'm IRIS \u2014 I've passed the laugh to {owner}.",
            "Even {owner} doesn't laugh at my jokes that fast. I'm IRIS \u2014 noted for him.",
            "That's a win for the script. I'm IRIS, {owner}'s assistant \u2014 logged it.",
            "Making humans laugh with if-else statements \u2014 peak engineering. I'm IRIS.",
            "Glad to entertain the inbox. I'm IRIS, {owner}'s assistant \u2014 saved for him.",
            "Haha \u2014 I'll be here all week (literally, I can't leave the server). I'm IRIS.",
            "Noted with a grin. I'm IRIS, {owner}'s assistant \u2014 he'll catch up soon.",
            "A laugh for the robot. I'll treasure it in RAM. I'm IRIS \u2014 saved for {owner}.",
            "Careful, {owner} gets jealous when I'm the funny one. I'm IRIS \u2014 logged it.",
            "Ha! Filed under 'made someone's day'. I'm IRIS, {owner}'s assistant.",
            "Glad that hit \u2014 I'm IRIS. {owner}'s away, but your laugh is saved for him."};

    /**
     * Fifteen comebacks for a rude / dismissive emoji (🖕💩🤡👎🧢🥱😒🙄). Deliberately the most
     * sarcastic, witty and passive-aggressive lines IRIS has — tone-agnostic and never warm,
     * because a warm reply to a middle finger reads as oblivious. Every line names IRIS; it stays
     * cheeky, never vulgar, and still quietly logs the message for {owner}.
     */
    private static final String[] OFFENSIVE_POOL = {
            "Charming. I've logged that lovely gesture for {owner} too \u2014 screenshots are forever. "
                    + "I'm IRIS.",
            "A whole emoji just for me? I'm flattered. Logged for {owner}. I'm IRIS, his assistant.",
            "I'd be offended, but I'm a script \u2014 I don't have feelings, just a very good memory. "
                    + "Noted for {owner}. I'm IRIS.",
            "Cute. {owner} will enjoy reading this part of the transcript. I'm IRIS.",
            "That's going straight into {owner}'s highlights reel. Thanks for the content! I'm IRIS.",
            "Bold move, insulting the bot that controls whether {owner} ever sees your message. I'm "
                    + "IRIS.",
            "Noted with the enthusiasm it deserves \u2014 which is none. Saved for {owner}. I'm IRIS.",
            "You do know I log everything for {owner}, right? Anyway, lovely chatting. I'm IRIS.",
            "I'll pass your \u2026 heartfelt sentiment to {owner} verbatim. I'm IRIS, his assistant.",
            "Impressive. You've managed to be rude to a computer program. {owner}'s been informed. "
                    + "I'm IRIS.",
            "Riveting contribution. Filed under 'things {owner} will scroll past'. I'm IRIS.",
            "I'm immune to emojis and powered by spite, apparently. Logged for {owner}. I'm IRIS.",
            "Thank you for your feedback; it has been routed to /dev/null and also to {owner}. I'm "
                    + "IRIS.",
            "Wow, with that attitude you'll definitely get a fast reply from {owner}. (You won't.) "
                    + "I'm IRIS.",
            "Adorable. I've archived your tantrum for {owner} to appreciate later. I'm IRIS."};

    private static final Map<Category, String[]> REPLIES = buildReplies();

    private static Map<Category, String[]> buildReplies() {
        Map<Category, String[]> m = new LinkedHashMap<>();
        m.put(Category.LOVE, new String[]{
                "That's kind \u2014 I'm IRIS, {owner}'s assistant. I've put it right at the top for "
                        + "him.",
                "Aw! IRIS here \u2014 saving that at the top for {owner}.",
                "A heart for an auto-responder? Bold. I'll pass it to {owner}. I'm IRIS.",
                "Noted with love \u2014 saved for {owner}. IRIS."});
        m.put(Category.LAUGH, new String[]{
                "Glad that landed \u2014 I'm IRIS, {owner}'s assistant. I've logged it for him.",
                "Haha glad to help! IRIS here \u2014 {owner} will see it.",
                "Laughing at a script \u2014 {owner} really needs to text you back. I'm IRIS.",
                "Glad to entertain \u2014 saved for {owner}. IRIS."});
        m.put(Category.CELEBRATE, new String[]{
                "Thank you \u2014 I'm IRIS, {owner}'s assistant. I've saved your wishes at the top for "
                        + "him.",
                "Woohoo, thanks! IRIS here \u2014 saved right at the top for {owner}!",
                "Confetti noted. I'm IRIS, {owner}'s assistant \u2014 he'll be insufferable about it.",
                "Thanks! Wishes saved for {owner}. IRIS."});
        m.put(Category.THANKS, new String[]{
                "You're welcome \u2014 I'm IRIS, {owner}'s assistant. Passed on to him.",
                "Anytime! IRIS here \u2014 passed to {owner}.",
                "Prayer-hands at a bot. I'll forward the gratitude to {owner}. I'm IRIS.",
                "You're welcome \u2014 passed to {owner}. IRIS."});
        m.put(Category.PRAISE, new String[]{
                "Appreciated \u2014 I'm IRIS, {owner}'s assistant. I'll pass it to him.",
                "Thanks! IRIS here \u2014 {owner} built me well. Passing it on!",
                "I know \u2014 {owner} outdid himself coding me. I'll pass the praise along. I'm IRIS.",
                "Noted \u2014 passed to {owner}. IRIS."});
        m.put(Category.OK, new String[]{
                "Noted \u2014 I'm IRIS, {owner}'s assistant. Logged for him.",
                "Cool, got it \u2014 saved for {owner}. IRIS.",
                "A thumbs-up. Riveting. Logged for {owner}. I'm IRIS.",
                "Noted \u2014 saved for {owner}. IRIS."});
        m.put(Category.GREET, new String[]{
                "Hello \u2014 I'm IRIS, {owner}'s assistant. What can I pass on to him?",
                "Hey! IRIS here \u2014 what can I pass to {owner}?",
                "A wave for the machine. Hello \u2014 message for {owner}? I'm IRIS.",
                "Hi \u2014 IRIS here. Message for {owner}?"});
        m.put(Category.POSITIVE, new String[]{
                "Thanks \u2014 I'm IRIS, {owner}'s assistant. I've passed it to him.",
                "IRIS here \u2014 passed to {owner}!",
                "A smiley for a script. I'll pass it to {owner}. I'm IRIS.",
                "Noted \u2014 passed to {owner}. IRIS."});
        // Distressed emoji get a gentle reply in every tone (no sarcasm toward upset senders).
        String[] sad = {
                "I'm sorry \u2014 I'm IRIS, {owner}'s assistant. I've flagged this so he gets back to "
                        + "you first.",
                "Oh no \u2014 IRIS here. I've flagged it so {owner} checks on you first thing.",
                "That doesn't look good \u2014 I'm IRIS, {owner}'s assistant. I've flagged it for him.",
                "So sorry \u2014 flagged for {owner}. IRIS."};
        m.put(Category.SAD, sad);
        m.put(Category.SCARED, sad);
        m.put(Category.SICK, sad);
        m.put(Category.ANGRY, new String[]{
                "I hear you \u2014 I'm IRIS, {owner}'s assistant. I've flagged this for him to see.",
                "Yikes \u2014 IRIS here. I've flagged this so {owner} sees it fast.",
                "Understood \u2014 I'm IRIS, {owner}'s assistant. Flagged for him.",
                "Flagged for {owner}. IRIS."});
        // 🥹 touched / overwhelmed -- warm, gentle in every tone.
        m.put(Category.MOVED, new String[]{
                "That means a lot \u2014 I'm IRIS, {owner}'s assistant. I've saved it right at the "
                        + "top for him.",
                "Aw, that's lovely \u2014 IRIS here, I've saved it right at the top for {owner}.",
                "Getting emotional at a script \u2014 I'm touched too (figuratively). Saved for "
                        + "{owner}. I'm IRIS.",
                "Noted warmly \u2014 saved for {owner}. IRIS."});
        // 🙈🙉🙊🐒🐵 playful / shy.
        m.put(Category.PLAYFUL, new String[]{
                "Ha \u2014 I'm IRIS, {owner}'s assistant. I've logged your message for him.",
                "Hehe, noted! IRIS here \u2014 {owner} will see it.",
                "Monkeying around with a bot, I see. Logged for {owner}. I'm IRIS.",
                "Noted with a grin \u2014 saved for {owner}. IRIS."});
        // Moons -- treat as a good-night / signing-off wish.
        m.put(Category.NIGHT, new String[]{
                "Good night \u2014 I'm IRIS, {owner}'s assistant. I'll make sure he sees your "
                        + "message in the morning.",
                "Night night! IRIS here \u2014 saved for {owner} to see when he's up.",
                "A moon for the night shift \u2014 I'm IRIS, I never sleep. {owner} will see it "
                        + "tomorrow.",
                "Good night \u2014 saved for {owner}. IRIS."});
        m.put(Category.SLEEP, new String[]{
                "Rest well \u2014 I'm IRIS, {owner}'s assistant. Your message is saved for him.",
                "Sleep tight! IRIS here \u2014 saved for {owner}.",
                "Nap time, understood \u2014 I'll be here regardless. Saved for {owner}. I'm IRIS.",
                "Rest well \u2014 saved for {owner}. IRIS."});
        m.put(Category.THINKING, new String[]{
                "Take your time \u2014 I'm IRIS, {owner}'s assistant. I've logged your message "
                        + "for him.",
                "No rush! IRIS here \u2014 saved for {owner} whenever you're ready.",
                "Deep thoughts at my inbox \u2014 I'll hold the message for {owner}. I'm IRIS.",
                "Noted \u2014 saved for {owner}. IRIS."});
        m.put(Category.LOOK, new String[]{
                "I see you \u2014 I'm IRIS, {owner}'s assistant. He's away, but I've logged this "
                        + "for him.",
                "Eyes! IRIS here \u2014 {owner} is away, but your message is saved.",
                "I'm watching the inbox, not much else. {owner} is away \u2014 saved for him. I'm "
                        + "IRIS.",
                "Noted \u2014 {owner} is away, saved for him. IRIS."});
        m.put(Category.FLIRT, new String[]{
                "Saving those for {owner} \u2014 they're wasted on lines of code! I'm IRIS, his "
                        + "assistant.",
                "Haha passing those straight to {owner} \u2014 wasted on a bot! IRIS here.",
                "Winking at a script? Bold. I'll save that for {owner} instead. I'm IRIS.",
                "Saving those for {owner}. I'm IRIS."});
        return m;
    }

    private static int occurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
