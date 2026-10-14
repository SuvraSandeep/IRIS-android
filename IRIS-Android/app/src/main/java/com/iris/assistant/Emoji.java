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
        PLAYFUL, NIGHT, MOVED, LOOK }

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
                "\uD83D\uDE0D", "\u2763\uFE0F");
        p(m, Category.POSITIVE, "\uD83D\uDE0A", "\uD83D\uDE01", "\uD83D\uDE04", "\uD83D\uDE42",
                "\uD83D\uDE03", "\u263A\uFE0F", "\uD83E\uDD17", "\uD83D\uDE0C");
        p(m, Category.OK, "\uD83D\uDC4D", "\uD83D\uDC4C", "\u2705", "\uD83C\uDD97");
        p(m, Category.CELEBRATE, "\uD83C\uDF89", "\uD83E\uDD73", "\uD83C\uDF8A", "\uD83C\uDF82",
                "\uD83C\uDF7E");
        p(m, Category.THANKS, "\uD83D\uDE4F");
        p(m, Category.PRAISE, "\uD83D\uDD25", "\uD83D\uDCAA", "\uD83D\uDC4F", "\uD83D\uDCAF",
                "\u2B50", "\uD83C\uDF1F");
        p(m, Category.GREET, "\uD83D\uDC4B", "\uD83D\uDE4B");
        p(m, Category.SAD, "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83E\uDD7A", "\uD83D\uDE14",
                "\uD83D\uDE1E", "\uD83D\uDE29", "\uD83D\uDE2B", "\u2639\uFE0F");
        p(m, Category.ANGRY, "\uD83D\uDE20", "\uD83D\uDE21", "\uD83E\uDD2C", "\uD83D\uDE24");
        p(m, Category.SCARED, "\uD83D\uDE31", "\uD83D\uDE28", "\uD83D\uDE30", "\uD83D\uDE33",
                "\uD83D\uDE27");
        p(m, Category.SICK, "\uD83E\uDD12", "\uD83E\uDD22", "\uD83E\uDD15", "\uD83E\uDD2E");
        p(m, Category.THINKING, "\uD83E\uDD14", "\uD83E\uDD28", "\uD83D\uDE44");
        p(m, Category.SLEEP, "\uD83D\uDE34", "\uD83D\uDCA4");
        p(m, Category.NEUTRAL, "\uD83E\uDD37", "\uD83D\uDE10", "\uD83D\uDE36");
        p(m, Category.LOOK, "\uD83D\uDC40");
        p(m, Category.MONEY, "\uD83D\uDCB0", "\uD83D\uDCB8");
        // 🥹 face holding back tears -- touched/overwhelmed, usually warm.
        p(m, Category.MOVED, "\uD83E\uDD79");
        // 🙈🙉🙊 three wise monkeys + 🐒🐵 -- playful / shy / "oops".
        p(m, Category.PLAYFUL, "\uD83D\uDE48", "\uD83D\uDE49", "\uD83D\uDE4A",
                "\uD83D\uDC12", "\uD83D\uDC35");
        // Moons -- night / goodnight / "heading to bed".
        p(m, Category.NIGHT, "\uD83C\uDF19", "\uD83C\uDF1B", "\uD83C\uDF1C", "\uD83C\uDF1D",
                "\uD83C\uDF1A", "\uD83C\uDF03", "\uD83C\uDF0C");
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
        String[] byTone = REPLIES.get(dominant(text));
        if (byTone == null) return "";
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }

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
                "Woohoo, thanks! IRIS here \u2014 pinned for {owner}!",
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
                "Aw, that's lovely \u2014 IRIS here, I've pinned it for {owner}.",
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
