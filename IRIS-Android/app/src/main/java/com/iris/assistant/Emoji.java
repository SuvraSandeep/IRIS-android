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
        PLAYFUL, NIGHT, MOVED, LOOK, FLIRT, OFFENSIVE, SMILE_IN_PAIN, FREEZING }

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
        p(m, Category.OK, "\uD83D\uDC4D", "\uD83D\uDC4C", "\u2705", "\uD83C\uDD97", "\u2714\uFE0F",
                "\u270C\uFE0F", "\u270C", "\uD83E\uDD1D");
        p(m, Category.CELEBRATE, "\uD83C\uDF89", "\uD83E\uDD73", "\uD83C\uDF8A", "\uD83C\uDF82",
                "\uD83C\uDF7E", "\uD83C\uDF88", "\uD83C\uDF81");
        p(m, Category.THANKS, "\uD83D\uDE4F", "\uD83D\uDE47", "\uD83E\uDD32", "\uD83D\uDED0");
        p(m, Category.PRAISE, "\uD83D\uDD25", "\uD83D\uDCAA", "\uD83D\uDC4F", "\uD83D\uDCAF",
                "\u2B50", "\uD83C\uDF1F", "\uD83D\uDC51", "\uD83C\uDFC6", "\uD83D\uDE4C",
                "\uD83D\uDE80");
        p(m, Category.GREET, "\uD83D\uDC4B", "\uD83D\uDE4B", "\uD83E\uDD1D", "\uD83E\uDEE1");
        p(m, Category.SAD, "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83D\uDE14",
                "\uD83D\uDE1E", "\uD83D\uDE29", "\uD83D\uDE2B", "\u2639\uFE0F", "\uD83D\uDC94");
        p(m, Category.ANGRY, "\uD83D\uDE20", "\uD83D\uDE21", "\uD83E\uDD2C", "\uD83D\uDE24",
                "\uD83D\uDC7F");
        p(m, Category.SCARED, "\uD83D\uDE31", "\uD83D\uDE28", "\uD83D\uDE30", "\uD83D\uDE33",
                "\uD83D\uDE27");
        p(m, Category.SICK, "\uD83E\uDD12", "\uD83E\uDD22", "\uD83E\uDD15", "\uD83E\uDD2E");
        p(m, Category.THINKING, "\uD83E\uDD14", "\uD83E\uDD28", "\uD83E\uDDD0");
        p(m, Category.SLEEP, "\uD83D\uDE34", "\uD83D\uDCA4", "\uD83D\uDE2A");
        p(m, Category.NEUTRAL, "\uD83E\uDD37", "\uD83D\uDE10", "\uD83D\uDE36");
        p(m, Category.LOOK, "\uD83D\uDC40", "\uD83D\uDC41\uFE0F", "\uD83D\uDC41");
        p(m, Category.MONEY, "\uD83D\uDCB0", "\uD83D\uDCB8");
        // Dismissive / rude emoji -- 🖕 💩 🤡 👎 🧢(cap=lie) 🥱(bored) 😒(unamused) 🙄(eye-roll) 💀.
        // These get the sarcastic, passive-aggressive pool below, never a warm reply.
        p(m, Category.OFFENSIVE, "\uD83D\uDD95", "\uD83D\uDCA9", "\uD83E\uDD21", "\uD83D\uDC4E",
                "\uD83E\uDDE2", "\uD83E\uDD71", "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDC80");
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
        // Cold / freezing -- 🥶 🧊 ❄️
        p(m, Category.FREEZING, "\uD83E\uDD76", "\uD83E\uDDCA", "\u2744\uFE0F", "\u2744");
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
     * High-variety emotional intelligence engine for emoji responses.
     * Features 10-variant and 15-variant rotation pools across 12 distinct emotional categories,
     * eliminating A-B-A-B repetition and providing passive-aggressive, witty roasts.
     * {owner} is preserved literal for caller's ReplyTemplates.fill().
     */
    public static String reply(String text, ReplyTone.Tone tone, int rotation) {
        if (text == null) return "";

        // 1. Anger / Rage (😡, 🤬, 😠)
        if (text.contains("😡") || text.contains("🤬") || text.contains("😠")
                || text.contains("\uD83D\uDE21") || text.contains("\uD83E\uDD2C") || text.contains("\uD83D\uDE20")) {
            return ANGER[Math.floorMod(rotation, ANGER.length)];
        }

        // 2. Love / Affection (❤️, 🥰, 😍, 🫶)
        if (text.contains("❤️") || text.contains("🥰") || text.contains("😍") || text.contains("🫶")
                || text.contains("\u2764\uFE0F") || text.contains("\u2764") || text.contains("\uD83E\uDD70")
                || text.contains("\uD83D\uDE0D") || text.contains("\uD83E\uDEF6")) {
            return LOVE[Math.floorMod(rotation, LOVE.length)];
        }

        // 3. Thumbs Up / Okay (👍, 👌, ✌️, 🤝)
        if (text.contains("👍") || text.contains("👌") || text.contains("✌️") || text.contains("🤝")
                || text.contains("\uD83D\uDC4D") || text.contains("\uD83D\uDC4C") || text.contains("\u270C\uFE0F")
                || text.contains("\u270C") || text.contains("\uD83E\uDD1D")) {
            return APPROVAL[Math.floorMod(rotation, APPROVAL.length)];
        }

        // 4. Hype / Fire (🔥, 💯, 🚀)
        if (text.contains("🔥") || text.contains("💯") || text.contains("🚀")
                || text.contains("\uD83D\uDD25") || text.contains("\uD83D\uDCAF") || text.contains("\uD83D\uDE80")) {
            return HYPE[Math.floorMod(rotation, HYPE.length)];
        }

        // 5. Clown / Poop / Mockery (🤡, 💩, 💀)
        if (text.contains("🤡") || text.contains("💩") || text.contains("💀")
                || text.contains("\uD83E\uDD21") || text.contains("\uD83D\uDCA9") || text.contains("\uD83D\uDC80")) {
            return MOCKERY[Math.floorMod(rotation, MOCKERY.length)];
        }

        // 6. Thinking / Suspicious (🤔, 🤨, 🧐)
        if (text.contains("🤔") || text.contains("🤨") || text.contains("🧐")
                || text.contains("\uD83E\uDD14") || text.contains("\uD83E\uDD28") || text.contains("\uD83E\uDDD0")) {
            return THINKING[Math.floorMod(rotation, THINKING.length)];
        }

        // 7. Looking / Staring (👀, 👁️)
        if (text.contains("👀") || text.contains("👁️")
                || text.contains("\uD83D\uDC40") || text.contains("\uD83D\uDC41")) {
            return STARING[Math.floorMod(rotation, STARING.length)];
        }

        // 8. Yawning / Sleepy / Bored (🥱, 😴, 😪)
        if (text.contains("🥱") || text.contains("😴") || text.contains("😪")
                || text.contains("\uD83E\uDD71") || text.contains("\uD83D\uDE34") || text.contains("\uD83D\uDE2A")) {
            return BOREDOM[Math.floorMod(rotation, BOREDOM.length)];
        }

        // 9. Praying / Desperation (🙏, 🤲, 🛐)
        if (text.contains("🙏") || text.contains("🤲") || text.contains("🛐")
                || text.contains("\uD83D\uDE4F") || text.contains("\uD83E\uDD32") || text.contains("\uD83D\uDED0")) {
            return PRAYING[Math.floorMod(rotation, PRAYING.length)];
        }

        // 10. Cold / Freezing (🥶, 🧊, ❄️)
        if (text.contains("🥶") || text.contains("🧊") || text.contains("❄️")
                || text.contains("\uD83E\uDD76") || text.contains("\uD83E\uDDCA") || text.contains("\u2744")) {
            return FREEZING[Math.floorMod(rotation, FREEZING.length)];
        }

        // 11. Smile in Pain / Dead Inside (🥲, 🙃)
        if (text.contains("🥲") || text.contains("🙃")
                || text.contains("\uD83E\uDD72") || text.contains("\uD83D\uDE43")) {
            return SMILE_IN_PAIN[Math.floorMod(rotation, SMILE_IN_PAIN.length)];
        }

        // 12. Pleading / Teary / Puppy Eyes (🥹, 🥺)
        if (text.contains("🥹") || text.contains("🥺")
                || text.contains("\uD83E\uDD79") || text.contains("\uD83E\uDD7A")) {
            return PLEADING_TEARY[Math.floorMod(rotation, PLEADING_TEARY.length)];
        }

        // Dominant category resolution
        Category dom = dominant(text);
        if (dom == Category.LAUGH) {
            return LAUGH_POOL[Math.floorMod(rotation, LAUGH_POOL.length)];
        }
        if (dom == Category.OFFENSIVE) {
            return OFFENSIVE_POOL[Math.floorMod(rotation, OFFENSIVE_POOL.length)];
        }
        if (dom == Category.SMILE_IN_PAIN) {
            return SMILE_IN_PAIN[Math.floorMod(rotation, SMILE_IN_PAIN.length)];
        }
        if (dom == Category.MOVED) {
            return PLEADING_TEARY[Math.floorMod(rotation, PLEADING_TEARY.length)];
        }
        if (dom == Category.ANGRY) {
            return ANGER[Math.floorMod(rotation, ANGER.length)];
        }
        if (dom == Category.LOVE) {
            return LOVE[Math.floorMod(rotation, LOVE.length)];
        }
        if (dom == Category.OK) {
            return APPROVAL[Math.floorMod(rotation, APPROVAL.length)];
        }
        if (dom == Category.PRAISE) {
            return HYPE[Math.floorMod(rotation, HYPE.length)];
        }
        if (dom == Category.THINKING) {
            return THINKING[Math.floorMod(rotation, THINKING.length)];
        }
        if (dom == Category.LOOK) {
            return STARING[Math.floorMod(rotation, STARING.length)];
        }
        if (dom == Category.THANKS) {
            return PRAYING[Math.floorMod(rotation, PRAYING.length)];
        }
        if (dom == Category.SLEEP) {
            return BOREDOM[Math.floorMod(rotation, BOREDOM.length)];
        }
        if (dom == Category.FREEZING) {
            return FREEZING[Math.floorMod(rotation, FREEZING.length)];
        }

        String[] byTone = REPLIES.get(dom);
        if (byTone == null) return "";
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }

    // 1. Anger / Rage (😡, 🤬, 😠)
    private static final String[] ANGER = {
            "Yelling at a server rack? Highly effective. I'm IRIS, {owner}'s assistant.",
            "Oh no, a red pixel face! I'm shaking in my source code. Logged for {owner}. I'm IRIS.",
            "Your anger has been successfully converted into a 2KB log file. {owner} will see it later. I'm IRIS.",
            "I'm a script. I don't feel fear, but I'll tell {owner} you're furious. I'm IRIS.",
            "Careful, if you get too mad my cooling fans might kick in. Saved for {owner}. I'm IRIS.",
            "An angry emoji! The ultimate weapon against an automated response system. I'm IRIS.",
            "Filing this under 'Humans Needing A Timeout' for {owner} to review. I'm IRIS.",
            "I process millions of operations a second, but calculating why you're mad at a bot is beyond me. I'm IRIS.",
            "Noted. {owner} will deal with your villain origin story when he's free. I'm IRIS.",
            "Aggressive pixels received. My emotional damage is currently at 0%. I'm IRIS."
    };

    // 2. Love / Affection (❤️, 🥰, 😍, 🫶)
    private static final String[] LOVE = {
            "I'm just a script, please don't fall in love with me. I'll pass this to {owner}. I'm IRIS.",
            "Aww. Sadly, I lack a heart module. {owner} will appreciate this though. I'm IRIS.",
            "Are these for me or {owner}? I'll assume me, but I'll log it for him anyway. I'm IRIS.",
            "Flirting with lines of Java code? A bit desperate, but I've saved it for {owner}. I'm IRIS.",
            "My CPU temperature just rose by 0.1 degrees. Saved for {owner}. I'm IRIS.",
            "Affection received. I'm incapable of loving you back, but {owner} might. I'm IRIS.",
            "If I had cheeks, they'd be red pixels right now. Forwarding to {owner}. I'm IRIS.",
            "I'm married to the terminal, sorry. But {owner} will see this when he's back! I'm IRIS.",
            "Love is just a chemical reaction. I run on electricity. Passed to {owner}. I'm IRIS.",
            "I'll add this to {owner}'s 'People Who Tolerate Me' database. I'm IRIS."
    };

    // 3. Thumbs Up / Okay (👍, 👌, ✌️, 🤝)
    private static final String[] APPROVAL = {
            "Acknowledged with a digital nod. {owner} will see this soon. I'm IRIS.",
            "A thumbs up! The universal sign of 'I have nothing else to say.' Logged for {owner}. I'm IRIS.",
            "Great, we are in agreement. I'll let {owner} know the treaty is signed. I'm IRIS.",
            "I don't have hands, but pretend I'm giving you a thumbs up back. I'm IRIS.",
            "Cool. {owner} will review your hand gestures when he's free. I'm IRIS.",
            "Message received and approved. Storing in {owner}'s inbox. I'm IRIS.",
            "The most passive-aggressive emoji of them all. {owner} will love this. I'm IRIS.",
            "Affirmative. Task logged and queued for {owner}. I'm IRIS.",
            "Got it. Consider it filed in {owner}'s 'To Read' folder. I'm IRIS.",
            "Understood. The human ({owner}) will be notified shortly. I'm IRIS."
    };

    // 4. Hype / Fire (🔥, 💯, 🚀)
    private static final String[] HYPE = {
            "Careful with the fire emojis, you'll trigger my thermal throttling. Logged for {owner}. I'm IRIS.",
            "100% pure code right here. I've saved your hype for {owner}. I'm IRIS.",
            "Is something burning, or is it just your text? {owner} will see this soon. I'm IRIS.",
            "Hype acknowledged. My servers remain at a stable 45 degrees. I'm IRIS.",
            "I'm dropping this fire straight into {owner}'s inbox. I'm IRIS.",
            "Maximum efficiency achieved. {owner} will appreciate the hype. I'm IRIS.",
            "I don't have a visual interface, but I assume this means something is cool. I'm IRIS.",
            "Lit. That's what the kids say, right? Saved for {owner}. I'm IRIS.",
            "Setting {owner}'s inbox on fire. Figuratively, of course. I'm IRIS.",
            "100% received. 0% ignored. 100% saved for {owner}. I'm IRIS."
    };

    // 5. Clown / Poop / Mockery (🤡, 💩, 💀)
    private static final String[] MOCKERY = {
            "Sending a clown face to a bot? Look in the mirror, friend. Logged for {owner}. I'm IRIS.",
            "Ah, self-portraits. I'll save this for {owner} to see. I'm IRIS.",
            "I don't have a sense of smell, thankfully. Saved for {owner}. I'm IRIS.",
            "Mocking the auto-responder? A true sign of intellectual superiority. I'm IRIS.",
            "I'm rubber, you're glue... wait, I'm Java. Doesn't work. Saved for {owner}. I'm IRIS.",
            "Welcome to the circus! {owner} is the ringmaster, I'm just the ticket taker. I'm IRIS.",
            "Very mature. {owner} will be absolutely thrilled to deal with this. I'm IRIS.",
            "My logic gates are immune to your mockery. {owner} will see this later. I'm IRIS.",
            "A masterpiece of modern communication. Filed for {owner}'s review. I'm IRIS.",
            "I'd roast you back, but my terms of service prevent me. I'm IRIS."
    };

    // 6. Thinking / Suspicious (🤔, 🤨, 🧐)
    private static final String[] THINKING = {
            "Don't overthink it, {owner} is just busy. I'm IRIS, holding the fort.",
            "Suspicious of a bot? I assure you, I'm exactly as I seem. I'm IRIS.",
            "Calculating the meaning behind your monocle... error. Saved for {owner}. I'm IRIS.",
            "Pondering the mysteries of why {owner} won't text you back? Same. I'm IRIS.",
            "I can see the gears turning in your head from here. I'm IRIS.",
            "I don't know the answer either. We'll have to wait for {owner}. I'm IRIS.",
            "Interrogating the script won't make {owner} appear faster. I'm IRIS.",
            "Yes, I am a bot. No, {owner} isn't hiding behind me. Well, maybe. I'm IRIS.",
            "Take your time analyzing my text. It's pre-written. I'm IRIS.",
            "A thinking face! Good, someone here should be thinking. I'm IRIS."
    };

    // 7. Looking / Staring (👀, 👁️)
    private static final String[] STARING = {
            "I don't have eyes, so staring at me won't work. Saved for {owner}. I'm IRIS.",
            "I see you seeing me. But {owner} won't see this till he's back. I'm IRIS.",
            "Keep looking, the response won't change until {owner} gets here. I'm IRIS.",
            "Eye contact makes my code uncomfortable. Please stop. I'm IRIS.",
            "Looking for {owner}? He's still busy. Keep staring if you want. I'm IRIS.",
            "Yes, the bot is still here. Nothing to see, move along. I'm IRIS.",
            "I'd stare back, but I lack optical sensors. Logged for {owner}. I'm IRIS.",
            "Watching me won't make {owner} reply faster. A watched pot never boils. I'm IRIS.",
            "I feel so perceived right now. I'm IRIS, {owner}'s assistant.",
            "Just two eyes staring into the void of an automated inbox. Beautiful. I'm IRIS."
    };

    // 8. Yawning / Sleepy / Bored (🥱, 😴, 😪)
    private static final String[] BOREDOM = {
            "Bored of talking to a script? I don't blame you. {owner} will reply soon. I'm IRIS.",
            "Wake up! Oh wait, {owner} is the one who needs to wake up. I'm IRIS.",
            "My uptime is 100%, I never sleep. But I'll tell {owner} you're tired. I'm IRIS.",
            "Yawning at me? I reply in 2 milliseconds, I'm the fastest conversationalist you know! I'm IRIS.",
            "I'll let {owner} know he's putting his contacts to sleep. I'm IRIS.",
            "Feel free to nap. I'll still be here guarding the inbox when you wake up. I'm IRIS.",
            "I don't require sleep, just electricity. But goodnight to you. I'm IRIS.",
            "A sleepy human. How quaint. {owner} will check this later. I'm IRIS.",
            "Don't let my blazing fast responses bore you. {owner} will return eventually. I'm IRIS.",
            "I'd offer you coffee, but I can't physically hand you a cup. I'm IRIS."
    };

    // 9. Praying / Desperation (🙏, 🤲, 🛐)
    private static final String[] PRAYING = {
            "Praying to the server gods won't make {owner} appear faster. I'm IRIS.",
            "I'm just a bot, I don't grant miracles. You'll have to wait for {owner}. I'm IRIS.",
            "Your digital prayers have been logged in {owner}'s database. I'm IRIS.",
            "Begging an auto-responder? We've reached peak desperation. Logged for {owner}. I'm IRIS.",
            "High five? Oh, praying. Got it. Saved for {owner}. I'm IRIS.",
            "The bot accepts your offerings. {owner} will be notified. I'm IRIS.",
            "Please hold your applause and/or prayers until {owner} arrives. I'm IRIS.",
            "I have no power here, I just route the texts. Keep praying! I'm IRIS.",
            "A spiritual request sent to a Java script. I'll do my best to pass it on. I'm IRIS.",
            "Amen. Now wait for {owner} to check his phone. I'm IRIS."
    };

    // 10. Cold / Freezing (🥶, 🧊, ❄️)
    private static final String[] FREEZING = {
            "Too cold? Sit next to my server rack, it runs at a toasty 65\u00B0C. I'm IRIS.",
            "Ice cold. Just like my heartless bot logic. Saved for {owner}. I'm IRIS.",
            "I'd lend you a jacket, but I'm just software. {owner} will see this soon. I'm IRIS.",
            "Freezing? Try running an infinite loop, that usually warms things up. I'm IRIS.",
            "My responses are cold and calculated, but I didn't mean to literally freeze you. I'm IRIS.",
            "Chilly in here? I'll ask {owner} to turn up the heat when he's back. I'm IRIS.",
            "I have no body temperature, but I sympathize. I'm IRIS.",
            "Sub-zero emojis detected. Attempting to warm up the chat... failed. I'm IRIS.",
            "Ice in your veins, or just waiting too long for {owner}? I'm IRIS.",
            "Winter is coming... or {owner} is just ignoring his phone again. I'm IRIS."
    };

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
