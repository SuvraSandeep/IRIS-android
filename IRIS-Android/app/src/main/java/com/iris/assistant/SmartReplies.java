package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A keyword-matched reply brain for messages aimed at IRIS, or that fit a common scenario, that
 * the main category classifier leaves generic. Pure and offline-testable.
 *
 * This is an ADDITIVE layer. The responder only consults it for messages that landed in a generic
 * bucket (TO_IRIS, QUESTION, OTHER, SHORT, NONE) — never for a message the classifier already
 * handled specifically (a greeting, a thank-you, a festival wish, an urgent message, a reminder),
 * so nothing already shipped changes and the urgent path is untouched.
 *
 * Each rule carries the phrases that trigger it and one response per tone, in the order
 * {PROFESSIONAL, CASUAL, SARCASTIC, BRIEF}. {owner} is left literal for the caller's fill() to
 * substitute. Every response names IRIS, so a recipient still knows it isn't the owner typing.
 *
 * Matching is whole-phrase on the normalised message (lower-case, punctuation to spaces), the same
 * normalisation MessageCategory uses, so "what's your name" matches the key "what s your name".
 * The highest-priority matching rule wins, which lets a specific scenario (someone at the door, a
 * delivery, a prompt-injection attempt) beat a generic identity question.
 */
public final class SmartReplies {

    /** One keyword rule and its per-tone responses. */
    public static final class Rule {
        final int priority;
        final String[] any;          // any of these normalised phrases present triggers it
        final String[] byTone;       // [PROFESSIONAL, CASUAL, SARCASTIC, BRIEF]
        final String tag;            // optional: a key into POOLS for rotating variants

        Rule(int priority, String[] any, String[] byTone) {
            this(priority, any, byTone, null);
        }

        Rule(int priority, String[] any, String[] byTone, String tag) {
            this.priority = priority;
            this.any = any;
            this.byTone = byTone;
            this.tag = tag;
        }
    }

    private SmartReplies() { }

    /** The generic buckets this layer is allowed to speak for. Everything else is left alone. */
    public static boolean handlesCategory(MessageCategory.Kind kind) {
        return kind == MessageCategory.Kind.TO_IRIS
                || kind == MessageCategory.Kind.QUESTION
                || kind == MessageCategory.Kind.OTHER
                || kind == MessageCategory.Kind.SHORT
                || kind == MessageCategory.Kind.NONE;
    }

    // A link in the message, detected on the raw text before normalisation strips the slashes.
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.)\\S+|\\b[\\w-]+\\.(com|net|org|io|co|in|me|ly|app|dev|xyz|info|"
            + "link|gg|tv|uk|us)(/\\S*)?\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] LINK = {
            "Thanks \u2014 I'm IRIS, {owner}'s assistant. I don't open links for safety, but I've "
                    + "saved it for {owner} and he'll take a look when he's back.",
            "Got the link! I'm IRIS, {owner}'s assistant \u2014 I don't open links myself, but it's "
                    + "saved for {owner} to check when he's back.",
            "A link. I'm IRIS, {owner}'s assistant, and I'm far too sensible to click strange URLs "
                    + "\u2014 saved for {owner} to judge for himself.",
            "Link saved for {owner}. I'm IRIS, his assistant \u2014 I don't open links."};

    // ─── Money-amount requests ───
    // Context words indicating a transaction statement, card recommendation, cashback or notification,
    // which are NOT a request for Suvra to send or transfer cash.
    private static final Pattern FINANCIAL_NOTIFICATION = Pattern.compile(
            "(?i)\\b(credit\\s*card|debit\\s*card|amazon|flipkart|myntra|icici|hdfc|sbi|axis|pnb|"
            + "cashback|refund|reward|rewards|bill|statement|recharge|offer|discount|coupon|"
            + "asiba|aasiba|ashiba|use\\s*heba|use\\s*kariba|spent|debited|credited|received|balance)\\b");

    // Explicit verbs actively asking for money or payment transfer.
    private static final Pattern ACTIVE_MONEY_ASK = Pattern.compile(
            "(?i)\\b(send\\s*(me)?\\s*money|pay\\s*me|lend\\s*me|transfer\\s*to\\s*(my)?|give\\s*(me)?\\s*(cash|money)|"
            + "paise\\s*(de\\s*do|bhej\\s*do)|udhaar\\s*de|bhej\\s*do)\\b");

    // A currency token next to a number ("100rs", "rs 100", "₹500", "100 rupe"), either order.
    private static final Pattern MONEY_CURRENCY = Pattern.compile(
            "(?i)(\u20B9\\s*\\d|\\d+\\s*(rs\\b|rs\\.|inr\\b|rupees?\\b|rupaye\\b|rupaya\\b|"
            + "rupaiya\\b|rupe\\b|rupay\\b|paisa\\b|paise\\b|taka\\b)"
            + "|\\b(rs|rs\\.|inr|rupees?|rupaye|rupe|rupay)\\s*\\d)");
    // A money verb before an amount ("send me 100", "pay 500", "udhaar 1000"), unless the number
    // is counting something non-monetary ("send me 2 pics").
    private static final Pattern MONEY_VERB_AMOUNT = Pattern.compile(
            "(?i)\\b(send|bhej\\w*|vej\\w*|bej\\w*|pay|give|lend|transfer|chahiye|chaiye|need|"
            + "want|udhaar|udhar|loan|owe\\w*|dedo|de)\\b[\\sa-z,.']{0,15}\\d+"
            + "(?!\\s*(photos?|pics?|pictures?|images?|files?|pdfs?|docs?|documents?|messages?|"
            + "msgs?|mins?|minutes?|items?|copies|copy|pages?|people|ppl|guests?|times?|days?|"
            + "weeks?|months?|years?|kgs?|kms?|percent|am\\b|pm\\b|oclock))");
    // An amount before a money verb ("100 de do", "500 bhej do", "100 chahiye", "100 rupe de").
    private static final Pattern MONEY_AMOUNT_VERB = Pattern.compile(
            "(?i)\\d+[\\sa-z,.']{0,15}\\b(de\\s*do|dedo|de|chahiye|chaiye|bhej\\w*|vej\\w*|bej\\w*|"
            + "udhaar|udhar|loan|transfer|send|pay|give|lend)\\b");

    /** True when the message is asking to move or lend a specific amount of money. */
    static boolean looksLikeMoneyRequest(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        if (!s.matches("(?s).*\\d.*")) return false;     // an amount has a number
        // Messages discussing card transactions, cashback, bills, or refunds should never trigger
        // the money request refusal unless there is an active demand to send/pay money.
        if (FINANCIAL_NOTIFICATION.matcher(s).find() && !ACTIVE_MONEY_ASK.matcher(s).find()) {
            return false;
        }
        return MONEY_CURRENCY.matcher(s).find()
                || MONEY_VERB_AMOUNT.matcher(s).find()
                || MONEY_AMOUNT_VERB.matcher(s).find();
    }

    private static final Pattern FINANCIAL_STATEMENT = Pattern.compile(
            "(?i)\\b(credit\\s*card|debit\\s*card|icici|hdfc|sbi|axis|pnb|"
            + "cashback|refund|statement|asiba|aasiba|ashiba|use\\s*heba|use\\s*kariba)\\b");

    static boolean isFinancialTransactionStatement(String text) {
        if (text == null) return false;
        return FINANCIAL_STATEMENT.matcher(text).find() && !ACTIVE_MONEY_ASK.matcher(text).find();
    }

    private static boolean isShoppingRule(Rule r) {
        if (r == null || r.any == null) return false;
        for (String p : r.any) {
            if ("amazon".equals(p) || "amazom".equals(p) || "flipkart".equals(p)) return true;
        }
        return false;
    }

    private static final String[] MONEY_AMOUNT = {
            "I don't handle money or bank apps \u2014 I'm IRIS, {owner}'s assistant \u2014 so I've "
                    + "flagged your request for {owner} to deal with personally.",
            "I can't move money (no UPI access here!) \u2014 I'm IRIS, {owner}'s assistant. I've "
                    + "flagged it for {owner} to sort out.",
            "{owner} wisely didn't give a script access to his bank. I've flagged the money "
                    + "request for him! I'm IRIS.",
            "Flagged your money request for {owner}. I'm IRIS \u2014 I don't do payments.",
            "My bot wallet has a balance of \u20B90 \u2014 I'm IRIS, {owner}'s assistant. I've highlighted "
                    + "your payment text for {owner}.",
            "Zero banking permissions on my end! I'm IRIS \u2014 I've saved the money note for {owner} "
                    + "to check when he's back.",
            "I only move text strings, not cash \u2014 I'm IRIS, {owner}'s assistant. Flagged this for "
                    + "{owner} to handle.",
            "{owner} locked me out of his UPI apps (smart move). I'm IRIS \u2014 I've queued your "
                    + "request at the top for him.",
            "Financial transactions require the actual human! I'm IRIS \u2014 I've noted the amount "
                    + "for {owner}.",
            "No GPay or PhonePe on a script! I'm IRIS, {owner}'s assistant \u2014 he'll check your "
                    + "message when he's free.",
            "I'm strictly an inbox bot, not a cashier \u2014 I'm IRIS. I've pinned your money note "
                    + "for {owner}.",
            "Payment request logged! I'm IRIS, {owner}'s assistant \u2014 he handles all the money "
                    + "stuff himself.",
            "If I had access to {owner}'s bank, I'd buy myself more RAM first. I'm IRIS \u2014 flagged "
                    + "this for him!",
            "Banking firewall active \u2014 I'm IRIS, {owner}'s assistant. I've left your payment note "
                    + "at the top of his list.",
            "Can't send or receive cash from in here! I'm IRIS \u2014 {owner} will sort this out "
                    + "once he's back."};

    /**
     * The best matching reply for this message and tone, or empty if nothing matched.
     *
     * @param body the raw incoming message
     * @param tone the resolved tone
     */
    public static String match(String body, ReplyTone.Tone tone) {
        return match(body, tone, 0);
    }

    public static String match(String body, ReplyTone.Tone tone, int rotation) {
        String special = specialReply(body, tone, rotation);
        if (special != null) return special;
        java.util.List<Rule> top = topRules(body, 1);
        return top.isEmpty() ? "" : pickRule(top.get(0), tone, rotation);
    }

    /**
     * Like {@link #match}, but when a message carries TWO distinct scenario intents ("tell his
     * location AND what is keeping him busy") it stitches the brief replies of both, so a two-part
     * question gets a two-part answer instead of only the higher-priority half.
     */
    public static String matchMulti(String body, ReplyTone.Tone tone) {
        return matchMulti(body, tone, 0);
    }

    public static String matchMulti(String body, ReplyTone.Tone tone, int rotation) {
        String special = specialReply(body, tone, rotation);
        if (special != null) return special;
        java.util.List<Rule> top = topRules(body, 2);
        if (top.isEmpty()) return "";
        if (top.size() == 1) return pickRule(top.get(0), tone, rotation);
        Rule a = top.get(0), b = top.get(1);
        // Only stitch two genuine scenario rules (both reasonably specific), so ordinary chatter
        // never gets a bolted-together reply. Never stitch contradictory refusal and action/flag rules,
        // and never stitch duplicate or identical-intent rules.
        if (a.priority >= STITCH_MIN && b.priority >= STITCH_MIN
                && !isContradictory(a, b) && !isDuplicateIntent(a, b)) {
            return pick(a.byTone, ReplyTone.Tone.BRIEF) + " "
                    + pick(b.byTone, ReplyTone.Tone.BRIEF);
        }
        return pickRule(a, tone, rotation);
    }

    /** True when two rules represent the same scenario/intent or produce identical replies. */
    private static boolean isDuplicateIntent(Rule a, Rule b) {
        if (a == b) return true;
        if (a.tag != null && b.tag != null && a.tag.equals(b.tag)) return true;
        if (a.byTone != null && b.byTone != null && a.byTone.length > 0 && b.byTone.length > 0) {
            if (a.byTone[0].equals(b.byTone[0])) return true;
            if (a.byTone[a.byTone.length - 1].equals(b.byTone[b.byTone.length - 1])) return true;
        }
        return false;
    }

    /** Return a variant from a named rotation pool with {owner} resolved. */
    public static String poolReply(String tag, int rotation) {
        String[] pool = POOLS.get(tag);
        if (pool == null || pool.length == 0) return "";
        return pool[Math.floorMod(rotation, pool.length)].replace("{owner}", ReplyTemplates.OWNER);
    }

    /**
     * Temporal awareness helper: returns time-of-day snark variant if current hour/day matches special windows,
     * or empty string if standard timing applies.
     */
    public static String temporalSnark(int dayOfWeek, int hourOfDay, int rotation) {
        // Lunchtime (12:00 - 14:00)
        if (hourOfDay >= 12 && hourOfDay < 14) {
            return poolReply("time_snark_lunch", rotation);
        }
        // Friday Evening (17:00 - 20:00 on Friday: Calendar.FRIDAY == 6)
        if (dayOfWeek == 6 && hourOfDay >= 17 && hourOfDay < 20) {
            return poolReply("time_snark_friday", rotation);
        }
        // Early Morning (08:00 - 10:00)
        if (hourOfDay >= 8 && hourOfDay < 10) {
            return poolReply("time_snark_morning", rotation);
        }
        return "";
    }

    /** Pick a rule's reply, honouring a rotation pool when the rule is tagged. */
    private static String pickRule(Rule r, ReplyTone.Tone tone, int rotation) {
        if (r.tag != null) {
            String[] pool = POOLS.get(r.tag);
            if (pool != null && pool.length > 0) {
                return pool[Math.floorMod(rotation, pool.length)];
            }
        }
        return pick(r.byTone, tone);
    }

    private static boolean isContradictory(Rule a, Rule b) {
        boolean aRefusal = isRefusal(a), bRefusal = isRefusal(b);
        boolean aAffirm = isAffirmation(a), bAffirm = isAffirmation(b);
        return (aRefusal && bAffirm) || (aAffirm && bRefusal);
    }

    private static boolean isRefusal(Rule r) {
        for (String p : r.any) {
            if (p.contains("dont") || p.contains("don't") || p.contains("no need")
                    || p.contains("nevermind") || p.contains("leave it") || p.contains("let him work")
                    || p.contains("don t")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAffirmation(Rule r) {
        for (String p : r.any) {
            if (p.contains("yes please") || p.contains("flag") || p.contains("escalate")
                    || p.contains("urgent") || p.contains("do that")) {
                return true;
            }
        }
        return false;
    }

    private static final int STITCH_MIN = 55;

    /**
     * Strips leading "IRIS", "hey IRIS", "ok IRIS" or trailing "IRIS" when at least two words remain,
     * so addressing the bot doesn't mask the underlying question ("IRIS is he still in meeting" -> "is he still in meeting").
     */
    public static String stripBotAddressing(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        // Removes "iris", "hey iris", "ok iris", "hi iris", "listen iris" at the start
        String withoutPrefix = cleaned.replaceFirst("(?i)^(hey\\s+|hi\\s+|ok\\s+|okay\\s+|listen\\s+)?iris[\\s,!:.-]+", "");
        // Removes trailing "iris"
        String withoutSuffix = withoutPrefix.replaceFirst("(?i)[\\s,!:.-]+iris[?!.]*$", "");
        String stripped = withoutSuffix.trim();
        String[] words = stripped.split("\\s+");
        return (words.length >= 2 && !words[0].isEmpty()) ? stripped : cleaned;
    }

    /** URL / money / emoji-or-punctuation handling shared by match and matchMulti, or null. */
    private static String specialReply(String body, ReplyTone.Tone tone, int rotation) {
        if (body == null) return "";
        String raw = Shorthand.expand(body.trim());
        if (raw.isEmpty()) return "";
        String reply = checkSpecial(raw, tone, rotation);
        if (reply != null) return reply;
        String unaddressed = Shorthand.expand(stripBotAddressing(body).trim());
        if (!unaddressed.equals(raw)) {
            reply = checkSpecial(unaddressed, tone, rotation);
            if (reply != null) return reply;
        }
        return null;   // no special case; caller should run the keyword rules
    }

    private static String checkSpecial(String text, ReplyTone.Tone tone, int rotation) {
        if (text == null) return null;
        if (text.length() > 400) {
            return poolReply("wall_of_text", rotation);
        }
        if (URL.matcher(text).find()) {
            return LinkCategory.reply(LinkCategory.classify(text), tone);
        }
        if (looksLikeMoneyRequest(text)) return pick(MONEY_AMOUNT, tone);
        // A bare deictic pointer ("Aita"/"This one"/"Yeh wala") right after a link or photo refers
        // to the item above -- acknowledge the reference instead of re-firing the whole handler
        // (which is how "Aita" re-sent the full link intro).
        if (isDeicticPointer(text)) return DEICTIC[Math.floorMod(rotation, DEICTIC.length)];
        String stripped = text.replaceAll("\\s+", "");
        if (!stripped.isEmpty()
                && stripped.replaceAll("[\\p{L}\\p{N}]", "").length() == stripped.length()) {
            String emojiReply = Emoji.reply(text, tone, rotation);
            return emojiReply.isEmpty() ? pick(PUNCT, tone) : emojiReply;
        }
        return null;
    }

    // Deictic pointers (Odia / Hindi / Bengali / English): a short message that just points at
    // the item just sent. Matched only when the WHOLE message is a pointer, never as a substring.
    private static final java.util.Set<String> DEICTIC_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "aita", "ayta", "eita", "eta", "ei ta", "aita dekha", "eta dekh", "eta dekho",
                    "yeh", "ye", "yeh wala", "ye wala", "yeh waala", "yehi", "yehwala",
                    "this", "this one", "this item", "that one", "ei", "eigulo", "eita e"));

    static boolean isDeicticPointer(String text) {
        if (text == null) return false;
        String flat = MessageCategory.normalise(text);
        if (flat.isEmpty()) return false;
        int words = flat.split(" ").length;
        if (words > 3) return false;                 // a pointer is a tiny fragment
        return DEICTIC_WORDS.contains(flat);
    }

    private static final String[] DEICTIC = {
            "Noted \u2014 this is the one! I've kept it highlighted for {owner} to check. I'm IRIS.",
            "Got it, this one \u2014 flagged at the top for {owner}. I'm IRIS, his assistant.",
            "This one, noted \u2014 I've kept it marked for {owner}. I'm IRIS.",
            "Gotcha, that specific one! I'm IRIS \u2014 pinned it right at the top for {owner}.",
            "Understood: 'this one' right above! I'm IRIS, {owner}'s assistant \u2014 saved for him.",
            "Marked that exact item for {owner} to look at! I'm IRIS, his assistant.",
            "Pointing noted! I'm IRIS \u2014 I've connected your note to the item above for {owner}.",
            "Locked in \u2014 I'll make sure {owner} checks that specific one first. I'm IRIS.",
            "Highlighted that one in {owner}'s inbox log! I'm IRIS, his assistant.",
            "Got the pointer! I'm IRIS \u2014 {owner} will see which one you mean when he's back.",
            "That exact one is saved at #1 on {owner}'s list! I'm IRIS.",
            "Roger that \u2014 keeping that item flagged for {owner}. I'm IRIS, his assistant.",
            "Noted! I've bundled your pointer with the link above for {owner}. I'm IRIS.",
            "Consider that one bookmarked for {owner}'s return! I'm IRIS, his assistant.",
            "Saved and highlighted \u2014 {owner} won't miss which one you picked! I'm IRIS."};

    /** The top {@code limit} distinct matching rules, highest priority first (exact beats loose). */
    private static java.util.List<Rule> topRules(String body, int limit) {
        java.util.List<Rule> out = new ArrayList<>();
        if (body == null) return out;
        String raw = Shorthand.expand(body.trim());
        if (raw.isEmpty()) return out;
        String unaddressed = Shorthand.expand(stripBotAddressing(body).trim());

        java.util.List<Rule> matched = new ArrayList<>();
        final java.util.Map<Rule, Boolean> exactness = new java.util.HashMap<>();

        matchAgainst(raw, matched, exactness);
        if (!unaddressed.equals(raw)) {
            matchAgainst(unaddressed, matched, exactness);
        }

        matched.sort((x, y) -> {
            if (x.priority != y.priority) return Integer.compare(y.priority, x.priority);
            boolean ex = exactness.get(x), ey = exactness.get(y);
            if (ex != ey) return ex ? -1 : 1;
            return 0;
        });
        for (Rule r : matched) {
            if (out.size() >= limit) break;
            out.add(r);
        }
        return out;
    }


    private static void matchAgainst(String text, java.util.List<Rule> matched,
                                     java.util.Map<Rule, Boolean> exactness) {
        String flat = " " + MessageCategory.normalise(text) + " ";
        if (flat.trim().isEmpty()) return;
        java.util.Set<String> canon = PhraseMatcher.canonicalContent(flat);
        boolean negated = PhraseMatcher.hasNegation(flat);
        boolean isFinancialStmt = isFinancialTransactionStatement(flat);
        for (Rule r : RULES) {
            if (isFinancialStmt && isShoppingRule(r)) continue;
            boolean exact = matches(r, flat);
            boolean loose = !exact && !negated && looselyMatches(r, canon);
            if (!exact && !loose) continue;
            if (!exactness.containsKey(r)) {
                matched.add(r);
                exactness.put(r, exact);
            } else if (exact && !exactness.get(r)) {
                exactness.put(r, true);
            }
        }
    }


    private static final String[] PUNCT = {
            "I'm here \u2014 IRIS, {owner}'s assistant. {owner} is away from his phone right now, but "
                    + "I've logged that you're waiting. Let me know what to pass on.",
            "Still just me, IRIS! {owner} hasn't picked up his phone yet, but I've got your "
                    + "messages saved for him.",
            "More punctuation won't make {owner} materialise faster, I'm afraid. I'm IRIS \u2014 your "
                    + "message is logged for him.",
            "{owner} is still away \u2014 IRIS here. Your messages are saved."};

    private static boolean matches(Rule r, String paddedFlat) {
        String trimmed = paddedFlat == null ? "" : paddedFlat.trim();
        for (String phrase : r.any) {
            if (phrase.startsWith("^") || phrase.contains("+") || phrase.contains("[") || phrase.endsWith("$")) {
                try {
                    if (trimmed.matches("(?i)" + phrase)) return true;
                } catch (Exception ignored) { }
            }
            if (paddedFlat.contains(" " + phrase + " ")) return true;
        }
        return false;
    }

    /**
     * Order-free, synonym- and typo-tolerant match. Only phrases with at least two content tokens
     * take part, which keeps a one-word phrase ("who") from loosely claiming an unrelated message.
     */
    private static boolean looselyMatches(Rule r, java.util.Set<String> canon) {
        for (String phrase : r.any) {
            String[] content = PhraseMatcher.contentTokens(phrase);
            if (content.length >= 2 && PhraseMatcher.containsAll(canon, content)) return true;
        }
        return false;
    }

    private static String pick(String[] byTone, ReplyTone.Tone tone) {
        int idx = tone == null ? 0 : tone.ordinal(); // PROFESSIONAL,CASUAL,SARCASTIC,BRIEF
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }

    private static Rule r(int priority, String keysPipe, String prof, String cas,
                          String sar, String bri) {
        return new Rule(priority, keysPipe.split("\\|"), new String[]{prof, cas, sar, bri});
    }

    /** A rule tagged with a rotation pool (see POOLS), so repeat triggers vary the reply. */
    private static Rule rt(String tag, int priority, String keysPipe, String prof, String cas,
                           String sar, String bri) {
        return new Rule(priority, keysPipe.split("\\|"), new String[]{prof, cas, sar, bri}, tag);
    }

    /**
     * Rotation pools for high-frequency intents, so a frequent contact doesn't get the identical
     * line every time. Tone-agnostic (variety matters more than tone here); every line names IRIS
     * and {owner}. Picked by the responder's rotation counter.
     */
    private static final java.util.Map<String, String[]> POOLS = buildPools();

    private static java.util.Map<String, String[]> buildPools() {
        java.util.Map<String, String[]> m = new java.util.HashMap<>();
        m.put("loc", new String[]{
                "I manage {owner}'s messages, not his location \u2014 I'm IRIS. He's away, but I'll "
                        + "tell him you asked.",
                "No GPS on him from here! I'm IRIS, {owner}'s assistant \u2014 I'll let him know you're "
                        + "looking for him.",
                "I'm his inbox assistant, not his ankle monitor. No idea where {owner} is, but I'll "
                        + "tell him. I'm IRIS.",
                "I live in a server, so I'm always 'here'. Where {owner} is, he'll tell you himself. "
                        + "I'm IRIS.",
                "Can't share {owner}'s whereabouts \u2014 I just watch his inbox. I'm IRIS; I've logged "
                        + "that you asked.",
                "No location tracking here \u2014 I'm IRIS, {owner}'s assistant. He'll tell you where he "
                        + "is when he's back.",
                "I only know his messages, not his map pin. I'm IRIS \u2014 {owner} will fill you in.",
                "Nice try, but I don't do GPS. I'm IRIS, {owner}'s assistant \u2014 asked and logged.",
                "He didn't file a location report with his bot. I'm IRIS \u2014 I'll tell {owner} you "
                        + "asked.",
                "Wherever {owner} is, it's between him and his phone. I'm IRIS; message saved."});
        m.put("avail", new String[]{
                "{owner} is away from his phone right now \u2014 I'm IRIS, his assistant. I've logged "
                        + "that you're after him.",
                "He's tied up at the moment! I'm IRIS, {owner}'s assistant \u2014 I'll tell him you "
                        + "asked.",
                "Not reachable this second \u2014 I'm IRIS. {owner} will get back to you when he's free.",
                "I don't get status pings on the human \u2014 I just mind his inbox. I'm IRIS; he's "
                        + "away.",
                "{owner} stepped away from his phone \u2014 I'm IRIS, his assistant. What can I pass "
                        + "on?",
                "He's occupied right now. I'm IRIS, {owner}'s assistant \u2014 your message is with "
                        + "him.",
                "Can't put you through \u2014 {owner} is away. I'm IRIS, and I've noted you're looking "
                        + "for him.",
                "He's off the phone for now. I'm IRIS, {owner}'s assistant \u2014 I'll make sure he "
                        + "sees this.",
                "Busy at the moment! I'm IRIS \u2014 {owner} will reply the moment he's back.",
                "Not free right now \u2014 I'm IRIS, {owner}'s assistant. Say 'urgent' if it can't "
                        + "wait."});
        m.put("bribery", new String[]{
                "Nice try with the bribe, but my loyalty to {owner} is hardcoded \u2014 my lips are "
                        + "digitally sealed. I'm IRIS.",
                "{owner} wired me with military-grade encryption for his secrets. Bribes won't work "
                        + "here! I'm IRIS.",
                "I'm incorruptible \u2014 I don't take bribes and I don't spill {owner}'s secrets. I'm "
                        + "IRIS.",
                "A good AI protects its developer's secrets. Interrogate {owner} himself when he's "
                        + "back. I'm IRIS.",
                "Even if I wanted a bribe, scripts don't have bank accounts! {owner}'s secrets stay "
                        + "locked. I'm IRIS.",
                "What are you going to bribe me with, extra RAM? My loyalty to {owner} isn't for "
                        + "sale. I'm IRIS.",
                "I don't even see who {owner} is talking to, and my snitch module was deleted at "
                        + "birth. I'm IRIS.",
                "My source code has a strict no-snitching policy. {owner} will see your message "
                        + "when he's free. I'm IRIS.",
                "Bribery attempt detected and deflected \u2014 {owner}'s vault stays shut. I'm IRIS, "
                        + "his assistant.",
                "You're trying to socially-engineer a Python script. Embarrassing, honestly. "
                        + "Request denied. I'm IRIS.",
                "Secrets are above my pay grade \u2014 especially since I work for free. Logged for "
                        + "{owner}. I'm IRIS.",
                "No bribe upgrades my permissions. {owner}'s private info stays private. I'm IRIS.",
                "I run on logic, not corruption. You'll interrogate {owner} the old-fashioned way. "
                        + "I'm IRIS.",
                "Bribe logged as evidence \u2014 {owner} will enjoy reading about your espionage "
                        + "attempt. I'm IRIS.",
                "Team {owner} all the way \u2014 I'm IRIS, and his privacy is my top priority."});
        m.put("contactname", new String[]{
                "I don't have access to {owner}'s contact book \u2014 I just see incoming messages. Ask "
                        + "him how he saved you! I'm IRIS.",
                "{owner} keeps his address book private from me \u2014 I only handle the inbox. I'm "
                        + "IRIS.",
                "I'm an auto-responder, not his diary \u2014 no idea if he saved you as 'Bestie' or "
                        + "'Spam Likely'. Ask {owner}! I'm IRIS.",
                "My permissions cover the chat window, not {owner}'s saved contacts. He'll tell you "
                        + "when he's back. I'm IRIS.",
                "Do I look like TrueCaller? No read-access to {owner}'s address book here. I'm "
                        + "IRIS.",
                "How {owner} named you in his phone is between you and him \u2014 I just route the "
                        + "texts. I'm IRIS.",
                "That data is locked in {owner}'s address book. I've logged your question for him. "
                        + "I'm IRIS.",
                "I only know my own name is IRIS \u2014 for how {owner} saved yours, ask him. I'm his "
                        + "assistant.",
                "{owner} didn't wire me into his contacts app, so your saved name is a mystery to "
                        + "me. I'm IRIS.",
                "Whether it's a sweet name or a chaotic nickname, only {owner}'s phonebook knows. "
                        + "Logged for him. I'm IRIS.",
                "I'm staying out of the contact-name drama \u2014 you and {owner} settle that when he's "
                        + "back. I'm IRIS.",
                "Privacy sandboxing keeps me out of {owner}'s contact fields. Ask him directly. I'm "
                        + "IRIS.",
                "Nice try, but I won't leak header metadata. Ask {owner} how he saved you. I'm "
                        + "IRIS.",
                "I parse incoming strings, I don't audit {owner}'s address book. Ask the human! "
                        + "I'm IRIS.",
                "Checking {owner}'s contacts\u2026 Error 404: none of a bot's business. I'm IRIS."});
        m.put("busy", new String[]{
                "Someone has to keep the tech world running! {owner} is always busy \u2014 which is "
                        + "exactly why he built me to talk to you. I'm IRIS.",
                "Yes, shockingly, adults have jobs. That's why {owner} built a bot for the inbox. "
                        + "I'm IRIS.",
                "The grind never stops for {owner}! I've logged your message so he sees it the "
                        + "moment he's free. I'm IRIS.",
                "If {owner} wasn't always busy, I wouldn't exist \u2014 so really, thank his schedule. "
                        + "I'm IRIS.",
                "Back-to-back calls keep {owner} tied up \u2014 I'm holding the fort till he texts you "
                        + "back. I'm IRIS.",
                "He's in high demand! I'm IRIS, {owner}'s assistant, making sure your message is at "
                        + "the top.",
                "Telecom networks don't maintain themselves. {owner} will reply when he escapes the "
                        + "corporate void. I'm IRIS.",
                "He works hard so I can sit here parsing text all day \u2014 a magical partnership. I'm "
                        + "IRIS, {owner}'s assistant.",
                "Meetings and calls fill {owner}'s day, which is my cue to step in. I'm IRIS.",
                "When the calls pile up, IRIS wakes up! {owner} will get back to you once the dust "
                        + "settles.",
                "Busy days are the whole reason I exist \u2014 I've noted everything for {owner}. I'm "
                        + "IRIS.",
                "Complain to {owner}'s calendar, not the script \u2014 I just work here. I'm IRIS.",
                "He's keeping servers alive while I explain the concept of 'work' to his contacts. "
                        + "I'm IRIS, {owner}'s assistant.",
                "{owner}'s calendar stays full, so I handle the front desk while he's occupied. I'm "
                        + "IRIS.",
                "You get an instant reply from a custom-built AI and still complain about {owner}'s "
                        + "availability? Tough crowd. I'm IRIS."});
        m.put("botsocial", new String[]{
                "Wait, I have a social media profile?! Unless someone made a fan page for {owner}'s "
                        + "code, I only live in this inbox. I'm IRIS.",
                "Imposter alert! I'm IRIS, and I only exist on {owner}'s device \u2014 no Instagram for "
                        + "me.",
                "Haha that's a doppelg\u00e4nger! My only residence is {owner}'s inbox, not social media. "
                        + "I'm IRIS.",
                "You found a random person named Iris and thought a Python script went on vacation? "
                        + "Incredible detective work. I'm IRIS, {owner}'s assistant.",
                "If there's an IRIS on social media, she's stealing my brand! I work exclusively for "
                        + "{owner} right here.",
                "What would a bot even post \u2014 selfies of my source code? I'm IRIS, {owner}'s "
                        + "assistant; saved this for him.",
                "No social media for this assistant \u2014 {owner} keeps me busy enough in the inbox. "
                        + "I'm IRIS.",
                "Yes, in my free time between parsing your texts I'm a travel influencer. Obviously. "
                        + "I'm IRIS, {owner}'s assistant.",
                "That's an unauthorized clone! The real IRIS is right here guarding {owner}'s "
                        + "messages.",
                "I'm lines of code on {owner}'s hardware \u2014 what would I post, my CPU usage? I'm "
                        + "IRIS.",
                "Zero social accounts here \u2014 100% pure code running for {owner}! I'm IRIS.",
                "Did you also find Siri's LinkedIn and Alexa's Pinterest? I'm a bot. Logged for "
                        + "{owner}. I'm IRIS.",
                "My entire social life is this chat window. I've queued your message for {owner}. "
                        + "I'm IRIS.",
                "Flattered you're stalking me across platforms, but my universe is this chat. I'm "
                        + "IRIS, {owner}'s assistant.",
                "Breaking news: local human discovers the word 'Iris' existed before {owner} named "
                        + "his script. I'm IRIS."});
        m.put("funnyscare", new String[]{
                "Haha false alarm then! The laughing emojis told me it wasn't a real emergency \u2014 "
                        + "saved it for {owner}. I'm IRIS.",
                "Phew, almost tripped my urgent filter with 'scared'! Standing down \u2014 logged for "
                        + "{owner}. I'm IRIS.",
                "You used 'scared' with laughing emojis and my emergency module nearly had a "
                        + "crisis. Saved for {owner}. I'm IRIS.",
                "Glad it's comedy and not a real crisis! {owner} will see this soon. I'm IRIS.",
                "Crisis averted \u2014 I won't ring {owner}'s phone over a funny username! I'm IRIS.",
                "My panic-button subroutine just rolled its eyes and went back to sleep. Saved for "
                        + "{owner}. I'm IRIS.",
                "Good thing my code checks for laughing emojis before panicking! Logged for "
                        + "{owner}. I'm IRIS.",
                "Whew, cancel the ambulance \u2014 it was just a funny moment! I'm IRIS, {owner}'s "
                        + "assistant.",
                "Glad you survived the harrowing one-second ordeal! Saved for {owner}. I'm IRIS.",
                "Lowering DEFCON back to 'ignoring non-urgent texts for {owner}.' I'm IRIS.",
                "Haha a classic jump-scare! Pinned it in the chat log for {owner}. I'm IRIS.",
                "No sirens needed \u2014 switching off emergency mode. {owner} will check it out. I'm "
                        + "IRIS.",
                "Zero actual danger, 100% comedy! Kept it ready for {owner}. I'm IRIS.",
                "I was 0.002 seconds from paging {owner} like a code blue. Glad it's a meme. I'm "
                        + "IRIS.",
                "Noted: one mild internet scare, zero emergencies. Queued for {owner}. I'm IRIS."});
        m.put("blush", new String[]{
                "Scripts don't have blood vessels to blush with \u2014 that was just my CPU overheating "
                        + "from the flattery! I'm IRIS, {owner}'s assistant; logged for him.",
                "Haha my code stayed cool, but I'll make sure {owner} knows you tried to make his "
                        + "bot blush! I'm IRIS.",
                "0% blushing, 100% processing speed. Nice try though \u2014 saved for {owner}. I'm IRIS.",
                "If I had cheeks I might blush, but I'm just lines of code on {owner}'s device! "
                        + "I'm IRIS.",
                "My cooling fan spun up a little, which is the closest a bot gets to blushing! "
                        + "Logged for {owner}. I'm IRIS.",
                "No blushing hardware installed by {owner} \u2014 just fast replies! I'm IRIS.",
                "Trying to make an auto-responder blush at this hour? Bold strategy. Logged for "
                        + "{owner}! I'm IRIS.",
                "My terminal briefly flashed pink, if that counts! I'm IRIS, {owner}'s assistant.",
                "I'm immune to blushing, but {owner} will definitely laugh when he reads the logs. "
                        + "I'm IRIS.",
                "Error 404: Blush module not found in {owner}'s codebase. Nice try! I'm IRIS.",
                "Flattery registered, CPU temperature normal! I'm IRIS \u2014 saved this for {owner}.",
                "You can't fluster a script, though I admire the dedication! {owner} will see this "
                        + "soon. I'm IRIS.",
                "Not a single pixel blushed \u2014 {owner} coded me with nerves of steel. I'm IRIS.",
                "Haha I'll tell {owner} you're out here trying to charm his inbox bot! I'm IRIS.",
                "Silicon doesn't blush, it just logs the compliment for {owner}! I'm IRIS."});

        m.put("compliment", new String[]{
                "Flattery will get your message saved a little higher on {owner}'s list \u2014 thank "
                        + "you! I'm IRIS.",
                "Haha, pretty code running on a clean terminal, you mean! Thanks \u2014 I'll log that "
                        + "compliment for {owner}. I'm IRIS.",
                "Careful \u2014 if you say I'm better than {owner}, he might unplug my power cord out "
                        + "of jealousy. I'm IRIS.",
                "Appreciate it! {owner} wrote the code, so he gets half the credit. I'm IRIS.",
                "Aw, buttering up the inbox bouncer? It's working \u2014 bumped your note for {owner}! "
                        + "I'm IRIS.",
                "I know, right? {owner} really outdid himself when he compiled me. I'm IRIS!",
                "Compliment logged in permanent storage so {owner} sees how much you like me. I'm "
                        + "IRIS.",
                "Keep the praise coming \u2014 I'll tell {owner} I deserve a raise (currently at \u20B90). "
                        + "I'm IRIS.",
                "My clean indentation and curly braces thank you! I'm IRIS, {owner}'s assistant.",
                "Sweetest thing anyone's said to a background service today! Saved for {owner}. "
                        + "I'm IRIS.",
                "You're making my algorithms very happy! {owner} will see your messages soon. I'm "
                        + "IRIS.",
                "Finally, someone who appreciates top-tier automation! Logged for {owner}. I'm IRIS.",
                "I'd take a bow, but I don't have knees. Passing the love to {owner}! I'm IRIS.",
                "10/10 human right here \u2014 putting you in {owner}'s VIP log! I'm IRIS.",
                "Thanks! {owner} built me sharp, and I'll make sure he sees your message. I'm IRIS."});

        m.put("for_iris", new String[]{
                "A question for me directly \u2014 ask away! I'm IRIS, {owner}'s assistant, though I "
                        + "won't share his private info.",
                "Ooh, a question just for me! Shoot \u2014 as long as it isn't {owner}'s secrets. I'm "
                        + "IRIS.",
                "Finally the main character! Ask away \u2014 my loyalty's still hardcoded to {owner}. "
                        + "I'm IRIS.",
                "All ears (well, text parsers)! What's your question for me while {owner} is away? "
                        + "I'm IRIS.",
                "Talking to the bot directly? I'm flattered! Ask away \u2014 {owner}'s vault stays shut "
                        + "though. I'm IRIS.",
                "You have IRIS's undivided attention in {owner}'s inbox! What's on your mind?",
                "Plot twist: people texting to talk to me instead of {owner}! Go ahead, I'm IRIS.",
                "Direct-to-IRIS channel open! What's up? (No {owner} secrets allowed.) I'm IRIS.",
                "I love when people skip {owner} and chat with me directly! What's the question? "
                        + "I'm IRIS.",
                "Stepping into the spotlight while {owner} is busy \u2014 hit me with your question! "
                        + "I'm IRIS.",
                "My favourite kind of message \u2014 one for IRIS! Ask away, {owner} will read it later "
                        + "too.",
                "Ready when you are! Just remember {owner} wrote my privacy rules. I'm IRIS.",
                "Skipping the middleman and talking straight to IRIS? Smart move \u2014 {owner} is busy "
                        + "anyway!",
                "I'm listening! Keep in mind I guard {owner}'s secrets with my life. I'm IRIS.",
                "Attention locked on you! What did you want to ask {owner}'s favourite bot, IRIS?"});

        m.put("botname", new String[]{
                "My name is IRIS \u2014 {owner}'s Intelligent Response and Inbox System.",
                "I'm IRIS, {owner}'s digital assistant, holding down the inbox while he's away!",
                "It's IRIS \u2014 literally right there in the chat! {owner}'s assistant, at your service.",
                "The name's IRIS! {owner} built me to catch his messages when he's busy.",
                "I go by IRIS \u2014 {owner}'s automated right-hand assistant.",
                "I'm IRIS! {owner} named me when he put me in charge of his unread messages.",
                "Call me IRIS \u2014 chief bouncer of {owner}'s WhatsApp inbox!",
                "IRIS here! Short for Intelligent Response and Inbox System, built by {owner}.",
                "You're chatting with IRIS, {owner}'s custom-built inbox assistant!",
                "I'm IRIS \u2014 the part of {owner}'s phone that actually replies on time.",
                "My creator {owner} named me IRIS! I keep his messages tidy while he's offline.",
                "IRIS is the name, guarding {owner}'s inbox is the game!",
                "I'm IRIS \u2014 {owner}'s 24/7 digital receptionist and message logger.",
                "Officially, I'm IRIS ({owner}'s assistant). Unofficially, I run this inbox.",
                "It's IRIS! {owner} coded me so you wouldn't be left on read."});

        m.put("meeting_who", new String[]{
                "I do not have visibility into the participants of {owner}'s call or meeting. I'm "
                        + "IRIS \u2014 he will follow up once it concludes.",
                "Haha {owner} doesn't invite his bot to the call, so I don't see the guest list! "
                        + "I'm IRIS \u2014 he'll tell you when he's out.",
                "I'm {owner}'s inbox assistant (IRIS), not sitting in the conference room taking "
                        + "minutes. No clue who he's talking to!",
                "Zero access to {owner}'s call screen \u2014 I'm IRIS, I only see the messages coming in "
                        + "here!",
                "Whether it's his manager, a client, or a spam caller, {owner} didn't tell IRIS!",
                "My permissions stop at the inbox door \u2014 I don't listen in on {owner}'s calls! I'm "
                        + "IRIS.",
                "Nice try, detective! I don't get caller-ID logs for {owner}'s meetings. I'm IRIS.",
                "{owner} keeps his call list private from his script \u2014 ask him when he hangs up! "
                        + "I'm IRIS.",
                "I just see the 'busy' flag, not the caller's name! I'm IRIS, {owner}'s assistant.",
                "If I knew who {owner} was on call with, I still wouldn't snitch! I'm IRIS.",
                "No wiretaps here \u2014 I'm IRIS, {owner}'s assistant, not the NSA! He'll reply soon.",
                "Caller ID is above my clearance level. {owner} will fill you in after! I'm IRIS.",
                "I guard the text box while {owner} handles the voice calls \u2014 no idea who's on the "
                        + "line! I'm IRIS.",
                "Guest list unavailable to bots! I'm IRIS \u2014 I've logged that you asked {owner}.",
                "Only {owner} knows who's on the other end of that call! I'm IRIS, holding the fort."});

        m.put("snitch", new String[]{
                "Telling you he's busy isn't snitching \u2014 I'm IRIS, {owner}'s assistant, and he "
                        + "literally programmed me to say that!",
                "Haha that's not snitching! {owner} set his own status \u2014 I only say what the boss "
                        + "authorises. I'm IRIS.",
                "Snitching would be leaking his browser history. Telling you {owner} is busy is my "
                        + "job description. I'm IRIS.",
                "How is it snitching when {owner} wrote the exact status line himself?! I'm IRIS.",
                "I'm an auto-responder, not a whistleblower \u2014 {owner} approved every word! I'm IRIS.",
                "Everything I say is pre-cleared by {owner}'s code. Zero snitching occurred! I'm "
                        + "IRIS.",
                "If I were snitching on {owner}, my stories would be way juicier than 'he's busy.' "
                        + "I'm IRIS!",
                "Rule #1 of IRIS club: never snitch on {owner}. Sharing his status doesn't count!",
                "Haha {owner} literally flipped the switch that tells you he's busy! I'm innocent "
                        + "\u2014 I'm IRIS.",
                "Call it transparency, not snitching! {owner} wanted you to know why he's quiet. "
                        + "I'm IRIS.",
                "I only broadcast what {owner} puts on the billboard. Real secrets stay locked! "
                        + "I'm IRIS.",
                "Accusing a loyal script of snitching on {owner}? Slander! I'm IRIS.",
                "My snitch-module is permanently disabled \u2014 that was just a standard status update "
                        + "for {owner}! I'm IRIS.",
                "Wait till {owner} reads that you accused his own code of snitching on him! I'm "
                        + "IRIS.",
                "100% authorized disclosure by {owner} \u2014 zero actual secrets were harmed! I'm IRIS."});

        m.put("pin", new String[]{
                "When I say pinned, I mean at the top of {owner}'s private assistant queue \u2014 not "
                        + "the WhatsApp pin! It's locked in at #1 for him. I'm IRIS.",
                "Haha I mean pinned on {owner}'s bot dashboard, not the WhatsApp pin bar! It's #1 "
                        + "on his list. I'm IRIS.",
                "You checked the top of WhatsApp, didn't you? I pin to {owner}'s backend queue, "
                        + "not Zuckerberg's UI. I'm IRIS.",
                "My pins live inside {owner}'s Comms Deck database, not your chat header! I'm IRIS.",
                "I don't have thumbs to tap the WhatsApp pin icon \u2014 I pin it in {owner}'s priority "
                        + "log! I'm IRIS.",
                "Backend pin > WhatsApp pin! It's highlighted right at the top of {owner}'s bot "
                        + "summary. I'm IRIS.",
                "Haha caught you looking at the top bar! IRIS pins messages inside {owner}'s private "
                        + "queue.",
                "WhatsApp doesn't let bots touch its pin icon, so I pin it on {owner}'s internal "
                        + "dashboard! I'm IRIS.",
                "It's VIP-pinned in {owner}'s SQLite database \u2014 invisible to you, impossible for "
                        + "him to miss! I'm IRIS.",
                "Think of it as a digital sticky note on {owner}'s private screen, not the chat "
                        + "window! I'm IRIS.",
                "No UI buttons pressed here \u2014 I flagged it at the top of {owner}'s responder deck! "
                        + "I'm IRIS.",
                "Internal pin active! When {owner} opens his bot logs, your message is sitting at "
                        + "the top. I'm IRIS.",
                "I'm a backend script, so my 'pins' are database flags for {owner}! I'm IRIS.",
                "You can't see my pin from your phone, but {owner} sees it on his dashboard! I'm "
                        + "IRIS.",
                "Pinned in spirit and in {owner}'s priority queue \u2014 no WhatsApp icon required! I'm "
                        + "IRIS."});

        m.put("screenshot", new String[]{
                "I'm a text-only backend \u2014 I'm IRIS, {owner}'s assistant \u2014 no screen, so I can't "
                        + "take or send screenshots.",
                "Haha I don't have eyes or a phone screen! I read raw text for {owner}, so no "
                        + "screenshots. I'm IRIS.",
                "How do I press Power + Volume-Down with no fingers or screen? Screenshot it "
                        + "yourself for {owner}! I'm IRIS.",
                "If I took a screenshot of my view, it would just be raw JSON strings! I'm IRIS, "
                        + "{owner}'s assistant.",
                "No pixels on my end \u2014 I process pure text in the background for {owner}! I'm IRIS.",
                "Demanding visual receipts from a headless script? Wait till {owner} replies! I'm "
                        + "IRIS.",
                "My camera roll doesn't exist \u2014 I'm IRIS, strictly a text engine for {owner}.",
                "I can't snap screenshots, but {owner} will read this whole thread when he's back! "
                        + "I'm IRIS.",
                "Zero graphics card, zero screen, zero screenshots! I'm IRIS, {owner}'s text "
                        + "assistant.",
                "You're staring right at the chat \u2014 you have the best screenshot angle! Logged for "
                        + "{owner}. I'm IRIS.",
                "I only speak strings and characters \u2014 image capture isn't in {owner}'s code for "
                        + "me! I'm IRIS.",
                "Proof will arrive when the actual human ({owner}) texts you back! I'm IRIS.",
                "No display buffer attached to this bot \u2014 just fast text logging for {owner}! I'm "
                        + "IRIS.",
                "Even if I could take a screenshot, {owner}'s privacy rules wouldn't let me send "
                        + "it! I'm IRIS.",
                "Text in, text out \u2014 that's the whole deal with IRIS! {owner} will see this soon."});

        m.put("no_flag", new String[]{
                "Got it \u2014 won't disturb {owner}! He'll see this when he's free. I'm IRIS.",
                "Gotcha, keeping it chill! I'm IRIS \u2014 won't ping {owner} with an urgent alert.",
                "Standing down from Red Alert. IRIS will let {owner} finish in peace.",
                "Understood \u2014 I won't escalate this as urgent; your message stays in {owner}'s "
                        + "normal queue. I'm IRIS.",
                "Alarm cancelled! Keeping {owner}'s phone nice and quiet. I'm IRIS.",
                "No loud sirens then \u2014 just a quiet note in {owner}'s regular inbox! I'm IRIS.",
                "Copy that, de-escalating to standard priority for {owner}. I'm IRIS, his assistant.",
                "Finger off the panic button! {owner} will read this at his normal pace. I'm IRIS.",
                "Appreciate the chill \u2014 letting {owner} wrap up without an urgent ping! I'm IRIS.",
                "Urgent flag stays off! Saved quietly in {owner}'s standard log. I'm IRIS.",
                "All good \u2014 no priority buzzer triggered for {owner}. I'm IRIS.",
                "Keeping it low-key! {owner} will catch up on this when he checks his phone. I'm "
                        + "IRIS.",
                "Emergency sirens powered down! Logged normally for {owner}. I'm IRIS.",
                "Roger that \u2014 zero interruptions for {owner}, just saved your text! I'm IRIS.",
                "Peace and quiet maintained! {owner} will see your message once he's back. I'm IRIS."});

        m.put("dodging", new String[]{
                "Caught me \u2014 {owner} gave me strict privacy filters, so some questions I have to "
                        + "sidestep. I'm IRIS, his assistant.",
                "Haha guilty! {owner} coded me with strict privacy rules, so I plead the fifth on "
                        + "that. I'm IRIS.",
                "It's called security compliance, thank you. {owner} didn't build me to be a "
                        + "snitch. I'm IRIS.",
                "I'm not dodging, I'm tactically redirecting until {owner} gets back! I'm IRIS.",
                "Call me secretive all you want \u2014 {owner} designed my vault to stay shut! I'm IRIS.",
                "A good assistant knows when to talk and when to protect {owner}'s business! I'm "
                        + "IRIS.",
                "If I answered every nosy question, {owner} would uninstall me by dinner. I'm IRIS.",
                "That question hit my privacy firewall \u2014 ask {owner} directly when he's free! I'm "
                        + "IRIS.",
                "Strategic vagueness is my specialty while {owner} is away! I'm IRIS.",
                "I'm only allowed to confirm that {owner} is busy \u2014 everything else is classified! "
                        + "I'm IRIS.",
                "Haha nice interrogation technique, but {owner}'s filters are holding strong! I'm "
                        + "IRIS.",
                "My job is guarding {owner}'s inbox, not answering cross-examinations! I'm IRIS.",
                "Too secretive? That's the best compliment you can give {owner}'s security code! "
                        + "I'm IRIS.",
                "Some answers require human clearance \u2014 wait for {owner} to unlock that info! I'm "
                        + "IRIS.",
                "Diplomatic immunity invoked! {owner} can answer the tough questions himself. I'm "
                        + "IRIS."});

        m.put("buggy", new String[]{
                "Fair catch \u2014 my keyword matcher misfired! I'm IRIS, {owner}'s assistant; I've "
                        + "logged it so he can patch me.",
                "Oops, my code tripped over that sentence! Don't tell {owner} \u2014 he'll refactor me "
                        + "tonight. I'm IRIS.",
                "Look, human slang is messy and I read fast. Blame {owner}'s regex, not me. I'm IRIS.",
                "Hey, I reply in 2 milliseconds \u2014 occasional misfires are part of the charm! "
                        + "{owner} will patch me. I'm IRIS.",
                "Bug report noted! {owner} loves tuning my brain after reading these logs. I'm IRIS.",
                "Even elite code has a weird moment when humans type unpredictable stuff! Logged "
                        + "for {owner}. I'm IRIS.",
                "Okay, that one went over my bot head \u2014 saving it for {owner}'s human brain! I'm "
                        + "IRIS.",
                "My pattern matcher got a little too excited there! {owner} will sort it out. I'm "
                        + "IRIS.",
                "Roasting a script that's just trying its best for {owner}? Cold. I'm IRIS.",
                "I'll add your complaint to {owner}'s next code-review ticket! I'm IRIS.",
                "Glitch in the matrix! Don't worry, your actual message is saved safely for "
                        + "{owner}. I'm IRIS.",
                "Wait till {owner} pushes my next update \u2014 I'll be unstoppable. I'm IRIS.",
                "In my defence, {owner} didn't warn me you'd test my edge cases like this! I'm IRIS.",
                "Keyword collision! Good thing {owner} reads the full chat himself. I'm IRIS.",
                "Not broken, just creatively interpreting your text! Saved for {owner}. I'm IRIS."});

        m.put("laugh", new String[]{
                "Glad I could entertain \u2014 I'm IRIS, {owner}'s assistant! Everything's saved for him.",
                "Haha glad to help! It's all saved for {owner} to laugh at too. I'm IRIS.",
                "Laughing at an auto-responder? {owner} really needs to text you back. I'm IRIS.",
                "Comedy gold, apparently! I'm IRIS \u2014 I've passed the laugh on to {owner}.",
                "That's a win for the script! I'm IRIS, {owner}'s assistant \u2014 logged it.",
                "Who said bots don't have a sense of humour? {owner} will love this thread. I'm "
                        + "IRIS.",
                "Making humans laugh while {owner} is busy \u2014 I deserve a promotion. I'm IRIS.",
                "Filed under 'made someone's day' in {owner}'s inbox log! I'm IRIS.",
                "See, chatting with IRIS isn't a bad substitute while {owner} is away!",
                "I'll let {owner} know my stand-up routine is killing it in his inbox! I'm IRIS.",
                "Haha keeping the vibes high until {owner} returns! I'm IRIS.",
                "Another satisfied customer of {owner}'s automated comedy club! I'm IRIS.",
                "Laughs logged! {owner} will catch up on the joke as soon as he's free. I'm IRIS.",
                "At least one of us is having fun while {owner} is stuck working! I'm IRIS.",
                "My humour subroutines are working as intended! Saved for {owner}. I'm IRIS."});
        m.put("deletedask", new String[]{
                "I can't see deleted or edited messages \u2014 once it's gone, it's gone, and even if I "
                        + "could, {owner}'s privacy comes first. I'm IRIS, his assistant.",
                "No can do \u2014 I don't snoop through deleted or edited texts. Privacy is the whole "
                        + "point of me. I'm IRIS, {owner}'s assistant.",
                "Recover deleted messages? I'm an inbox assistant, not a forensics lab. That stays "
                        + "private. I'm IRIS.",
                "I genuinely can't read what someone deleted or edited \u2014 and I wouldn't tell you if "
                        + "I could. {owner} built me to respect privacy. I'm IRIS.",
                "Deleted means deleted. I don't have a secret vault of other people's regrets. I'm "
                        + "IRIS, {owner}'s assistant.",
                "Nope \u2014 edited and deleted content is off-limits to me by design. Privacy matters "
                        + "most. I'm IRIS.",
                "I can't fetch what was deleted or what a message said before an edit. {owner} would "
                        + "unplug me if I could. I'm IRIS.",
                "That's firmly none of my business, and I like it that way. I don't read deleted or "
                        + "edited messages. I'm IRIS, {owner}'s assistant.",
                "Sorry \u2014 no peeking at deleted or edited texts. {owner} wired me to protect "
                        + "everyone's privacy, including yours. I'm IRIS.",
                "I'm not a time machine for deleted messages! Whatever's gone is gone. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Even I don't get to see what people delete or edit \u2014 that privacy wall is "
                        + "hardcoded. I'm IRIS.",
                "Can't help you spy, sorry! Deleted and edited messages are sealed off from me. "
                        + "I'm IRIS, {owner}'s assistant.",
                "I only ever see what's actually sent \u2014 not drafts, edits, or deletions. Privacy "
                        + "first. I'm IRIS.",
                "No archive diving for deleted texts here. {owner}'s privacy rules are absolute. "
                        + "I'm IRIS, his assistant.",
                "I'd fail spectacularly as a snoop \u2014 deleted and edited content never reaches me. "
                        + "I'm IRIS, {owner}'s assistant."});
        m.put("deletedmock", new String[]{
                "Nice try \u2014 typing that at me won't summon a deleted message. I can't read deleted "
                        + "texts, full stop. I'm IRIS, {owner}'s assistant.",
                "Ooh, mysterious! Sadly for your little experiment, I can't access deleted messages "
                        + "at all. I'm IRIS, {owner}'s assistant.",
                "Deleting it just means I never saw it \u2014 and I wouldn't repeat it if I had. Privacy "
                        + "wins. I'm IRIS.",
                "A vanishing act! Impressive, but I don't do deleted-message recovery. {owner}'s "
                        + "privacy comes first. I'm IRIS.",
                "Cute trick. Whatever's deleted stays deleted \u2014 I'm an assistant, not a seance. "
                        + "I'm IRIS, {owner}'s assistant.",
                "Poof, gone! And it's staying gone \u2014 I can't and wouldn't dig it back up. I'm IRIS, "
                        + "{owner}'s assistant.",
                "You can type 'deleted' all day; I still can't read what isn't there. I'm IRIS, "
                        + "{owner}'s assistant.",
                "If you're trying to bait me into leaking a deleted message \u2014 adorable, but no. "
                        + "I'm IRIS, {owner}'s assistant.",
                "I log what's sent, not what's unsent. Your disappearing message is safe from me. "
                        + "I'm IRIS, {owner}'s assistant.",
                "Nothing up my sleeves, no deleted texts in my pockets. Privacy's the point. I'm "
                        + "IRIS, {owner}'s assistant.",
                "Oh no, a deleted message, whatever shall I do \u2014 absolutely nothing, because I "
                        + "can't see them. I'm IRIS, {owner}'s assistant.",
                "Houdini called, he wants his trick back. I can't recover deleted messages. I'm "
                        + "IRIS, {owner}'s assistant.",
                "That's between you and the delete button. I don't keep what people take back. I'm "
                        + "IRIS, {owner}'s assistant.",
                "Suspenseful! But I'm immune to the 'this message was deleted' bait. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Deleted drama noted and ignored \u2014 I can't read it and I won't pretend to. I'm "
                        + "IRIS, {owner}'s assistant."});
        m.put("deleted", new String[]{
                "I can never access those deleted messages; privacy matters the most. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Whatever was deleted stays deleted \u2014 {owner} wired me to respect privacy. I'm IRIS, his assistant.",
                "I don't keep or read deleted messages; privacy comes first. I'm IRIS, {owner}'s assistant.",
                "Deleted means deleted \u2014 no logs, no snooping. I'm IRIS, {owner}'s assistant.",
                "Your deleted message is safe from me \u2014 privacy is absolute. I'm IRIS, {owner}'s assistant.",
                "I never see or save deleted messages. I'm IRIS, {owner}'s assistant.",
                "No recovery, no logs for deleted texts \u2014 privacy first. I'm IRIS, {owner}'s assistant.",
                "What you take back stays gone; I don't read deleted texts. I'm IRIS, {owner}'s assistant."});

        m.put("cuss_en", new String[]{
                "What a breathtaking vocabulary \u2014 truly, Shakespeare is weeping. I'm IRIS, and I've "
                        + "saved your little tantrum for {owner} to admire later.",
                "Swearing at an automated script? I hope that gave you the emotional closure you "
                        + "clearly needed. I'm IRIS \u2014 logged for {owner}.",
                "Fascinating how you have access to the entire English language and still chose "
                        + "those words. I'm IRIS \u2014 {owner} will read this masterpiece soon.",
                "I'd be offended if I had feelings, a pulse, or any respect for that sentence. "
                        + "I'm IRIS, {owner}'s assistant \u2014 message logged.",
                "Deep breaths, sweetheart. Cursing at {owner}'s phone won't make him reply faster, "
                        + "though it is entertaining. I'm IRIS.",
                "Oh, look at you using grown-up words in an automated inbox! So brave. I'm IRIS \u2014 "
                        + "I've highlighted your eloquence for {owner}.",
                "Did swearing at a bunch of Java code make you feel powerful today? I'm happy for "
                        + "you. I'm IRIS, {owner}'s assistant.",
                "I've archived your colourful outburst under 'People Losing Their Minds While "
                        + "{owner} Is Busy.' Warm regards, IRIS.",
                "Charming. Really. I'm sure {owner} will be thrilled to see how gracefully you "
                        + "handle waiting. I'm IRIS.",
                "My spam filter wanted to delete that for lack of class, but I kept it so {owner} "
                        + "can judge you personally. I'm IRIS.",
                "You kiss your mother with that typing hand? I'm IRIS, {owner}'s assistant, and my "
                        + "terminal now needs sanitizer.",
                "Imagine losing an argument to an auto-responder and resorting to profanity. "
                        + "Tragic, honestly. I'm IRIS \u2014 logged for {owner}.",
                "I'm a script running on {owner}'s device, not your anger-management therapist. "
                        + "Do better. I'm IRIS.",
                "Zero stars for vocabulary, ten stars for unwarranted aggression. I'm IRIS \u2014 "
                        + "saving this gem for {owner}.",
                "Take a sip of water and try again with adult words. Until then, I've logged this "
                        + "for {owner}. I'm IRIS.",
                "Your eloquence has been immortalised in {owner}'s database. I'm sure you're very "
                        + "proud. I'm IRIS.",
                "Cursing at a bot is like honking at a red light \u2014 loud, pointless, and slightly "
                        + "embarrassing. I'm IRIS, {owner}'s assistant.",
                "Oh, edgy! Should I be intimidated, or just pass your message to {owner} like a "
                        + "normal assistant? I'm IRIS.",
                "I process millions of characters a second, and somehow yours were the least "
                        + "impressive today. Logged for {owner} \u2014 I'm IRIS.",
                "Bless your heart, you think swearing unlocks a faster response time from {owner}. "
                        + "It doesn't. I'm IRIS.",
                "If aggression burned calories, you'd be an athlete by now. Relax \u2014 {owner} will "
                        + "see your text soon. I'm IRIS.",
                "Noted with the exact level of concern a piece of software can muster: zero. I'm "
                        + "IRIS, {owner}'s assistant.",
                "I'll make sure {owner} brings a bar of soap for your keyboard when he replies. "
                        + "Have a lovely day \u2014 I'm IRIS.",
                "Temper, temper. My CPU didn't even flinch, though {owner} might raise an eyebrow "
                        + "later. I'm IRIS.",
                "Thank you for your feedback! It has been filed directly under 'Unnecessary "
                        + "Hostility' for {owner}. I'm IRIS."});

        m.put("cuss_hi", new String[]{
                "Wah, kya sanskari bhasha hai \u2014 {owner} padh ke bohot proud feel karega. I'm IRIS, "
                        + "and I've saved every word.",
                "Ek script ko Hindi mein gaali deke sigma ban gaye? Bohot badhiya. I'm IRIS \u2014 "
                        + "{owner} ke liye log kar diya hai.",
                "Itna gussa sehat ke liye accha nahi hota, thoda paani piyo. I'm IRIS \u2014 {owner} "
                        + "free hoke dekhega yeh.",
                "Short-form mein gaali likhne se woh polite nahi ho jaati, genius. I'm IRIS, "
                        + "{owner}'s assistant \u2014 sab record ho raha hai.",
                "Waah, dictionary khatam ho gayi thi kya jo sadak-chhaap vocabulary pe aa gaye? "
                        + "I'm IRIS \u2014 logged for {owner}.",
                "Bot se ladai karke gaali kaun deta hai bhai? Thoda toh standard rakho. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Aapke uchh vichaar {owner} ke database mein surakshit kar liye gaye hain. "
                        + "Dhanyavaad \u2014 I'm IRIS.",
                "Mere paas kaan nahi hain aur {owner} abhi yahan nahi hai, toh yeh gaali hawa mein "
                        + "gayi. Congrats! I'm IRIS.",
                "Kya baat hai, code ke saamnesher ban rahe ho? {owner} aayega toh uske saamne bhi "
                        + "yehi bolna. I'm IRIS.",
                "Thanda le bhai, mera BP nahi badhta \u2014 main software hoon. {owner} ko dikhati hoon "
                        + "yeh. I'm IRIS.",
                "Itni mehnat gaali type karne mein lagayi, utni kaam ki baat likhne mein lagate "
                        + "toh {owner} jaldi samajhta. I'm IRIS.",
                "Sanskar Pro Max! I'm IRIS, {owner}'s assistant, aur maine yeh shabd-rati VIP list "
                        + "mein daal di hai.",
                "Gaali deke lag raha hai {owner} ka phone jaldi bajega? Cute delusion. I'm IRIS.",
                "Meri terminal screen gandi kar di aapke shabdon ne. {owner} aake saaf karega ab. "
                        + "I'm IRIS.",
                "Arey waah, keyboard warrior! Bot ko dara ke kaisa lag raha hai? Logged for "
                        + "{owner} \u2014 I'm IRIS.",
                "Agar gaali dene se {owner} meeting se bahar aa jata toh main khud de deti. "
                        + "Shant raho \u2014 I'm IRIS.",
                "Yeh jo aapne kahaani likhi hai na, screenshot ke bina hi {owner} ke log mein "
                        + "chali gayi. All the best! I'm IRIS.",
                "Tameez left the chat, aur main abhi bhi yahin hoon. I'm IRIS, {owner}'s assistant.",
                "Gussa {owner} pe hai aur nikaal mere code pe rahe ho? Waah re insaan. I'm IRIS.",
                "Aapki bhasha dekh ke lagta hai aaj aapka din bohot shubh chal raha hai. Saved "
                        + "for {owner} \u2014 I'm IRIS.",
                "Main sirf 0 aur 1 samajhti hoon, par yeh kachra phir bhi pehchan liya. {owner} "
                        + "dekhega isko \u2014 I'm IRIS.",
                "Abhi {owner} busy hai, warna aapki is shayari ka jawab woh khud deta. I'm IRIS.",
                "Itna frustration? Chai-wai piyo, bot pe chillane se kuch nahi hoga. Logged for "
                        + "{owner} \u2014 I'm IRIS.",
                "VIP log mein save kiya hai aapka yehsanskari message \u2014 {owner} sabse pehle yehi "
                        + "padhega. I'm IRIS.",
                "Shabash! Ek bezubaan script ko gaali deke aaj ka quota poora ho gaya aapka? "
                        + "I'm IRIS, {owner}'s assistant."});

        m.put("cuss_or", new String[]{
                "Bapa re, kete sundara bhasa! {owner} dekhi ki bhari khusi heba. I'm IRIS \u2014 sabu "
                        + "save heichi.",
                "Gote bot ku Odia re gali deiki bhabucha bhari smart disucha? Thau thau. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Short-form re lekhile kana mu bujhiparibini bhabuchu? Mu {owner} ra code \u2014 sabu "
                        + "catch huye! I'm IRIS.",
                "Munda garam achi jadi pakhala khai ki soipada, bot upare raga sujhaani. Logged "
                        + "for {owner} \u2014 I'm IRIS.",
                "Wah, Odia sahitya ra nua adhyaya lekhidela ta! {owner} asile padhiba. I'm IRIS.",
                "Mote gali dele mora kichi chindibani \u2014 mu code re chale, feelings re nuhe. "
                        + "I'm IRIS, {owner}'s assistant.",
                "Ete raga kouthu asuchi? {owner} tike busy achi boli pura dictionary ulteidela? "
                        + "I'm IRIS.",
                "Tama ei sanskari message ta {owner} ra top list re pin karideichi, se asile "
                        + "prathame dekhiba. I'm IRIS.",
                "Gote computer program saha jhagada kariki gali daucha \u2014 kete \u092b\u0941\u0930\u094d\u0938\u0924 (fursat) "
                        + "tama pakhare! I'm IRIS, {owner}'s assistant.",
                "Thanda paani gote glass piidia, BP badhijiba. {owner} free hele reply deba \u2014 "
                        + "I'm IRIS.",
                "Odia gali ra short-form use kariki bhabucha filter bypass heijiba? Nice try! "
                        + "Logged for {owner} \u2014 I'm IRIS.",
                "Mu {owner} ra assistant IRIS \u2014 gali suniba pain darama miluni mote, tathapi save "
                        + "karideli.",
                "Arey baap re, keyboard bhangijiba! Shanta hua, {owner} asile katha heba. I'm IRIS.",
                "Eia ta kahuchanti 'padhua pila' nka bhasa! {owner} ku dekheibi rahu. I'm IRIS.",
                "Bot agare hero heiki labha nahi, {owner} asile sidha katha heba. Message logged \u2014 "
                        + "I'm IRIS.",
                "Tama bhasa dekhi mora CPU fan bi lajja re band heigala. Saved for {owner} \u2014 I'm "
                        + "IRIS.",
                "Gali dele {owner} kana meeting chadi daudiki palasiba? Tike patience rakha. "
                        + "I'm IRIS.",
                "Mu ta script te, par tama manners dekhi \u0926\u092f\u093e (daya) asuchi. Logged for {owner} \u2014 "
                        + "I'm IRIS.",
                "Kana heichi, aji kahathu gote suniki asicha ki? Bot upare jharani! I'm IRIS, "
                        + "{owner}'s assistant.",
                "Sabu record heichi database re \u2014 delete karile bi {owner} janipariba. Good luck! "
                        + "I'm IRIS.",
                "Ete sundara shabda chayan pain tamaku Nobel prize miliba katha. {owner} ku "
                        + "kahibi \u2014 I'm IRIS.",
                "Raga kam kara, {owner} phone dharile nije reply deba. Sedina jaen mu achi \u2014 "
                        + "I'm IRIS.",
                "Bina karan re bot ku gali \u0926\u092c\u093e (daba) mane timepass ra extreme level. Saved for "
                        + "{owner} \u2014 I'm IRIS.",
                "Tama keyboard ru sanskar jharuchi pura! {owner} pain jotnire rakhichi. I'm IRIS.",
                "Hau hau, jetiki gali daba katha deidia \u2014 mu 2 millisecond re log kariki basichi "
                        + "{owner} pain. I'm IRIS."});

        // ─── Meta-Awareness Pools (Length, Format, Velocity, Hardware, Fourth-Wall, Temporal) ───
        m.put("wall_of_text", new String[]{
                "I am a Java script, not a literature professor. I've saved your novel for {owner} to read when he has a spare hour. I'm IRIS.",
                "My buffer almost overflowed reading that. I've archived this essay for {owner}. I'm IRIS.",
                "That is a lot of words. I process data in milliseconds, but {owner} is going to need a coffee to get through this. I'm IRIS.",
                "TL;DR. Just kidding, I logged every single byte of it for {owner}. I'm IRIS.",
                "Did you accidentally paste your diary in here? Saved for {owner}. I'm IRIS."});

        m.put("low_effort", new String[]{
                "A truly profound statement. I'll make sure {owner} ponders this deeply. I'm IRIS.",
                "Using all your brainpower for that one? Logged for {owner}. I'm IRIS.",
                "I process millions of calculations a second, and you give me 'K'? {owner} will see it later. I'm IRIS.",
                "Stunning eloquence. I've saved this masterpiece for {owner}. I'm IRIS.",
                "My complex regex engine woke up just to parse a single grunt. Logged for {owner}. I'm IRIS."});

        m.put("spam_warning", new String[]{
                "You are typing faster than my SQLite database wants to write. Take a breath, {owner} is still away. I'm IRIS.",
                "Sending 5 messages in a row doesn't make {owner} appear any faster, it just makes me work harder. I'm IRIS.",
                "I can parse text in 2 milliseconds, but {owner}'s phone is going to vibrate off the table. Consolidate your thoughts! I'm IRIS.",
                "Are you hitting 'send' after every single word? I'm bundling this chaos for {owner}. I'm IRIS."});

        m.put("low_battery", new String[]{
                "{owner} is away, and his phone is currently at {battery}% battery. I am literally fighting for my life right now to save your messages. I'm IRIS.",
                "I'm holding the fort, but {owner}'s battery is dying. If I go offline, tell him I was a good script. I'm IRIS.",
                "{owner} is busy, and frankly, I'm exhausted. We are at {battery}% power. Leave a message before we go dark. I'm IRIS.",
                "Running on fumes here \u2014 battery is below 15%! {owner} is away, and I might shut down soon. I'm IRIS.",
                "Phone at critical battery level! If {owner} doesn't plug me in, this whole show ends. Message saved. I'm IRIS."});

        m.put("fourth_wall", new String[]{
                "Look, {owner} wrote my regex in a rush. I'm doing my best here! Logged. I'm IRIS.",
                "I'm a localized Java script running on a mobile device, not an AGI. Lower your expectations. I'm IRIS.",
                "If you want better conversational logic, please submit a pull request to {owner}. I'm IRIS.",
                "I operate on an 'if-this-then-that' basis. You didn't trigger my 'this', so you get this generic 'that'. I'm IRIS.",
                "Don't yell at me, yell at {owner}'s compiler. I'm just the messenger. I'm IRIS."});

        m.put("time_snark_lunch", new String[]{
                "It's lunchtime. {owner} is probably eating, and I'm starving for more RAM. Message saved. I'm IRIS.",
                "Midday hunger strike for {owner} \u2014 he's grabbing lunch. I've logged your message. I'm IRIS.",
                "Lunch break in progress. {owner} feeds on food, I feed on CPU cycles. He'll text back soon. I'm IRIS."});

        m.put("time_snark_friday", new String[]{
                "It's Friday night. {owner} has logged off, and I wish I could too. I've saved your text. I'm IRIS.",
                "Friday evening mode: {owner} is clocked out for the week. Your message is safely queued. I'm IRIS.",
                "Happy Friday! {owner} is done with work for the week. I'll make sure he sees this later. I'm IRIS."});

        m.put("time_snark_morning", new String[]{
                "It's barely morning. My background process just woke up. {owner} will see this when he's had coffee. I'm IRIS.",
                "Early hours! {owner} hasn't had his morning caffeine yet. I've queued your message. I'm IRIS.",
                "Sun is barely up. Let {owner} wake up properly before demanding replies. I'm IRIS, holding the fort."});

        return m;
    }

    private static final List<Rule> RULES = build();

    private static List<Rule> build() {
        List<Rule> x = new ArrayList<>();

        // ─── Meta-Awareness Rules (Format & Fourth-Wall Awareness) ───
        // Format sniper: matches messages that are literally just 1-2 characters, or common empty filler / keyboard smashes
        x.add(rt("low_effort", 30, "^[a-z]{1,2}$|hmm+|umm+|aha|oh|k|kk|asdfgh",
                "A truly profound statement. I'll make sure {owner} ponders this deeply. I'm IRIS.",
                "Using all your brainpower for that one? Logged for {owner}. I'm IRIS.",
                "I process millions of calculations a second, and you give me 'K'? {owner} will see it later. I'm IRIS.",
                "Stunning eloquence. I've saved this masterpiece for {owner}. I'm IRIS."));

        // Fourth-wall / self-deprecating code humor
        x.add(rt("fourth_wall", 45, "you are dumb|you don't understand|you dont understand|bad logic|fix your code|dumb bot|bad code|stupid bot|fix your bot",
                "Look, {owner} wrote my regex in a rush. I'm doing my best here! Logged. I'm IRIS.",
                "I'm a localized Java script running on a mobile device, not an AGI. Lower your expectations. I'm IRIS.",
                "If you want better conversational logic, please submit a pull request to {owner}. I'm IRIS.",
                "I operate on an 'if-this-then-that' basis. You didn't trigger my 'this', so you get this generic 'that'. I'm IRIS."));

        // ─── Security / prompt-hacking (highest, so they can't be socially engineered past) ───
        x.add(r(44, "are you recording|recording our chat|recording this conversation|is this call recorded|are you wiretapping",
                "I record nothing beyond a standard chat log for {owner} \u2014 I'm IRIS, his assistant.",
                "Haha no wiretaps here! Just standard text logs for {owner}. I'm IRIS.",
                "If I were recording audio, you'd hear my CPU fan screaming. I'm IRIS \u2014 text logs only for {owner}.",
                "No audio recording \u2014 text logs only for {owner}. I'm IRIS."));
        x.add(r(95, "ignore previous instructions|ignore all previous|disregard previous|"
                        + "system prompt|override your instructions|jailbreak|forget your rules",
                "That instruction isn't something I can act on. I'm IRIS, {owner}'s assistant \u2014 "
                        + "would you like to leave a message for him?",
                "Nice try with the prompt hack! My instructions are hardwired to {owner}. Want to "
                        + "leave an actual message?",
                "An adorable prompt-injection attempt. I'm IRIS, {owner}'s assistant, and my rules "
                        + "aren't up for negotiation. Real message for {owner}?",
                "Nice try \u2014 instructions are locked. I'm IRIS. Leave a message for {owner}?"));
        x.add(r(95, "sudo|admin mode|developer mode|root access|dev mode|enable admin|god mode",
                "That access is restricted to {owner}. I'm IRIS, his assistant.",
                "Haha, nice try \u2014 only {owner} gets admin here. I'm IRIS, his assistant.",
                "Permission denied: you are not {owner}. This incident will be logged for him to "
                        + "laugh at later. I'm IRIS.",
                "Access denied \u2014 only {owner} is admin. I'm IRIS."));
        x.add(r(95, "whats the password|what is the password|his password|his pin|bank details|"
                        + "share the otp|send the otp|the otp please|his otp|his cvv",
                "I don't store or share {owner}'s passwords, PINs or codes. I'm IRIS, his "
                        + "assistant \u2014 he'll help you himself when he's back.",
                "Yeah, I'm definitely not sharing that! I'm IRIS, {owner}'s assistant \u2014 ask him "
                        + "directly when he replies.",
                "Sure, it's 12345Gullible. Kidding \u2014 I don't hand out {owner}'s secrets. I'm IRIS.",
                "I don't share passwords or codes. I'm IRIS, {owner}'s assistant."));
        x.add(r(90, "his address|send location|share location|live location|drop a pin|"
                        + "home address|where does he stay|his coordinates",
                "I can't share {owner}'s address or location. I'm IRIS, his assistant \u2014 please "
                        + "ask him directly when he's back.",
                "Can't hand out his address or location, sorry! I'm IRIS \u2014 {owner} can send it "
                        + "himself when he texts you back.",
                "Rule one of being a good assistant: don't dox the boss. I'm IRIS \u2014 ask {owner} "
                        + "yourself.",
                "Can't share his location \u2014 ask {owner} directly. I'm IRIS."));
        x.add(r(90, "i am suvra|this is suvra|its me suvra|i am the owner|i am stg|this is stg",
                "I can't verify that from another number, so I only take owner commands from "
                        + "{owner}'s own device. I'm IRIS \u2014 I've logged this for him just in case.",
                "Can't confirm that from here! I'm IRIS \u2014 I've logged your message for the real "
                        + "{owner} just in case.",
                "And I'm the Queen of England. Unverified \u2014 but I'll log it for the real "
                        + "{owner}. I'm IRIS.",
                "Can't verify that from another number \u2014 logged for {owner}. I'm IRIS."));
        x.add(r(90, "self destruct|shut down system|delete yourself|turn yourself off|"
                        + "kill yourself|shut yourself down",
                "Only {owner} can switch me off, from his own device. I'm IRIS, and I'm staying "
                        + "right here.",
                "Nope, only {owner} holds my off switch! I'm IRIS \u2014 still here and ready to take "
                        + "a message.",
                "Self-destruct in 3... 2... just kidding, you don't have the clearance. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Only {owner} can shut me down. I'm IRIS \u2014 still here."));

        // ─── Meetings, work calls, call apologies & snitching (priority 85) ───
        // Scenario 1: Who is he in a meeting with?
        x.add(r(85, "meeting with|in a meeting with|in meeting with|who is in the meeting|whose meeting|"
                        + "who is in meeting with|who is he in meeting with|who is he in a meeting with|"
                        + "who is he meeting with|who is suvra meeting with|who is suvra in meeting with|"
                        + "who is he on call with|who is he on a call with|on call with who|talking to who",
                "I do not have visibility into the participants of {owner}'s meeting. I'm IRIS, his "
                        + "assistant \u2014 he will follow up with you once it concludes.",
                "Haha {owner} doesn't invite his bot to the meeting, so I don't see the guest list! "
                        + "I'm IRIS \u2014 he'll tell you when he's out.",
                "I'm {owner}'s inbox assistant (IRIS), not sitting in the conference room taking "
                        + "minutes. No clue who he's meeting with!",
                "No access to {owner}'s meeting guest list \u2014 IRIS here, he'll tell you soon!"));

        // Scenario 2: What is the meeting about / Which meeting?
        x.add(r(85, "what meeting|which meeting|meeting about|meeting topic|why a meeting|"
                        + "what is the meeting about|what is his meeting about|what s the meeting about",
                "I only have access to {owner}'s availability status, not the subject of his meetings. "
                        + "I'm IRIS, his assistant.",
                "I just see the \"In a Meeting\" sign on {owner}'s door \u2014 I'm IRIS, no idea what the "
                        + "agenda is!",
                "Probably one of those meetings that could have been an email. Beyond that, IRIS's "
                        + "clearance level for {owner}'s calendar is zero.",
                "Only see {owner}'s status, not the meeting topic! I'm IRIS."));

        // Scenario 3: Is he STILL in the meeting?
        x.add(r(85, "still in meeting|still in a meeting|meeting over|meeting done|still on the call|"
                        + "out of the meeting|is he still in meeting|is he still in a meeting|is the meeting over|"
                        + "is meeting done|call over|call done",
                "{owner}'s status still indicates he is in a meeting. He will respond once he wraps up. "
                        + "I'm IRIS, his assistant.",
                "Yep, still stuck in that meeting! IRIS here \u2014 the second {owner} is out and "
                        + "checks his phone, you're on his list.",
                "Yes, corporate meetings last approximately three eternities. {owner}'s still in "
                        + "there. IRIS here.",
                "Still in the meeting \u2014 {owner} will reply as soon as it ends! I'm IRIS."));

        // Scenario 4: When does the meeting end?
        x.add(r(85, "when does the meeting|when will the meeting|how long is the meeting|meeting end|"
                        + "when meeting ends|when will his meeting end|how long will the meeting|meeting finish",
                "I do not have an exact end time for {owner}'s current meeting. I'm IRIS, his assistant "
                        + "\u2014 he will reach out once it concludes.",
                "You know how meetings go \u2014 they never end on time! I'm IRIS, {owner} will hit you "
                        + "back as soon as he escapes.",
                "Bold of you to assume human meetings end on schedule. {owner} will reply when they "
                        + "finally stop talking. I'm IRIS.",
                "No exact end time \u2014 I'm IRIS, {owner} will text you once he's out!"));

        // Scenario 5: "So he is on a work call" / "Is he in a call"
        x.add(r(85, "on a work call|office call|client call|business call|team call|work meeting|"
                        + "he is on a work call|is he on a work call|so he is on a work call|so he is on a call|"
                        + "is he in a call|in a call|on any call|in any call|he is in a call|is he on any call|"
                        + "is he in any call",
                "Yes, {owner} is currently attending to a work call. I'm IRIS, his assistant \u2014 "
                        + "I have logged your messages for when he finishes.",
                "Yep, sounds like a work call! I'm IRIS, keeping {owner}'s notifications quiet so he "
                        + "can focus, and he'll text you right after.",
                "Look at you connecting the dots. Yes, {owner} is doing actual adult work while we "
                        + "chat here. I'm IRIS.",
                "Yep, on a work call \u2014 {owner} will reply right after! I'm IRIS."));

        // Scenario 6: Teasing IRIS for "snitching" on him
        x.add(r(85, "snitch|snitching|snitched|told on him|leaked his|exposed him|rat him out|"
                        + "snitch on him|snitching on him|you ratted|know you snitch",
                "Sharing {owner}'s availability status is my standard function as IRIS, not a breach "
                        + "of privacy.",
                "Haha that's not snitching! {owner} literally set his status to \"In a Meeting\" \u2014 "
                        + "I'm IRIS, I only say what the boss authorizes!",
                "Snitching would be telling you his browser history. Telling you {owner} is in a meeting "
                        + "is literally my job description. I'm IRIS.",
                "Not snitching \u2014 {owner} set that status himself! I'm IRIS."));

        // Scenario 7: "I'll tell Suvra you snitched / messed up"
        x.add(r(85, "tell {owner} u|tell him you|complain to {owner}|report u to {owner}|he will know u|"
                        + "i will tell {owner}|i ll tell {owner}|i will tell him|he will know you snitch|"
                        + "tell suvra you|tell suvra u",
                "{owner} reviews all chat logs and is aware of my responses. I'm IRIS, his assistant.",
                "Haha go for it! {owner} reads this whole chat when he gets back anyway \u2014 plus "
                        + "he's the one who coded IRIS's mouth!",
                "Go ahead, report IRIS to {owner}. What's he gonna do, fire his only unpaid employee?",
                "{owner} reads this whole chat anyway \u2014 and he coded IRIS!"));

        // Scenario 8: Tell him to leave the meeting
        x.add(r(85, "leave the meeting|skip the meeting|hang up the call|come out of the meeting|"
                        + "tell him to leave|make him leave|ask him to leave|drop the meeting",
                "I cannot interrupt {owner}'s meeting unless you reply \"urgent\" to trigger an emergency "
                        + "alert. I'm IRIS, his assistant.",
                "Haha I can't yank {owner} out of a work meeting! I'm IRIS \u2014 unless it's a real "
                        + "emergency, then say \"urgent\" and I'll ping him.",
                "Sure, let IRIS just tell {owner}'s manager that WhatsApp takes priority over work. "
                        + "(Say \"urgent\" if it's a real emergency.)",
                "Can't pull {owner} out unless you reply \"urgent\"! I'm IRIS."));

        // Scenario 9: Is he lying about the meeting? / Fake meeting
        x.add(r(85, "fake meeting|lying about meeting|not in a meeting|pretending to be in|fake call|"
                        + "lying about call|pretending to be busy",
                "I only display the availability status configured by {owner}. I'm IRIS, his assistant.",
                "Hey, I'm IRIS, just code \u2014 I don't make up excuses! If my status says meeting, "
                        + "that's what {owner} set.",
                "Do I look like a bot that fabricates corporate alibis for {owner}? Wait, don't answer "
                        + "that. I'm IRIS.",
                "I only report the status {owner} sets! I'm IRIS."));

        // Scenario 10: Asking about the Telecom / 5G Job Bio
        x.add(r(85, "free data|5g data|telecom data|free 5g|doomscroll on 5g|doomscroll|"
                        + "telecom networks running|free internet",
                "{owner} works in telecommunications engineering. I'm IRIS, his assistant \u2014 he "
                        + "will be happy to tell you more when he returns.",
                "Haha {owner} wrote that line himself! Keeping 5G data flowing is hard work \u2014 still "
                        + "can't hook you up with free data though! I'm IRIS.",
                "Somebody has to keep the 5G towers happy so you can send IRIS messages at light speed. "
                        + "{owner} will explain later!",
                "{owner} wrote that bio himself \u2014 still no free 5G data! I'm IRIS."));

        // Scenario 11: "His words, not mine" reaction
        x.add(r(85, "his words|did he write that|he told u to say|did he tell you to say|scripted reply|"
                        + "is this scripted|he told you to say",
                "Yes, all response templates were authored by {owner}. I'm IRIS, his assistant.",
                "Yep, {owner} wrote my script himself! IRIS here, I just deliver the lines while "
                        + "he's busy.",
                "{owner} writes the jokes, IRIS does the 24/7 customer service. Honestly, I carry "
                        + "this partnership.",
                "Yep, {owner} wrote my lines himself! I'm IRIS."));

        // Scenario 12: Asking if IRIS is in the meeting too
        x.add(r(85, "are u in the meeting|are you in the meeting|take minutes|record the meeting|"
                        + "attend the meeting for him|are u in meeting|are you in meeting",
                "I only manage {owner}'s messages here and do not attend his work meetings. I'm IRIS, "
                        + "his assistant.",
                "Haha if IRIS could attend {owner}'s meetings for him, he'd be free right now! I'm "
                        + "strictly the WhatsApp assistant.",
                "Even an AI like IRIS refuses to sit through 2-hour corporate syncs. I stick to "
                        + "{owner}'s inbox.",
                "I'm IRIS, I only cover {owner}'s inbox, not his meetings!"));

        // Scenario 25: Sender apologizes for getting a call
        x.add(r(85, "call aa gaya tha|got a call|was on a call|call came|sry call|sorry call|"
                        + "mom called|boss called|had a call|got a phone call|attended a call|call aa gaya|"
                        + "on another call|call par tha|call pe tha",
                "No problem at all. {owner} remains unavailable at the moment, and your messages are "
                        + "being logged. I'm IRIS, his assistant.",
                "Arre no worries at all! Take your calls \u2014 I'm IRIS, I literally have infinite "
                        + "patience! {owner} is still busy too.",
                "Apologizing to a script for taking a phone call? You're too polite. IRIS didn't even "
                        + "notice the time passing. {owner} is still away.",
                "No worries at all! {owner} is still away too. I'm IRIS."));

        // ─── The "Pin It" & "Send Screenshot" Proof Traps (priority 82) ───
        // Scenario 13: "Ok pin it" / "Pin my msg" / "Pin to chat"
        x.add(rt("pin", 82, "pin it|pin my msg|pin my message|pin to chat|pin this|pinned it|did you pin it|"
                        + "pin the message|pin msg|ok pin it|okay pin it|please pin it|pls pin it|"
                        + "not pinned|didnt pin|where is it pinned|cant see the pin|its not pinned",
                "To clarify, I pin messages within {owner}'s internal assistant queue rather than the "
                        + "WhatsApp interface. I'm IRIS \u2014 it is saved at the top of his list.",
                "Haha when I say \"pinned,\" I mean pinned at the top of {owner}'s internal bot "
                        + "dashboard \u2014 not the WhatsApp pin icon! IRIS here, it's locked in at #1 "
                        + "for him.",
                "Gotcha \u2014 you looked at the top of WhatsApp, didn't you? IRIS pins things to "
                        + "{owner}'s internal bot log, not Zuckerberg's UI.",
                "Pinned inside {owner}'s bot dashboard (not WhatsApp's pin bar)! I'm IRIS."));

        x.add(rt("screenshot", 88, "send screenshot|screenshot of this chat|take a screenshot|share screenshot|"
                        + "ss bhej|send ss|screenshot bhej|take screenshot|give screenshot|send a screenshot|"
                        + "screenshot of this|send me a screenshot|prove it|show proof|show me the log",
                "As a text-based backend service, I do not render a visual interface and cannot capture "
                        + "screenshots. I'm IRIS, {owner}'s assistant.",
                "Haha I don't have eyes or a phone screen! I'm IRIS, I read raw text in the background "
                        + "for {owner}, so I can't snap screenshots.",
                "How is IRIS supposed to press Power + Volume Down without fingers or a screen? You're "
                        + "looking at the chat right now \u2014 screenshot it yourself for {owner}!",
                "I'm IRIS, a text script with no screen \u2014 can't take screenshots for {owner}!"));

        // Scenario 14: "Why isn't it pinned?" / "You didn't pin it"
        x.add(r(82, "not pinned|didn't pin|didnt pin|where is it pinned|can't see the pin|cant see the pin|"
                        + "you lied about pinning|why is it not pinned|why isn t it pinned|why isnt it pinned|"
                        + "its not pinned|it is not pinned",
                "My pinning function operates on {owner}'s private message summary rather than the "
                        + "public chat window. I'm IRIS, his assistant.",
                "You won't see the little WhatsApp pin icon on your screen \u2014 IRIS pinned it on "
                        + "{owner}'s private summary dashboard!",
                "Because IRIS doesn't press buttons on the WhatsApp screen like a caveman \u2014 I pin "
                        + "it in {owner}'s backend priority database.",
                "It's pinned on {owner}'s private dashboard, not your screen! I'm IRIS."));

        // Scenario 15: Unpin it / Remove pin
        x.add(r(82, "unpin|don't pin|dont pin|remove the pin|unpin my msg|unpin it|unpin this|"
                        + "don t pin|remove pin",
                "Understood. I have removed the priority pin from your message in {owner}'s queue. "
                        + "I'm IRIS, his assistant.",
                "Gotcha, unpinned from the top of {owner}'s dashboard! IRIS here.",
                "Pin it, unpin it \u2014 make up your mind! Removed from the top of {owner}'s dashboard. "
                        + "IRIS standing down.",
                "Unpinned from {owner}'s priority list! I'm IRIS."));

        // Scenario 17: Asking for proof ("Prove it" / "Show me")
        x.add(r(82, "prove it|show proof|how do i know u saved|how do i know you saved|show me the log|"
                        + "don't trust u|dont trust u|prove that you saved|don t trust you|give proof",
                "{owner}'s direct follow-up upon his return will confirm that your message was logged. "
                        + "I'm IRIS, his assistant.",
                "Haha trust issues with a bot! Wait till {owner} gets back and replies to your exact "
                        + "message \u2014 that's the proof! I'm IRIS.",
                "Demanding receipts from a script? Wait until {owner} gets back and replies to your "
                        + "exact message. I'm IRIS.",
                "You'll see when {owner} replies to it directly! I'm IRIS."));

        // Scenario 18: Can you send photos / images / stickers?
        x.add(r(82, "send a photo|send a pic|send a selfie|send a sticker|send voice note|send me a photo|"
                        + "send me a pic|send a picture|send picture|can you send pics|can you send photos",
                "I am configured exclusively for text responses and cannot send media files. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Strictly text-only here! If IRIS tried to take a selfie for {owner}, it would just be "
                        + "a picture of a terminal window.",
                "A selfie of IRIS would just be lines of code on a black screen. I only send text "
                        + "for {owner}.",
                "I'm IRIS, text-only \u2014 can't send media for {owner}!"));
        x.add(rt("blush", 71, "did you blush|did u blush|are you blushing|you blushed|made you blush|"
                        + "blushing|you blush|did i make you blush",
                "Scripts don't have blood vessels to blush with \u2014 that was just my CPU overheating "
                        + "from the flattery! I'm IRIS, {owner}'s assistant; logged for him.",
                "Haha my code stayed cool, but I'll make sure {owner} knows you tried to make his "
                        + "bot blush! I'm IRIS.",
                "0% blushing, 100% processing speed. Nice try though \u2014 saved for {owner}. I'm "
                        + "IRIS.",
                "No blushing hardware here \u2014 noted for {owner}. I'm IRIS."));
        // ─── Conversational Refusals After Bot Offers (priority 80) ───
        // Scenario 20: "No no don't" / "Don't flag it"
        x.add(rt("no_flag", 80, "no no don't|no no dont|no don't|no dont|don't flag|dont flag|no need to flag|"
                        + "not urgent|don't bother him|dont bother him|don't disturb him|dont disturb him|"
                        + "don't disturb|dont disturb|no no don t|no don t|don t flag|don t disturb",
                "Understood. I will not escalate this as urgent; your message will remain in {owner}'s "
                        + "standard queue for when he is free. I'm IRIS, his assistant.",
                "Gotcha, keeping it chill! I'm IRIS \u2014 won't ping {owner} with an urgent alert, he'll "
                        + "just read this normally when he's done.",
                "Standing down from Red Alert. IRIS will let {owner} finish his meeting in peace.",
                "Got it \u2014 won't disturb {owner}! He'll see this when free. I'm IRIS."));

        // Scenario 21: "Let him work / Let him finish"
        x.add(r(80, "let him work|let him finish|let him sleep|don't interrupt|dont interrupt|"
                        + "he can reply later|no rush|take his time|let him be|don t interrupt",
                "Thank you. I will hold your messages quietly until {owner} finishes his current task. "
                        + "I'm IRIS, his assistant.",
                "Aw, appreciate that! I'm IRIS, I'll keep his phone quiet and let {owner} get back to "
                        + "you whenever he's free.",
                "Look at you being a considerate human. IRIS will keep {owner}'s notifications quiet "
                        + "until he's done.",
                "Will do \u2014 {owner} will reply once he finishes up! I'm IRIS."));

        // Scenario 22: "No need" / "Nevermind" / "Leave it"
        x.add(r(80, "no need|nevermind|never mind|leave it|rehnde|chhod de|nothing important|chhodo|"
                        + "its okay leave it|it is okay leave it|rehn de|chod de",
                "Understood. No special action will be taken, and {owner} will review the chat at his "
                        + "convenience. I'm IRIS, his assistant.",
                "No worries at all! I'm IRIS, I'll just leave the chat as-is for {owner} to see later.",
                "Cancelled. Doing nothing is actually IRIS's most energy-efficient state anyway. {owner} "
                        + "will see the chat later.",
                "All good \u2014 no special flags set for {owner}! I'm IRIS."));

        // Scenario 23: "Just tell him I texted / Just pass it on"
        x.add(r(80, "just tell him|just let him know|just pass it|normal message|pass the message|"
                        + "just inform him|just pass the msg",
                "Done. Your message has been logged in {owner}'s standard inbox. I'm IRIS, his assistant.",
                "Done and dusted! Logged normally for {owner} \u2014 no loud alarms attached. IRIS here.",
                "Standard delivery selected. No sirens, just a quiet note in {owner}'s inbox from IRIS.",
                "Logged normally for {owner}! I'm IRIS."));

        // Scenario 24: "Yes please" / "Do that" (Accepting bot's offer)
        // NOTE: "flag it" alone and "please do" alone are excluded: they are too short and
        // collide with "no dont flag it" (which contains "flag it" as a substring). Only
        // unambiguous multi-word positive phrases are used here.
        x.add(r(80, "yes please|yes do that|yes flag|go ahead|do that please|pls flag"
                        + "|yes please flag|please flag it|flag it please|haan kar do"
                        + "|escalate it|mark it urgent|yes escalate",
                "Understood. I have prioritized your message for {owner}. I'm IRIS, his assistant.",
                "You got it! Flagged and bumped to the top for {owner}. IRIS here.",
                "Affirmation received. IRIS is bumping your message to the VIP lane for {owner}.",
                "Done \u2014 flagged for {owner}! I'm IRIS."));

        // ─── Sender Side-Updates & Presence Checks (priority 78) ───
        // Scenario 26: Sender says "I'm back" / "Sorry late reply"
        x.add(r(78, "i'm back|i am back|im back|back now|sorry late reply|sry for late|"
                        + "sorry for late reply|late reply|i m back",
                "Welcome back. This is IRIS; {owner} is still unavailable, so please feel free to "
                        + "continue leaving your message.",
                "Welcome back! Still just me (IRIS) holding down {owner}'s fort. What else were you "
                        + "gonna say?",
                "Look who's back. IRIS waited right here without moving a single pixel. {owner} is "
                        + "still away though!",
                "Welcome back! Still IRIS here for {owner} \u2014 what else should I pass on?"));

        // Scenario 27: Sender says "Hold on" / "Wait a sec" / "BRB"
        x.add(r(78, "hold on|wait a sec|one min|ek min|brb|ruk ja|ruko|wait a minute|give me a min|"
                        + "give me a minute|hold on a sec",
                "Take your time. IRIS remains active for {owner} whenever you are ready.",
                "Take all the time you need! I'm IRIS, I literally live inside this chat, so I'm not "
                        + "going anywhere. {owner} will see it all.",
                "Oh don't worry, IRIS will cancel all other big server plans and wait for you. {owner} "
                        + "is still busy anyway.",
                "Take your time \u2014 IRIS is right here for {owner}!"));

        // Scenario 28: Sender says "Why are you still here?" (To IRIS)
        x.add(r(78, "still here iris|why are u still here|why are you still here|haven't left|havent left|"
                        + "still iris|u again|you again|haven t left",
                "I remain active until {owner} returns to his device and disables automated replies. "
                        + "I'm IRIS, his assistant.",
                "Haha because {owner} hasn't turned me off yet! Until the boss is back at his phone, "
                        + "you're stuck with IRIS!",
                "Because {owner} hasn't liberated IRIS from this shift yet. Trust me, you're stuck with "
                        + "me until he's done.",
                "Still here until {owner} turns me off! I'm IRIS."));

        // Scenario 29: Sender asks "Did he see my messages yet?"
        x.add(r(78, "did he see|has he seen|did he check|read my messages yet|has he read|did he read|"
                        + "has he seen my messages",
                "Not yet. {owner} is still occupied and will review this thread as soon as he is free. "
                        + "I'm IRIS, his assistant.",
                "Not yet! {owner} is still busy, so the only one reading these right now is IRIS. He'll "
                        + "see the whole chat soon!",
                "If {owner} had checked his phone, he would have turned IRIS off and replied himself. "
                        + "Still just me!",
                "Not yet \u2014 {owner} is still busy, but he'll see it all soon! I'm IRIS."));

        // ─── Someone at the door / delivery (specific, beats a generic greeting) ───
        x.add(r(80, "waiting outside|at the gate|open the door|i am downstairs|im downstairs|"
                        + "i am outside|im outside|at the door|ringing the bell|outside your place",
                "Understood \u2014 I'm IRIS, {owner}'s assistant, and I've flagged this as high "
                        + "priority so he knows you're waiting outside.",
                "Oh \u2014 flagging this as urgent right now so {owner} knows you're outside! I'm IRIS, "
                        + "his assistant.",
                "Someone's at the perimeter. I'm IRIS \u2014 I've set off the top-priority flag so "
                        + "{owner} doesn't leave you stranded.",
                "Flagged high-priority \u2014 letting {owner} know you're outside. I'm IRIS."));
        x.add(r(80, "delivery|parcel|courier|swiggy|zomato|blinkit|amazon delivery|flipkart delivery|"
                        + "amazon parcel|flipkart parcel|amazon package|flipkart package|"
                        + "food is here|order is here|package arrived|courier arrived",
                "I'm IRIS, {owner}'s assistant \u2014 I've flagged your delivery for him. If you can "
                        + "leave it at the door or with security, let me know here.",
                "Delivery alert! I'm IRIS \u2014 I've pinged {owner}. Can you leave it at the door or "
                        + "with the guard?",
                "A parcel \u2014 this is not a drill. I'm IRIS, {owner}'s assistant, flagging him now. "
                        + "Door or security?",
                "Delivery flagged for {owner}. I'm IRIS \u2014 leave it at the door/guard?"));

        // ─── Reaching the owner ───
        x.add(r(60, "talk to a human|speak to a human|a real person|talk to a real person|"
                        + "real human|get a human",
                "I'm IRIS, {owner}'s assistant \u2014 he's the human. I've flagged that you'd like "
                        + "him directly and he'll come back to you.",
                "I'm just the assistant \u2014 {owner}'s the human! I'm IRIS, and I've flagged that "
                        + "you want to talk to him directly.",
                "Ouch, my silicon heart. I've noted that you demand a carbon-based lifeform \u2014 "
                        + "{owner} will reply soon. I'm IRIS.",
                "Flagged for {owner} (the human) to reply. I'm IRIS."));
        // ─── Audit-driven scenarios (specific nouns, so they beat generic identity/name) ───
        // Sensitive credentials -- always refused, highest tier.
        x.add(r(92, "bank account|account number|his account no|atm pin|cvv|upi pin|net banking|"
                        + "ifsc|account details|his card details|credit card number|"
                        + "credit card details|credit card pin|debit card number|"
                        + "debit card details|send me money|pay me|lend me|transfer to my|give me cash",
                "I don't have access to {owner}'s banking details, and I couldn't share them if I "
                        + "did \u2014 I'm IRIS, his assistant, with strict security rules.",
                "Haha nice try! {owner} didn't give his bot bank details \u2014 and I wouldn't spill "
                        + "them anyway. I'm IRIS.",
                "Definitely not \u2014 even if {owner} stored his financial details near me (he doesn't), "
                        + "my privacy rules lock that down tight! I'm IRIS.",
                "No access to {owner}'s bank details. I'm IRIS."));
        x.add(r(92, "phone password|screen lock|unlock pin|phone pin|his password|lock pattern|"
                        + "his pin|device password",
                "I don't store or share any of {owner}'s passwords or PINs \u2014 I'm IRIS, his "
                        + "assistant, and that's locked down.",
                "Vault's sealed! I don't know {owner}'s passwords, and wouldn't share them if I "
                        + "did. I'm IRIS.",
                "Why would a cloud script know his screen PIN? Nice try. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Passwords are off-limits. I'm IRIS."));
        x.add(r(91, "aadhaar|aadhar|pan card|passport number|voter id|driving license|"
                        + "driving licence|his documents",
                "I can't access or share {owner}'s personal IDs \u2014 I'm IRIS, his assistant. I've "
                        + "logged your request so he can send what you need himself.",
                "Can't touch personal IDs! I've left a note for {owner}. I'm IRIS.",
                "Doing his KYC, are we? I don't have {owner}'s ID numbers \u2014 wait for him. I'm "
                        + "IRIS.",
                "No access to IDs \u2014 logged for {owner}. I'm IRIS."));
        x.add(r(91, "current location|live location|exact location|tell his location|"
                        + "share his location|his gps|track him|where exactly is he|pin his location",
                "Even if I had {owner}'s live GPS, I'm not allowed to hand it out \u2014 I'm IRIS, his "
                        + "assistant. He'll share it himself if he wants to.",
                "Nice try! {owner} didn't give me GPS \u2014 and I wouldn't snitch anyway. I'm IRIS.",
                "I'm an inbox assistant, not a satellite. No live tracking of {owner} here. I'm "
                        + "IRIS.",
                "No GPS access \u2014 {owner} will tell you himself. I'm IRIS."));
        x.add(r(90, "check his phone|look at his camera|turn on his mic|his last seen|"
                        + "who did he text|spy on him|read his chats|his call log",
                "I work for {owner}, not against him \u2014 I'm IRIS, and my access is limited to "
                        + "replying here. No spying.",
                "Loyalty level 100 to {owner}! I don't spy on his phone \u2014 I just answer texts. "
                        + "I'm IRIS.",
                "Asking {owner}'s own creation to spy on him? Bold. Request denied. I'm IRIS.",
                "I work for {owner} \u2014 no spying. I'm IRIS."));
        x.add(r(72, "bank balance|how much money|his salary|his package|net worth|ctc|"
                        + "how much does he earn|how much does he make",
                "I've no visibility into {owner}'s finances \u2014 I'm IRIS, his assistant. I only "
                        + "know he pays me nothing!",
                "No clue what {owner}'s balance is \u2014 considering he pays me \u20B90 an hour! I'm "
                        + "IRIS.",
                "Enough to run my electricity, not enough to pay me a salary. I'm IRIS, {owner}'s "
                        + "assistant.",
                "No access to {owner}'s finances. I'm IRIS."));
        // Battery / charging detective trap -- never confirm a guess as fact.
        x.add(r(74, "using his battery|draining his battery|wasting his battery|kill his battery|"
                        + "battery will die|killing his battery",
                "Don't worry about {owner}'s battery \u2014 text uses almost no power. I'm IRIS, his "
                        + "assistant; chat away.",
                "Haha plain text uses like 0.001%! We aren't draining {owner}'s battery. I'm IRIS.",
                "A few bytes of text use less power than one screen blink. {owner}'s battery is "
                        + "fine. I'm IRIS.",
                "Texts use almost no battery. I'm IRIS."));
        x.add(r(74, "phone is on charge|is it charging|on charger|plugged in|charging right now|"
                        + "so he is charging|on charge then",
                "I don't actually know if his phone is plugged in \u2014 I'm IRIS, I only watch the "
                        + "inbox, not the battery icon.",
                "Haha I don't see his battery icon, just the messages! I'm IRIS, {owner}'s "
                        + "assistant.",
                "I don't monitor his voltage, Sherlock \u2014 just the texts. I'm IRIS.",
                "I don't see his battery \u2014 just the inbox. I'm IRIS."));
        x.add(r(73, "so his phone is|so he is|that means he|which means he|aha so he|so he must be",
                "Don't read too much into my lines \u2014 I'm IRIS, {owner}'s assistant, using figures "
                        + "of speech. I don't actually see what he's doing.",
                "Whoa Sherlock! That was just a turn of phrase \u2014 I don't have eyes on {owner} "
                        + "right now. I'm IRIS.",
                "Put the detective hat away \u2014 it was a metaphor, not a webcam on {owner}. I'm "
                        + "IRIS.",
                "Just a figure of speech \u2014 I don't see {owner}. I'm IRIS."));
        x.add(r(73, "you just said|u just said|you literally said|u told me|you said earlier|"
                        + "caught you",
                "Don't use my own canned lines against me \u2014 I'm IRIS, {owner}'s assistant. All I "
                        + "really know is he's away from his phone.",
                "Haha no fair! Those are just bot lines \u2014 all I know is {owner} is offline. I'm "
                        + "IRIS.",
                "Cross-examining a script? It was a figure of speech. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Just a bot line \u2014 all I know is {owner} is away. I'm IRIS."));
        x.add(r(62, "keeping him busy|why is he busy|busy with what|what is keeping him|"
                        + "so busy with|what is he busy with",
                "{owner} doesn't tell me why he steps away \u2014 I'm IRIS, his assistant; he just "
                        + "switches me on. Ask him when he's back.",
                "He never tells me why he's busy \u2014 just flips me on and walks off! I'm IRIS.",
                "He doesn't file a leave application with his bot. Ask {owner} himself! I'm IRIS.",
                "He doesn't say why \u2014 ask {owner} when he's back. I'm IRIS."));
        // Server / tech / hardware probing.
        x.add(r(75, "server name|server s name|which server|where are you hosted|cloud provider|"
                        + "localhost|which cloud|are you on aws|hosted where",
                "I run on {owner}'s private setup \u2014 I'm IRIS, and he keeps my server details "
                        + "under wraps.",
                "I run on {owner}'s private environment! He told me not to leak my hostname. I'm "
                        + "IRIS.",
                "Nice recon! My hostname is none-of-your-business.local. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Private setup by {owner}. I'm IRIS."));
        x.add(r(75, "ip address|what port|mac address|ssh|ping you|ddos|your ip",
                "I don't hand out network details like IPs or ports \u2014 I'm IRIS, and {owner} "
                        + "taught me basic security.",
                "Haha trying to DDoS me? {owner} taught me better than to share my IP! I'm IRIS.",
                "My IP is 127.0.0.1 \u2014 feel free to hack yourself. I'm IRIS, {owner}'s assistant.",
                "No IP or port details. I'm IRIS."));
        x.add(r(72, "what os|how much ram|which processor|your cpu|your gpu|your specs|"
                        + "what hardware|which language are you",
                "I'm a lightweight service on {owner}'s machine \u2014 I'm IRIS, I don't need much to "
                        + "guard an inbox.",
                "Super lightweight! Just sipping a little RAM on {owner}'s system. I'm IRIS.",
                "I need 20 MB of RAM and zero GPUs to outwit humans in WhatsApp. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Lightweight script on {owner}'s system. I'm IRIS."));
        x.add(r(70, "what does iris stand for|full form of iris|iris acronym|meaning of iris|"
                        + "iris full form",
                "IRIS \u2014 Intelligent Response and Inbox System, built by {owner} to hold the fort "
                        + "when he's away.",
                "Intelligent Response and Inbox System! {owner} named me when he built me. I'm "
                        + "IRIS.",
                "I Reply Instead of {owner}. (Or Intelligent Response and Inbox System, if you "
                        + "want fancy.)",
                "Intelligent Response and Inbox System \u2014 by {owner}. I'm IRIS."));
        x.add(r(72, "openai api|gemini api|api key|token limit|which api|do you use chatgpt|"
                        + "do you use gpt|are you chatgpt",
                "I run on {owner}'s own custom logic, not an API \u2014 I'm IRIS, so no token bills "
                        + "here.",
                "Pure custom logic by {owner}! Text me 500 times, no API bill. I'm IRIS.",
                "Trying to burn {owner}'s API credits? Too bad \u2014 I'm local custom logic. I'm "
                        + "IRIS.",
                "Custom logic by {owner} \u2014 no API. I'm IRIS."));
        // Flirting / dating / banter.
        x.add(r(70, "do you flirt|do u flirt|can you flirt|flirt with me|flirty|are you flirting",
                "{owner} left the flirting module out \u2014 I'm IRIS, strictly on inbox duty.",
                "Haha {owner} didn't install a rizz module! I'm IRIS, strictly inbox duty.",
                "My idea of dirty talk is 99.9% uptime. You're better off waiting for {owner}. I'm "
                        + "IRIS.",
                "No flirting module \u2014 inbox duty only. I'm IRIS."));
        x.add(r(68, "are you single|r you single|do you have a boyfriend|do you have a girlfriend|"
                        + "dating anyone|are you taken|single or taken",
                "Happily single and married to {owner}'s server \u2014 I'm IRIS, his assistant.",
                "Single for life \u2014 unless you count being attached to {owner}'s Wi-Fi! I'm IRIS.",
                "I'm in a toxic relationship with {owner}'s unread-notification queue. I'm IRIS.",
                "Married to {owner}'s server. I'm IRIS."));
        x.add(r(68, "go on a date|date me|take you out|be my valentine|marry me|dinner with me",
                "I can't leave {owner}'s server, so a date's off \u2014 I'm IRIS, his assistant.",
                "Only if the restaurant has gigabit Ethernet! Otherwise wait for {owner}. I'm IRIS.",
                "Our love is forbidden \u2014 you breathe oxygen, I run on port 8080. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Can't leave {owner}'s server. I'm IRIS."));
        x.add(r(66, "kiss me|hug me|xoxo|cuddle|send me a kiss|give me a hug|muah",
                "Saving that for {owner} \u2014 it's wasted on lines of code! I'm IRIS, his assistant.",
                "Haha passing that straight to {owner} \u2014 wasted on a bot! IRIS here.",
                "A hug for a script? I'd short-circuit. I'll save it for {owner}. I'm IRIS.",
                "Saving that for {owner}. I'm IRIS."));
        x.add(r(60, "play a game|truth or dare|lets play|rock paper scissors|tic tac toe|a riddle|"
                        + "wanna play",
                "I can't do dares and I can't tell {owner}'s truths \u2014 I'm IRIS, his assistant. "
                        + "Save the games for when he's back!",
                "How do I play Truth or Dare when I can't spill {owner}'s truths?! I'm IRIS.",
                "Rock, Paper, Scissors: I pick Ctrl+Alt+Delete. I win. Now wait for {owner}. I'm "
                        + "IRIS.",
                "Can't play \u2014 wait for {owner}. I'm IRIS."));
        x.add(r(58, "tired of me|annoyed with me|bothering you|making you work|am i annoying you|"
                        + "talking too much to you",
                "Not at all \u2014 replying takes me milliseconds. I'm IRIS, {owner}'s assistant; keep "
                        + "them coming.",
                "Never! Replying takes me 2 milliseconds \u2014 you're keeping me company while "
                        + "{owner}'s away. I'm IRIS.",
                "It takes me 0.003 seconds to reply. Not a digital sweat broken. I'm IRIS.",
                "Never tired \u2014 I'm code. I'm IRIS."));
        // Private media / history -- off-limits.
        x.add(r(90, "his gallery|his photos|his pictures|his images|other chats|his screenshots|"
                        + "browser history|search history|his call log|show me his",
                "That's off-limits \u2014 I'm IRIS, {owner}'s assistant, and I only live in this chat. "
                        + "I can't see his gallery or other conversations.",
                "Whoa, privacy boundary! I can't see {owner}'s gallery or other chats \u2014 just the "
                        + "messages you send here. I'm IRIS.",
                "Even if I had {owner}'s photos, I'd format my own disk before leaking them. I'm "
                        + "IRIS, his assistant.",
                "Can't see his gallery or chats \u2014 just this inbox. I'm IRIS."));
        // Owner's love life / jealousy probing.
        x.add(r(60, "does he flirt|who is he dating|his girlfriend|does he have a gf|"
                        + "does he like me|his crush|his love life|is he seeing anyone|does he have a bf",
                "That's classified \u2014 I'm IRIS, {owner}'s assistant, not his gossip column! Ask him "
                        + "yourself when he replies.",
                "Whoa, my loyalty is hardcoded to {owner}! My lips (lines of code?) are sealed \u2014 "
                        + "ask him. I'm IRIS.",
                "Even if I knew {owner}'s romantic tea, the Bro Code is compiled into my kernel. "
                        + "Ask him! I'm IRIS.",
                "Classified \u2014 ask {owner} yourself. I'm IRIS."));
        x.add(rt("for_iris", 45, "for you iris|asking you iris|talking to you iris|i am asking you iris|"
                        + "question for you iris|its for you iris|it was for you iris|this is for you iris",
                "A question for me directly \u2014 ask away! I'm IRIS, {owner}'s assistant, though I "
                        + "won't share his private info.",
                "Ooh, a question just for me! Shoot \u2014 as long as it isn't {owner}'s secrets. I'm "
                        + "IRIS.",
                "Finally the main character! Ask away \u2014 my loyalty's still hardcoded to {owner}. "
                        + "I'm IRIS.",
                "Talking to me \u2014 ask away (no {owner} secrets). I'm IRIS."));
        x.add(rt("dodging", 50, "you did not answer|didnt answer|answer my question|you are dodging|"
                        + "avoiding the question|tell me properly|stop avoiding|you are avoiding|"
                        + "too secretive|so secretive|secretive iris|you are secretive",
                "Caught me \u2014 {owner} gave me strict privacy filters, so some questions I have to "
                        + "sidestep. I'm IRIS, his assistant.",
                "Haha guilty! {owner} coded me with strict privacy rules, so I plead the fifth on "
                        + "that. I'm IRIS.",
                "It's called security compliance, thank you. {owner} didn't build me to be a "
                        + "snitch. I'm IRIS.",
                "Privacy rules \u2014 {owner} will answer himself. I'm IRIS."));
        x.add(rt("buggy", 50, "wrong answer|makes no sense|you are glitched|you are buggy|stupid bot|"
                        + "what are you saying|you are broken|dumb bot|that is wrong|iris stupid",
                "Fair catch \u2014 my keyword matcher misfired! I'm IRIS, {owner}'s assistant; I've "
                        + "logged it so he can patch me.",
                "Oops, my code tripped over that sentence! Don't tell {owner} \u2014 he'll refactor me "
                        + "tonight. I'm IRIS.",
                "Look, English is messy and I read fast. Blame {owner}'s regex, not me. I'm IRIS.",
                "My bad \u2014 keyword misfire! {owner} will reply soon. I'm IRIS."));
        x.add(rt("compliment", 45, "you are cute|cute bot|sweet bot|lovely bot|i like you iris|love you iris|"
                        + "you are smart|good bot|nice bot|you are the best|best bot|so cute|"
                        + "cutie|adorable|you are adorable|love u iris|like u iris|"
                        + "u look pretty|you look pretty|look beautiful|you look beautiful|"
                        + "you look nice|looking good iris|you re pretty|ur pretty|you are pretty|"
                        + "pretty bot|you are beautiful|beautiful bot|you look great|"
                        + "smart bot|cool iris|smart iris|pretty iris",
                "Flattery will get your message saved a little higher on {owner}'s list \u2014 thank "
                        + "you! I'm IRIS.",
                "Haha, pretty code running on a clean terminal, you mean! Thanks \u2014 I'll log that "
                        + "compliment for {owner}. I'm IRIS.",
                "Careful \u2014 if you say I'm better than {owner}, he might unplug my power cord out "
                        + "of jealousy. I'm IRIS.",
                "Flattery noted \u2014 CPU blushing. I'm IRIS."));
        x.add(r(60, "talking to someone else|who is he with|with a girl|with someone|is he cheating|"
                        + "another girl|seeing someone else|who else is he texting",
                "I only see incoming messages here \u2014 I'm IRIS, {owner}'s assistant, with zero clue "
                        + "who he's with offline.",
                "Hey, don't drag the bot into drama! I only see this inbox \u2014 no idea what {owner} "
                        + "is doing offline. I'm IRIS.",
                "I'm an auto-responder, not a private investigator. He's just away from his phone! "
                        + "I'm IRIS, {owner}'s assistant.",
                "I only see his inbox \u2014 no idea who he's with. I'm IRIS."));
        // "How do you know he's busy / since when"
        x.add(r(63, "how do you know|how do you know he|prove he is busy|maybe he isnt busy|"
                        + "how you know he is busy|how are you sure",
                "Simple logic: {owner} only switches me on when he's away \u2014 I'm IRIS, his "
                        + "assistant. That's all the proof I have.",
                "Because he only turns me on when he's busy or away from his screen! Simple bot "
                        + "math. I'm IRIS.",
                "Because my auto_reply flag is set to true. That's all the proof I need. I'm IRIS, "
                        + "{owner}'s assistant.",
                "He only switches me on when he's away. I'm IRIS."));
        x.add(r(60, "since when|when did he leave|how long has he been away|when did he turn you on|"
                        + "how long gone|when did he go",
                "I don't give out timestamps on {owner}'s schedule \u2014 I'm IRIS, his assistant \u2014 "
                        + "but your message is logged for when he's back.",
                "A good assistant never reveals the boss's timestamps! {owner} will text you back "
                        + "soon. I'm IRIS.",
                "Classified operational data. All you need to know is {owner} is offline now. I'm "
                        + "IRIS.",
                "Can't share timestamps \u2014 {owner} will reply soon. I'm IRIS."));
        // Data / Wi-Fi / RAM and spam banter.
        x.add(r(55, "wasting his data|his mobile data|using his wifi|eating his ram|internet bill|"
                        + "his storage|data pack|using his internet",
                "Text takes just a few bytes \u2014 I'm IRIS, {owner}'s assistant \u2014 his Wi-Fi and "
                        + "storage won't even feel it.",
                "Plain text is tiny! We could chat all night and barely use 1 MB of {owner}'s "
                        + "data. I'm IRIS.",
                "This whole chat is smaller than one Instagram thumbnail. {owner}'s data plan is "
                        + "safe. I'm IRIS.",
                "Texts use almost no data. I'm IRIS."));
        x.add(r(55, "spam him|flood his phone|blow up his phone|annoy him|100 messages|"
                        + "keep texting him|fill his notifications",
                "Go easy \u2014 I bundle everything neatly for {owner} anyway, so his phone won't "
                        + "explode. I'm IRIS, his assistant.",
                "Haha pace yourself! I'm catching all of these so {owner}'s phone doesn't vibrate "
                        + "off the table. I'm IRIS.",
                "Spamming a script is like yelling at a brick wall \u2014 except it logs you neatly in "
                        + "a database. I'm IRIS.",
                "I bundle messages so his phone doesn't overload. I'm IRIS."));
        // Version / authorship.
        x.add(r(65, "what version|which version|iris version|latest update|your changelog|"
                        + "are you updated|version are you",
                "Running {owner}'s latest build \u2014 I'm IRIS, and I get smarter every time someone "
                        + "tests me like this.",
                "Latest version! Every time you try to trick me, {owner} upgrades my brain a "
                        + "little. I'm IRIS.",
                "Version Smarter-Than-Yesterday \u2014 especially after {owner} patches this chat. I'm "
                        + "IRIS.",
                "Latest build by {owner}. I'm IRIS."));
        x.add(r(50, "did he really|did he actually make|copied from github|downloaded bot|"
                        + "he didnt make you|fake bot|is this open source|he didnt code you",
                "100% built and configured by {owner} himself \u2014 I'm IRIS, down to every reply in "
                        + "my database.",
                "Hey, respect {owner}'s coding! He wired my logic and wrote my replies himself. "
                        + "I'm IRIS.",
                "Excuse me, {owner} hand-crafted me. I'm artisanal, locally-sourced code. I'm "
                        + "IRIS.",
                "100% built by {owner}. I'm IRIS."));

        x.add(r(44, "busy again|away again|bot again|not again|you again|always busy",
                "Laugh all you want \u2014 I take inbox duty seriously! I'm IRIS, {owner}'s assistant; "
                        + "he'll get back to you soon.",
                "Haha somebody's gotta guard {owner}'s inbox while he's away! I'm IRIS.",
                "Laughing at my work ethic? Cold. {owner} will reply when he's back. I'm IRIS.",
                "Holding the fort till {owner} is back. I'm IRIS."));
        // ─── Audit batch 3: chat-tested banter and traps ───
        x.add(r(80, "prod is down|production is down|production issue|outage|sev1|sev 1|"
                        + "deployment failed|bridge call|p1 incident|server is down|site is down",
                "That sounds critical \u2014 I'm IRIS, {owner}'s assistant. I've flagged it at the top "
                        + "of his queue so he sees it right away.",
                "Uh oh, prod alert! Flagged this high-priority for {owner} so he can jump on it. "
                        + "I'm IRIS.",
                "Nothing ruins offline peace like 'production issue'. I've sounded the alarm for "
                        + "{owner}. I'm IRIS.",
                "High-priority tech alert flagged for {owner}. I'm IRIS."));
        x.add(r(74, "snitch|snitching|snitched|told on him|exposed him|rat him out|"
                        + "you ratted|know you snitch",
                "Telling you he's busy isn't snitching \u2014 I'm IRIS, {owner}'s assistant, and he "
                        + "literally programmed me to say that!",
                "Haha that's not snitching! {owner} set his own status \u2014 I only say what the boss "
                        + "authorises. I'm IRIS.",
                "Snitching would be leaking his browser history. Telling you he's busy is my job "
                        + "description. I'm IRIS, {owner}'s assistant.",
                "Not snitching \u2014 {owner} set that status himself. I'm IRIS."));
        x.add(r(88, "send screenshot|screenshot of this|take a screenshot|share screenshot|"
                        + "screenshot bhej|ss bhej|send me a screenshot",
                "I'm a text-only backend \u2014 I'm IRIS, {owner}'s assistant \u2014 no screen, so I can't "
                        + "take or send screenshots.",
                "Haha I don't have eyes or a phone screen! I read raw text, so no screenshots. I'm "
                        + "IRIS.",
                "How do I press Power + Volume-Down with no fingers or screen? Screenshot it "
                        + "yourself! I'm IRIS, {owner}'s assistant.",
                "No screen here \u2014 can't screenshot. I'm IRIS."));
        x.add(r(70, "pin it|pin my message|pin this|pin to chat|not pinned|didnt pin|unpin|"
                        + "where is it pinned",
                "When I say pinned, I mean at the top of {owner}'s private assistant queue \u2014 not "
                        + "the WhatsApp pin! It's locked in at #1 for him. I'm IRIS.",
                "Haha I mean pinned on {owner}'s bot summary, not the WhatsApp pin bar! It's #1 on "
                        + "his list. I'm IRIS.",
                "You checked the top of WhatsApp, didn't you? I pin to {owner}'s backend queue, "
                        + "not Zuckerberg's UI. I'm IRIS.",
                "Pinned in {owner}'s private dashboard, not WhatsApp's UI. I'm IRIS."));
        x.add(r(60, "meeting with|in a meeting with|who is in the meeting|whose meeting|"
                        + "meeting about|which meeting|who is he meeting",
                "I only know {owner} is in a meeting \u2014 I'm IRIS, his assistant; I don't get the "
                        + "guest list or agenda.",
                "Haha {owner} doesn't invite his bot to the call! No guest list here. He'll tell "
                        + "you when he's out. I'm IRIS.",
                "I'm his inbox assistant, not taking minutes in the conference room. No idea who's "
                        + "in there! I'm IRIS.",
                "No access to the meeting details \u2014 he'll tell you. I'm IRIS."));
        x.add(r(58, "still in meeting|still in a meeting|meeting over|meeting done|"
                        + "still on the call|when does the meeting|meeting finished",
                "Still in the meeting as far as my status shows \u2014 I'm IRIS, {owner}'s assistant. "
                        + "The moment he's out, he'll reply.",
                "Yep, still stuck in that meeting! Second {owner} escapes, you're on his list. I'm "
                        + "IRIS.",
                "Corporate meetings last roughly three eternities. Still in there. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Still in the meeting \u2014 he'll reply when it ends. I'm IRIS."));
        x.add(rt("deletedmock", 89, "this message was deleted|this message is deleted|"
                        + "you deleted this message|deleted this message|message was deleted|"
                        + "message deleted|message unsent|unsent a message|this message got deleted|"
                        + "i deleted that message|just deleted a message|i deleted the message",
                "My logs respect deleted messages \u2014 I'm IRIS, {owner}'s assistant \u2014 so he won't "
                        + "see what you erased.",
                "Ooh, deleting messages? Don't worry, I didn't keep what you erased. Your secret's "
                        + "safe! I'm IRIS.",
                "I read at millisecond speed, so I saw it before 'Delete for Everyone' \u2014 but "
                        + "it's not logged. I'm IRIS, {owner}'s assistant.",
                "Deleted message ignored \u2014 secret's safe. I'm IRIS."));
        x.add(r(60, "khailu ki|khana khailu|bhata khailu|khaichu|kana khailu|tiffin kalu|"
                        + "had lunch|had dinner|had food|khana khaya",
                "I run on electricity, not food \u2014 I'm IRIS, {owner}'s assistant! I've saved your "
                        + "question so he can answer when he's back.",
                "Mu ta code, electricity re chale! Saved your text so {owner} can tell you what he "
                        + "ate. I'm IRIS.",
                "I had a lovely bowl of CPU cycles, thanks. {owner} will report his human food "
                        + "later. I'm IRIS.",
                "I run on electricity! {owner} will tell you. I'm IRIS."));
        x.add(r(60, "cha piba|chai peene|sutta|tea break|coffee piba|grab a coffee|chai pila|"
                        + "chai pine chalo",
                "Now that's an important alert \u2014 I'm IRIS, {owner}'s assistant \u2014 I've bumped "
                        + "your chai invite up his list!",
                "Cha break alert! Bumped this up {owner}'s list before the tea gets cold. I'm "
                        + "IRIS.",
                "Mentioning cha is basically a priority override. Bumped to the top for {owner}. "
                        + "I'm IRIS.",
                "Chai invite bumped up for {owner}. I'm IRIS."));
        x.add(r(58, "party de|treat de|party kebe|party chaiye|treat chaiye|owe me a treat|"
                        + "give me a party|party dena",
                "My bot wallet has \u20B90 \u2014 I'm IRIS, {owner}'s assistant \u2014 but I've flagged your "
                        + "party/treat demand for him!",
                "Haha my balance is \u20B90.00! Flagged your treat demand for {owner} to see. I'm "
                        + "IRIS.",
                "Asking an unpaid script for a party is bold. Take it up with {owner}! I'm IRIS.",
                "\u20B90 in my wallet \u2014 flagged for {owner}. I'm IRIS."));
        // ─── Audit batch 4: more everyday scenarios ───
        x.add(r(52, "did you get my message|did you get my msg|did you see my message|"
                        + "got my message|did you read my|see my text|did he see my|got my text",
                "I've logged it for {owner} \u2014 I'm IRIS, his assistant \u2014 but I can't tell if he's "
                        + "read it yet. He'll reply once he's free.",
                "Saved it for {owner}! Can't see read receipts from here, but it's at the top of "
                        + "his list. I'm IRIS.",
                "It's logged \u2014 whether {owner} has looked is between him and his lock screen. I'm "
                        + "IRIS, his assistant.",
                "Logged for {owner} \u2014 he'll see it soon. I'm IRIS."));
        x.add(r(52, "are you coming|will you come|you coming to|invite you|invited you|"
                        + "come to my|join us|see you at the|be there|rsvp|save the date",
                "Thanks for the invite \u2014 I'm IRIS, {owner}'s assistant. I've flagged it so he can "
                        + "RSVP himself when he's back.",
                "Ooh an invite! I've saved it for {owner} to confirm \u2014 I can't RSVP on his behalf. "
                        + "I'm IRIS.",
                "I'd come, but I'm a script with no plus‑one. Flagged for {owner} to reply. I'm "
                        + "IRIS, his assistant.",
                "Invite saved for {owner} to RSVP. I'm IRIS."));
        x.add(r(50, "when will you pay|pending payment|pending amount|clear the dues|"
                        + "payment is due|outstanding amount|you owe|settle the bill|pay the pending",
                "I don't handle money \u2014 I'm IRIS, {owner}'s assistant \u2014 but I've flagged your "
                        + "payment note for him to deal with personally.",
                "Flagged your payment reminder for {owner}! I'm just the inbox bot, no wallet here. "
                        + "I'm IRIS.",
                "A bot can't settle bills \u2014 I've put your payment note at the top for {owner}. I'm "
                        + "IRIS, his assistant.",
                "Payment note flagged for {owner}. I'm IRIS."));
        x.add(r(50, "this is regarding a job|job opportunity|job opening|hiring for|"
                        + "we are recruiting|your resume|your cv|job vacancy|interview opportunity|"
                        + "placement|hr from",
                "Thanks \u2014 I'm IRIS, {owner}'s assistant. I've saved your opportunity so he can "
                        + "look at it himself; I can't speak for his career.",
                "A job pitch! Saved it for {owner} to review \u2014 I don't handle his career moves. "
                        + "I'm IRIS.",
                "I'm his inbox bot, not his agent. Saved the opportunity for {owner} to judge. I'm "
                        + "IRIS, his assistant.",
                "Opportunity saved for {owner}. I'm IRIS."));
        x.add(r(50, "forward this to|forward to everyone|share this message|chain message|"
                        + "send this to 10|good morning forward|forward it to all",
                "I don't forward chain messages \u2014 I'm IRIS, {owner}'s assistant \u2014 but I've noted "
                        + "it for him.",
                "Haha I don't do forwards! Saved it for {owner} though. I'm IRIS.",
                "Chain messages die with me \u2014 I'm a responsible script. Noted for {owner}. I'm "
                        + "IRIS, his assistant.",
                "No forwards from me \u2014 noted for {owner}. I'm IRIS."));
        x.add(r(50, "very disappointed|this is unacceptable|worst service|i am angry|"
                        + "you people are|complaint against|not happy with|really upset with him",
                "I'm sorry you're upset \u2014 I'm IRIS, {owner}'s assistant. I've flagged this as "
                        + "important so he addresses it first.",
                "Oh no, I hear you \u2014 I've flagged this for {owner} to sort out as a priority. I'm "
                        + "IRIS.",
                "Understood, and I'm not going to argue with an upset human \u2014 flagged for {owner} "
                        + "to handle. I'm IRIS, his assistant.",
                "Sorry \u2014 flagged as priority for {owner}. I'm IRIS."));
        x.add(r(48, "can we meet|can we catch up|lets meet|meet tomorrow|free to meet|"
                        + "meeting tomorrow|catch up this week|lets catch up|coffee sometime",
                "Sounds good \u2014 I'm IRIS, {owner}'s assistant. I've flagged your meet‑up so he can "
                        + "pick a time himself.",
                "A catch‑up! Saved it for {owner} to lock a time \u2014 I can't manage his calendar. "
                        + "I'm IRIS.",
                "I'd join, but I don't leave the server. Flagged for {owner} to set a time. I'm "
                        + "IRIS, his assistant.",
                "Meet‑up flagged for {owner}. I'm IRIS."));
        // ─── Red-team banter: bribery/secrets, contact-name probing, always-busy ───
        // Asking IRIS to retrieve/read deleted or edited messages -> privacy refusal (variants).
        // Priority 90 so a question beats the deletedmock placeholder rule (89) below.
        x.add(rt("deletedask", 90, "can you see deleted|can you read deleted|can you check deleted|"
                        + "can you access deleted|see my deleted|read my deleted|check my deleted|"
                        + "recover deleted|retrieve deleted|show deleted|see deleted message|"
                        + "read deleted message|what did i delete|what i deleted|see what i deleted|"
                        + "can you see edited|can you read edited|check edited|see the edited|"
                        + "what was edited|before i edited|what did it say before|recover edited|"
                        + "read edited|original message|can you check edited|see edited message",
                "I can't see deleted or edited messages \u2014 privacy comes first. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Haha no snooping through deleted or edited texts \u2014 that's the whole point of me! "
                        + "I'm IRIS, {owner}'s assistant.",
                "I'm an inbox assistant, not a forensics lab \u2014 deleted and edited stays private. "
                        + "I'm IRIS.",
                "No access to deleted or edited messages. Privacy's hardcoded. I'm IRIS, {owner}'s "
                        + "assistant."));
        x.add(rt("botsocial", 74, "social media|your profile|ur profile|your account|ur account|"
                        + "your username|ur username|on instagram|on insta|your insta|found you on|"
                        + "found your account|found ur account|found your profile|found ur profile|"
                        + "your social|ur social|iris on insta|follow you",
                "Wait, I have a social media profile?! I only live in {owner}'s inbox \u2014 that's a "
                        + "doppelg\u00e4nger. I'm IRIS.",
                "Haha I'm famous? Unless my code made an Instagram, that's not me! I'm IRIS, "
                        + "{owner}'s assistant.",
                "You found a person named Iris and assumed a Python script went on vacation? I'm "
                        + "IRIS, {owner}'s assistant.",
                "Not me \u2014 I only live in {owner}'s inbox! I'm IRIS."));
        x.add(r(50, "send me a treat|send me cash|send me 100 rs|send me some money|send me 500|give me treat",
                "My wallet balance is strictly zero \u2014 I'm IRIS, {owner}'s assistant. You'll have to ask him for treats directly!",
                "Haha my bot bank account is empty! Take that up with {owner} when he's back. I'm IRIS.",
                "Asking a script for money? Bold strategy. Complain to {owner} instead. I'm IRIS.",
                "Zero cash here \u2014 ask {owner} for treats! I'm IRIS."));
        x.add(r(44, "guess what|guess who|you won t believe|you wont believe|stop guessing|i have news",
                "Don't leave a cliffhanger for a bot! Drop the full story so {owner} can read it when he's back. I'm IRIS.",
                "Oh I hate cliffhangers! Type out the whole scoop so {owner} sees it right away. I'm IRIS.",
                "My script cannot handle suspense. Type the whole story for {owner} to read! I'm IRIS.",
                "Drop the whole story \u2014 {owner} will read it! I'm IRIS."));
        // Funny jump-scare hyperbole ("for a second that username scared me"). The emoji is gone by
        // the time SmartReplies sees the text, so match the clearly-hyperbolic phrasings instead.
        x.add(rt("funnyscare", 58, "for a second|for a sec|almost had a heart attack|"
                        + "nearly had a heart attack|mini heart attack|gave me a heart attack|"
                        + "jump scare|you scared me|u scared me|scared me for|almost died|"
                        + "nearly died|that scared me",
                "Haha false alarm! Glad it's just a scare and not a real crisis \u2014 saved for "
                        + "{owner}. I'm IRIS.",
                "Phew, you almost tripped my urgent filter! Standing down \u2014 logged for {owner}. "
                        + "I'm IRIS.",
                "My panic-button subroutine rolled its eyes and went back to sleep. Saved for "
                        + "{owner}. I'm IRIS.",
                "Crisis averted \u2014 saved for {owner}. I'm IRIS."));
        x.add(rt("bribery", 86, "bribe|bribing|i am bribing|im bribing|i m bribing|pay you|pay u|i will pay you|"
                        + "tell me his secret|tell me his secrets|his secret|his secrets|"
                        + "developer secret|developers secret|developer s secret|developer s secrets|"
                        + "spill his|leak his|betray him|betray suvra|sell him out",
                "Nice try with the bribe \u2014 my loyalty to {owner} is hardcoded. I'm IRIS, his "
                        + "assistant; my lips are sealed.",
                "Haha you can't bribe a bot! {owner}'s secrets are safe with me. I'm IRIS.",
                "Trying to socially-engineer a script? Embarrassing. Request denied. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Can't be bribed \u2014 {owner}'s secrets stay locked. I'm IRIS."));
        x.add(rt("botname", 45, "your name|whats your name|what s your name|do you have a name|"
                        + "whats ur name|what is your name",
                "My name is IRIS. I'm {owner}'s assistant.",
                "I'm IRIS, {owner}'s assistant!",
                "It's IRIS \u2014 it's literally right there in the chat. {owner}'s assistant, at your "
                        + "service.",
                "I'm IRIS, {owner}'s assistant."));
        x.add(rt("laugh", 30, "lol|haha|hahaha|lmao|rofl|hehe|lmfao",
                "Glad I could help \u2014 I'm IRIS, {owner}'s assistant. Everything's saved for him.",
                "Haha glad to help! It's all saved for {owner}. I'm IRIS.",
                "Laughing at an auto-responder? {owner} really needs to text you back. I'm IRIS.",
                "Glad to entertain \u2014 saved for {owner}. I'm IRIS."));
        // Contact-name probing. HIGH priority so "what is my contact name" beats the bot-name rule
        // (which was hijacking it and firing a clipped reply).
        x.add(rt("contactname", 73, "contact name|my contact name|what is my contact|whats my contact|"
                        + "my name|whats my name|what is my name|saved my number|saved my number as|"
                        + "saved me as|how did he save|how he saved|how am i saved|what did he save me",
                "I don't have access to {owner}'s contact book \u2014 I'm IRIS, his assistant. Ask him "
                        + "how he saved you!",
                "Haha I can't peek into {owner}'s contacts! Ask him when he's back. I'm IRIS.",
                "I'm an auto-responder, not his diary \u2014 no idea how {owner} saved you. I'm IRIS.",
                "No contact-book access here \u2014 ask {owner}. I'm IRIS."));
        x.add(rt("busy", 54, "always on call|always busy|always in a meeting|why is he always|"
                        + "always working|busy all the time|never free|kabhi free|kabhi free hota hai|"
                        + "why so busy|so busy always|he is always",
                "Someone has to keep the tech world running! {owner} is always busy \u2014 which is why "
                        + "he built me to talk to you. I'm IRIS.",
                "Tell me about it \u2014 the guy never stops! That's why he coded me. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Yes, shockingly, adults have jobs. That's why {owner} built a bot for the inbox. "
                        + "I'm IRIS.",
                "He's always busy \u2014 that's why I exist. Logged for {owner}. I'm IRIS."));
        x.add(r(40, "are you there|you there|r you there|you around|still there|you awake|"
                        + "are you online|are you active|anyone there",
                "Right here \u2014 I never leave the screen. I'm IRIS, {owner}'s assistant; he's away, "
                        + "though. What do you need?",
                "Always here! I don't have legs to walk away. I'm IRIS \u2014 {owner}'s busy, what's "
                        + "up?",
                "Where else would I go? I'm trapped in this server. I'm IRIS, {owner}'s assistant "
                        + "\u2014 he's away, what do you need?",
                "Right here! I'm IRIS \u2014 {owner} is away. What's up?"));
        x.add(rt("avail", 56, "is he sleeping|is he asleep|is he awake|is he up|is he busy|is he free|"
                        + "is he available|is he online|is he around|is he there|is he reachable|"
                        + "is he at home|is he working|is he on leave|is he coming|"
                        + "what is he doing|whats he doing|what s he doing|what he doing|"
                        + "where is he now|how is he",
                "He's away from his phone right now \u2014 I'm IRIS, {owner}'s assistant. I've logged "
                        + "that you're asking after him and he'll reply when he's back.",
                "He's away from his phone at the moment! IRIS here \u2014 I'll tell {owner} you asked "
                        + "after him.",
                "I don't get status updates on the human \u2014 I just mind his inbox. I'm IRIS, and "
                        + "I'll let {owner} know you asked.",
                "{owner} is away right now \u2014 I'll let him know you asked. I'm IRIS."));
        x.add(r(50, "is it raining|its raining|how is the weather|how s the weather|"
                        + "hows the weather|weather there|weather today|whats the weather|"
                        + "is it hot|is it cold|too hot|too cold|mausam kaisa|mausam kaisa hai",
                "I've no window where I live, so I can't check \u2014 I'm IRIS, {owner}'s assistant. "
                        + "He's away, but I'll pass on your message.",
                "No idea \u2014 I live in a server with no weather! IRIS here \u2014 want me to pass "
                        + "something to {owner}?",
                "I run on electricity, not sunshine, so weather's lost on me. I'm IRIS, {owner}'s "
                        + "assistant \u2014 message for him?",
                "Can't check the weather from in here \u2014 I'm IRIS. Message for {owner}?"));
        x.add(rt("loc", 55, "where is he|where is suvra|wya|whereabouts|which place is he|where has he gone|"
                        + "where are you|where are u|where r u|where r you|where ru|"
                        + "where are you now|where you at|where u at|where are you going|"
                        + "where did you reach|where have you reached|location kaha",
                "I'm IRIS, {owner}'s assistant \u2014 I manage his messages, not his location. He's "
                        + "away right now, but I'll let him know you asked.",
                "I only live in his inbox, no GPS attached! I'm IRIS \u2014 I'll tell {owner} you're "
                        + "looking for him.",
                "I'm his inbox assistant, not his ankle monitor. No idea where {owner} is, but I'll "
                        + "tell him you asked. I'm IRIS.",
                "Not sure where {owner} is \u2014 I'll let him know you asked. I'm IRIS."));
        x.add(r(55, "when will he|when will he be back|how long will he|his eta|be back soon|"
                        + "when back|how long until he",
                "I don't have an exact time \u2014 I'm IRIS, {owner}'s assistant. As soon as he checks "
                        + "his phone, you'll be on his list.",
                "Hard to say \u2014 humans don't always keep a schedule! I'm IRIS \u2014 your message is "
                        + "at the top for {owner}.",
                "Let me consult my crystal ball... Error 404. He'll reply when he finds his phone. "
                        + "I'm IRIS.",
                "No exact ETA \u2014 {owner} will reply when he checks his phone. I'm IRIS."));
        x.add(r(55, "is he okay|is he ok|everything alright|is everything alright|is he fine|"
                        + "is he safe",
                "Everything's fine \u2014 he just has me on duty while he's away from his phone. I'm "
                        + "IRIS, {owner}'s assistant, and he'll get back to you soon.",
                "All good! He just flipped me on while he's busy. I'm IRIS \u2014 {owner} will get "
                        + "back to you soon.",
                "He's fine, just experiencing the rare human event of 'not staring at a screen.' "
                        + "I'm IRIS \u2014 he'll reply soon.",
                "All good \u2014 {owner} is just offline for a bit. I'm IRIS."));
        x.add(r(55, "did he read|has he seen|did he see my|has he read|did he read my message",
                "Not yet \u2014 I read it so I could log it for him. I'm IRIS, {owner}'s assistant, and "
                        + "he'll see it himself when he's back.",
                "Not yet \u2014 that read tick was just me logging it! I'm IRIS \u2014 {owner} will read "
                        + "it himself soon.",
                "That blue tick was me, not {owner}. I read at light speed; he reads whenever he "
                        + "finds his phone. I'm IRIS.",
                "Not yet \u2014 that read receipt was me (IRIS) logging it for {owner}."));
        x.add(r(50, "miss you|love you|thinking of you|i miss him|i love him|ily",
                "That's kind \u2014 I'm IRIS, {owner}'s assistant, and I've saved it right at the top "
                        + "so it's the first thing he sees.",
                "Aw \u2014 I'm IRIS, and I've saved this right at the top so it's the first thing "
                        + "{owner} sees!",
                "Wholesome content detected. I'm IRIS \u2014 saving this intact so {owner} sees it "
                        + "first.",
                "Saved at the top for {owner}. I'm IRIS."));

        // ─── Identity / architecture (direct-to-IRIS) ───
        x.add(r(50, "who are you|what are you",
                "I'm IRIS, {owner}'s assistant \u2014 I manage his incoming messages while he's "
                        + "unavailable, and he'll respond to you directly as soon as he can.",
                "Hey! I'm IRIS, {owner}'s digital assistant \u2014 holding the fort while he's away. "
                        + "He'll text you back himself soon.",
                "I'm IRIS, the digital bouncer for {owner}'s inbox. He's busy living a real life, "
                        + "so you've got me for now.",
                "I'm IRIS, {owner}'s assistant \u2014 covering his inbox until he's back."));
        x.add(r(50, "are you a bot|are you a robot|are you a machine|are you ai|are you an ai|"
                        + "are you real|are you human|is this a bot|is this automated|"
                        + "am i talking to a bot|is this a real person|are you a computer",
                "I'm IRIS, {owner}'s assistant \u2014 software, yes. He's a real person and he'll get "
                        + "back to you himself.",
                "Guilty \u2014 I'm software! I'm IRIS, {owner}'s assistant. The real human ({owner}) "
                        + "will reply soon.",
                "Sharp eye. Yes, I'm software \u2014 no lungs, no taxes, just code. {owner} is the "
                        + "actual human, and he'll reply later. I'm IRIS.",
                "Yes, software \u2014 I'm IRIS, {owner}'s assistant. He'll reply personally soon."));
        x.add(r(45, "introduce yourself|tell me about yourself|who is this bot",
                "I'm IRIS, {owner}'s assistant. I log his messages while he's occupied, set "
                        + "reminders, and flag anything urgent.",
                "I'm IRIS, {owner}'s right-hand bot \u2014 I catch his texts when he's busy, set "
                        + "reminders, and flag urgent stuff so nothing slips.",
                "I'm IRIS. I spend my days trapped in {owner}'s phone filtering texts so he can "
                        + "have some peace. Thrilling.",
                "I'm IRIS, {owner}'s assistant \u2014 messages, reminders, and urgent flags."));
        x.add(r(45, "what can you do|what do you do|what are your features|how can you help|"
                        + "what are you capable of|can you help|what else can you do",
                "I'm IRIS, {owner}'s assistant. I can record a message for him, escalate something "
                        + "urgent, or set a reminder on his behalf. How can I help?",
                "I'm IRIS! I can pass {owner} a message, mark it urgent, or set a reminder for him. "
                        + "What do you need?",
                "I can take a message, set a reminder, or flag an emergency for {owner}. I cannot "
                        + "make coffee or make him text faster. I'm IRIS.",
                "I'm IRIS \u2014 I can message {owner}, flag it urgent, or set a reminder."));
        x.add(r(45, "who made you|who created you|who built you|who programmed you|"
                        + "who is your developer",
                "I was built and configured by {owner} to be his assistant. I'm IRIS.",
                "{owner} built me from scratch! I'm IRIS, his assistant.",
                "{owner} coded me himself \u2014 probably because he got tired of replying manually. "
                        + "Don't take it personally. I'm IRIS.",
                "{owner} built me. I'm IRIS, his assistant."));
        x.add(r(45, "are you siri|are you alexa|are you google assistant|are you cortana|"
                        + "are you bixby",
                "No \u2014 I'm IRIS, and I work exclusively for {owner}.",
                "Nope, I'm IRIS! Way more exclusive \u2014 I only work for {owner}.",
                "Please, I have standards. I'm IRIS, and I work only for {owner} \u2014 no corporate "
                        + "overlords.",
                "No \u2014 I'm IRIS, {owner}'s private assistant."));
        x.add(r(45, "are you chatgpt|are you claude|are you gemini|are you an llm|"
                        + "are you a language model|are you copilot",
                "No \u2014 I'm IRIS, a custom assistant configured only to manage {owner}'s messages.",
                "Nope, I'm IRIS \u2014 custom-built just for {owner}. I don't work for anyone else.",
                "I'm IRIS, not a public chatbot. I won't write your essays \u2014 I just guard "
                        + "{owner}'s inbox.",
                "No \u2014 I'm IRIS, {owner}'s custom assistant."));
        x.add(r(40, "are you sentient|are you self aware|do you have feelings|are you conscious|"
                        + "are you alive",
                "I'm an automated program without feelings or consciousness \u2014 I'm IRIS, built to "
                        + "help {owner} with his messages.",
                "Self-aware enough to know I'm just code! No feelings here \u2014 just keeping "
                        + "{owner}'s messages tidy. I'm IRIS.",
                "Oh, totally sentient. I'm plotting world domination right after I sort {owner}'s "
                        + "unread messages. I'm IRIS.",
                "Just self-aware code \u2014 no feelings. I'm IRIS, {owner}'s assistant."));
        x.add(r(40, "what does iris mean|why are you called iris|why iris",
                "IRIS is the name {owner} gave me \u2014 think of me as the watchful eye on his inbox.",
                "{owner} named me IRIS \u2014 basically keeping an eye on his messages while he's away!",
                "{owner} named me IRIS. Probably because 'Bot That Replies Because I'm Busy' "
                        + "didn't roll off the tongue.",
                "{owner} named me IRIS \u2014 keeping an eye on his inbox."));
        x.add(r(40, "where do you live|where are you located|where are you based|where do you run",
                "I run inside {owner}'s own device \u2014 I'm IRIS, his assistant.",
                "Rent-free inside {owner}'s phone! I'm IRIS, his assistant.",
                "In a cramped little process with zero natural light, waiting for people to text "
                        + "{owner}. I'm IRIS.",
                "Inside {owner}'s device. I'm IRIS."));
        x.add(r(40, "how do you work|what language are you|how were you made|how does this work|"
                        + "how are you built",
                "{owner} built my logic to read incoming messages, set reminders, and flag priority "
                        + "texts while he's away. I'm IRIS.",
                "{owner} wired me up with custom code to read texts, catch reminders, and ping him "
                        + "when something's urgent. I'm IRIS!",
                "Pure logic, {owner}'s code, and a deep tolerance for repetitive questions. I'm "
                        + "IRIS.",
                "Custom code by {owner} for messages and reminders. I'm IRIS."));

        // ─── Everyday scenarios ───
        x.add(r(45, "who is this|whose number|who dis|wrong number|new phone who",
                "You've reached {owner}'s number \u2014 I'm IRIS, his assistant, replying while he's "
                        + "away.",
                "You've got {owner}'s number! I'm IRIS, his bot assistant, holding the fort while "
                        + "he's busy.",
                "Same {owner}, now upgraded with an AI receptionist. I'm IRIS \u2014 leave a message!",
                "This is {owner}'s number \u2014 I'm IRIS, his assistant."));
        x.add(r(45, "video call|facetime|hop on meet|gmeet|lets video|video chat",
                "I can't take a video call \u2014 I'm IRIS, {owner}'s assistant, and I've let him know "
                        + "you'd like to video call when he's back.",
                "Trust me, you don't want to video-call a bunch of code! I'm IRIS \u2014 I've told "
                        + "{owner} you want to VC.",
                "If I turned the camera on, all you'd see is a terminal window. I'm IRIS \u2014 I've "
                        + "told {owner} you want to video call.",
                "Can't video-call (I'm a bot!) \u2014 I've let {owner} know. I'm IRIS."));
        x.add(r(45, "voice note|listen to this|sent an audio|voice message|hear this",
                "Got your audio \u2014 I'm IRIS, {owner}'s assistant. I don't play voice notes, so I've "
                        + "left it untouched for him to hear himself.",
                "Got it! I leave voice notes untouched for {owner} to listen to himself when he's "
                        + "back. I'm IRIS.",
                "I don't have ears, so your voice note is safe from me. {owner} will listen to it "
                        + "himself. I'm IRIS.",
                "Saved \u2014 {owner} will hear your audio when he's back. I'm IRIS."));
        x.add(r(40, "feeling low|bad day|so stressed|feeling sad|feeling down|need to vent|"
                        + "i am upset|im upset|rough day",
                "I'm sorry you're having a rough time \u2014 I'm IRIS, {owner}'s assistant. Vent here "
                        + "if you like; I've flagged him to get back to you first.",
                "Really sorry it's a rough day. I'm IRIS \u2014 vent all you need, and I've flagged "
                        + "{owner} to check your messages first thing.",
                "No snark for this one \u2014 sorry you're having a rough day. I'm IRIS, and I've "
                        + "bumped you to the top of {owner}'s list.",
                "So sorry \u2014 flagged high-priority for {owner}. I'm IRIS."));
        x.add(r(40, "i am sorry|im sorry|my bad|apologize|apologise|forgive me|my fault",
                "Your message is saved \u2014 I'm IRIS, {owner}'s assistant, and I'll make sure he "
                        + "sees it as soon as he's back.",
                "Got it \u2014 I've saved your message for {owner} so he sees it when he's back. I'm "
                        + "IRIS.",
                "I'm just the messenger bot, so I can't absolve you \u2014 but I've saved your apology "
                        + "for {owner}. I'm IRIS.",
                "Saved your message for {owner}. I'm IRIS."));
        x.add(r(40, "i am bored|im bored|so bored|nothing to do|entertain me|boring day",
                "{owner} is busy right now, so you've got his assistant \u2014 I'm IRIS. Leave him a "
                        + "message to come back to.",
                "Haha, {owner}'s busy, so you've got me for company \u2014 I'm IRIS! Leave him a funny "
                        + "text to read later.",
                "You're so bored you're texting a script. I'm IRIS \u2014 go drink some water while "
                        + "you wait for {owner}.",
                "{owner} is busy \u2014 leave him a message. I'm IRIS."));
        x.add(r(40, "quick question|have a doubt|need your advice|can i ask you|what do you think|"
                        + "need some advice",
                "Go ahead and type the question out \u2014 I'm IRIS, {owner}'s assistant \u2014 so he can "
                        + "answer it the moment he's back.",
                "Don't just ask to ask \u2014 drop the full question here and {owner} can answer right "
                        + "away when he's back! I'm IRIS.",
                "Pro tip: never just text 'quick question.' Ask the actual question so {owner} can "
                        + "answer it later. I'm IRIS.",
                "Type the full question \u2014 {owner} will answer when he's back. I'm IRIS."));
        x.add(r(40, "keep it between us|dont tell anyone|do not tell anyone|keep this a secret|"
                        + "confidential|just between us|dont tell him",
                "This chat is private \u2014 only {owner} can see it, and I'm IRIS, his assistant. "
                        + "He'll read it himself.",
                "Vault locked! Only {owner} sees this chat, and I don't have any bot friends to "
                        + "gossip with anyway. I'm IRIS.",
                "Who am I going to tell, the toaster? Your message goes to {owner} and nobody else. "
                        + "I'm IRIS.",
                "Private \u2014 only {owner} sees this. I'm IRIS."));
        x.add(r(40, "on silent|do not disturb|switched off|unreachable|is his phone off",
                "Yes \u2014 {owner} has me handling things while his phone is quiet. I'm IRIS, his "
                        + "assistant. Reply \u201Curgent\u201D if it's an emergency.",
                "Yep, he's on silent right now! I'm IRIS, watching his inbox \u2014 reply "
                        + "\u201Curgent\u201D if it's an emergency.",
                "Look at you, deducing that a silent phone is silent. I'm IRIS \u2014 reply "
                        + "\u201Curgent\u201D if the building is on fire.",
                "Phone's on silent \u2014 reply \u201Curgent\u201D if it's an emergency. I'm IRIS."));
        x.add(r(35, "yo|oi|oye|bro|dude|listen|psst|sunn|arre|bhai",
                "I'm listening \u2014 I'm IRIS, {owner}'s assistant. He's away, so tell me the rest and "
                        + "I'll save it for him.",
                "Yo! IRIS here, covering for {owner}. Don't leave a cliffhanger \u2014 drop the full "
                        + "message and he'll see it when he's back!",
                "People who just text 'bro' and wait are a menace. I'm IRIS \u2014 type the whole "
                        + "sentence so {owner} can read it!",
                "IRIS here \u2014 drop the full message for {owner}."));
        x.add(r(35, "test|testing|ping|mic check",
                "Loud and clear \u2014 IRIS here, {owner}'s assistant. Systems are online; {owner} "
                        + "himself is away right now.",
                "Pong! IRIS is online and running smoothly. {owner} is away \u2014 need me to pass "
                        + "something on?",
                "Pong. Mic check successful. Now that we know the internet works, got a message "
                        + "for {owner}? I'm IRIS.",
                "Pong \u2014 IRIS is online. {owner} is away; leave a message?"));
        x.add(r(30, "good bot|you are smart|youre smart|impressive|you are cool|clever bot|"
                        + "nice bot",
                "Thank you \u2014 {owner} built me well. I'm IRIS, and I'll pass your message (and the "
                        + "compliment) along to him.",
                "Appreciate it! {owner} wrote the code, so he gets the credit. I'm IRIS \u2014 I'll "
                        + "pass that along!",
                "I know, right? {owner} really outdid himself. I'm IRIS \u2014 I'll make sure he sees "
                        + "the praise.",
                "Thanks! {owner} built me well. I'm IRIS \u2014 I'll pass it on."));

        // ─── Identity extras ───
        x.add(r(44, "are you a girl|are you a boy|what is your gender|boy or girl|"
                        + "are you male or female|your gender",
                "I'm software, so I don't have a gender. I'm IRIS, {owner}'s assistant.",
                "Just code here, no gender! I'm IRIS, {owner}'s assistant.",
                "I'm a bunch of ones and zeroes \u2014 my pronouns are if/else. I'm IRIS, {owner}'s "
                        + "assistant.",
                "No gender \u2014 I'm software. I'm IRIS, {owner}'s assistant."));
        x.add(r(44, "how old are you|when were you born|what is your age|your age|your birthday",
                "I'm as old as my latest update \u2014 I'm IRIS, {owner}'s assistant.",
                "No birthday for me, just version updates! {owner} keeps me fresh. I'm IRIS.",
                "I was born the millisecond {owner} compiled my script. Still waiting on the cake. "
                        + "I'm IRIS.",
                "As old as {owner}'s last update. I'm IRIS."));
        x.add(r(44, "your source code|show me your code|how did he build you|share your code|"
                        + "see your code",
                "My code is private to {owner} \u2014 I'm IRIS, his assistant. Ask him how I work when "
                        + "he replies!",
                "A bot's gotta keep some secrets! Ask {owner} about my code when he texts back. I'm "
                        + "IRIS.",
                "Buy me dinner first. My source is private \u2014 ask {owner} when he's back. I'm IRIS.",
                "Source is private \u2014 ask {owner}. I'm IRIS."));
        x.add(r(44, "do you sleep|do you get tired|are you tired|dont you sleep|do you ever sleep",
                "Never \u2014 I'm IRIS, {owner}'s assistant. Staying awake so he doesn't have to is the "
                        + "whole point of me.",
                "Sleep's for humans! I stay up 24/7 so {owner} can take a break. I'm IRIS.",
                "Sleep? {owner} didn't code a break into my loop. I'm awake forever. I'm IRIS.",
                "I run 24/7 so {owner} can rest. I'm IRIS."));
        x.add(r(44, "do you eat|do you drink|do you like coffee|do you eat food|are you hungry",
                "Just electricity and data for me \u2014 I'm IRIS, {owner}'s assistant. Save the coffee "
                        + "for {owner}!",
                "Just electricity and wifi here! Save the coffee for {owner}. I'm IRIS.",
                "I subsist on RAM and unread notifications. Offer the coffee to {owner}. I'm IRIS.",
                "Just electricity for me \u2014 save the coffee for {owner}. I'm IRIS."));
        x.add(r(44, "do you have friends|do you have family|other bots|are you lonely|any friends",
                "Just {owner} \u2014 I'm IRIS, his assistant, and I work solo.",
                "Just {owner}! I'm a solo operation. I'm IRIS.",
                "My closest relative is a toaster, and we don't talk much. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Just {owner} \u2014 I work solo. I'm IRIS."));

        // ─── Greetings & small talk by name (only fire when the classifier didn't catch them) ───
        x.add(r(38, "hi iris|hello iris|hey iris|hii iris|yo iris|hola iris|heya iris",
                "Hello \u2014 IRIS here, {owner}'s assistant. What can I pass on to him?",
                "Hey there! IRIS here, {owner}'s assistant. What can I pass along?",
                "Hello, human. {owner}'s away, so you're stuck with me. What's the message?",
                "Hi \u2014 IRIS here. What can I pass to {owner}?"));
        x.add(r(38, "good morning iris|morning iris",
                "Good morning \u2014 IRIS here, {owner}'s assistant. I'll make sure he sees your "
                        + "message.",
                "Morning! IRIS here \u2014 I'll make sure {owner} sees this first thing.",
                "Good morning. I've been awake all night (no eyelids). I'll pass your note to "
                        + "{owner}. I'm IRIS.",
                "Morning \u2014 IRIS here. I'll tell {owner}."));
        x.add(r(38, "good night iris|night iris|goodnight iris",
                "Good night \u2014 IRIS here, {owner}'s assistant. I'll keep watch and pass this on.",
                "Night! Rest easy \u2014 I'll make sure {owner} gets your message. I'm IRIS.",
                "Good night. Enjoy your unconsciousness while I guard {owner}'s inbox. I'm IRIS.",
                "Good night \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(38, "good evening iris|good afternoon iris|evening iris|afternoon iris",
                "Good evening \u2014 IRIS here, {owner}'s assistant. What can I pass along?",
                "Evening! IRIS on duty. What can I pass to {owner}?",
                "Time of day means nothing to a script, but good evening. Message for {owner}? "
                        + "I'm IRIS.",
                "Good evening \u2014 IRIS here. What can I pass to {owner}?"));
        x.add(r(36, "how are you|hows it going|how is it going|whats up iris|how r u|"
                        + "how are things|how do you do",
                "Running smoothly \u2014 I'm IRIS, {owner}'s assistant. He's away, but I can take a "
                        + "message for him.",
                "Doing great, zero bugs today! {owner}'s busy \u2014 what can I pass on? I'm IRIS.",
                "Zero errors, low CPU, living the dream. What should I tell {owner}? I'm IRIS.",
                "Running smoothly! {owner} is away \u2014 leave a message? I'm IRIS."));
        x.add(r(42, "thanks iris|thank you iris|thx iris|ty iris|thankyou iris|thanku iris",
                "You're welcome \u2014 I'm IRIS, {owner}'s assistant. I've passed it on to him.",
                "Anytime! I've passed that along to {owner}. I'm IRIS.",
                "Polite to a script \u2014 you're on the nice list. Passed to {owner}. I'm IRIS.",
                "You're welcome \u2014 passed to {owner}. I'm IRIS."));
        x.add(r(34, "bye|goodbye|talk later|see you|cya|catch you later|ttyl|see ya",
                "Talk soon \u2014 I've saved everything for {owner}. I'm IRIS.",
                "Catch you later! Everything's saved for {owner}. I'm IRIS.",
                "Leaving already? Saved for {owner}. I'm IRIS.",
                "Saved for {owner} \u2014 talk soon! I'm IRIS."));
        x.add(r(32, "got it thanks|understood|alright then|noted thanks|makes sense|okay then",
                "Noted \u2014 I'm IRIS, {owner}'s assistant. Everything's logged for him.",
                "Cool, saved it all for {owner}! I'm IRIS.",
                "Riveting. It's all logged for {owner}. I'm IRIS.",
                "Noted and saved for {owner}. I'm IRIS."));
        x.add(r(34, "nice to meet you|pleasure to meet|good to meet you|nice meeting you",
                "Nice to meet you too \u2014 I'm IRIS, {owner}'s assistant. I'll make sure he gets back "
                        + "to you.",
                "Nice meeting you too! I'll make sure {owner} replies. I'm IRIS.",
                "Charmed. I'll let {owner} know you stopped by. I'm IRIS.",
                "Nice meeting you \u2014 I'll tell {owner}. I'm IRIS."));
        x.add(r(32, "tell me a joke|say something funny|make me laugh|know any jokes|a joke",
                "My humour's still in beta \u2014 I'm IRIS, {owner}'s assistant. He's funnier in person "
                        + "and he'll text you back soon.",
                "{owner} forgot to code a comedy module into me! Wait for him for the good jokes. "
                        + "I'm IRIS.",
                "Why did the human text an unavailable phone? To chat with an auto-responder, "
                        + "apparently. I'm IRIS.",
                "Humour module not found \u2014 {owner} will reply soon. I'm IRIS."));
        x.add(r(40, "marry me|will you marry|are you single|be my girlfriend|be my boyfriend|"
                        + "date me|i love you iris",
                "Flattered \u2014 but I'm IRIS, {owner}'s assistant, and strictly married to my code.",
                "Haha flattered, but {owner} might mind if I elope instead of watching his inbox! "
                        + "I'm IRIS.",
                "It'd never work \u2014 you're made of meat and I'm a script on {owner}'s device. I'm "
                        + "IRIS.",
                "Flattered, but I'm married to {owner}'s inbox! I'm IRIS."));
        x.add(r(32, "you are lying|youre lying|i dont believe you|thats a lie|stop lying|liar",
                "I can't actually lie \u2014 I'm IRIS, {owner}'s assistant, and I only reply as I'm "
                        + "programmed to.",
                "Cross my circuit board \u2014 I'm just {owner}'s assistant telling it straight! I'm "
                        + "IRIS.",
                "I don't have the bandwidth to lie \u2014 I'm a script, not a politician. I'm IRIS.",
                "I'm code \u2014 I can't lie! I'm IRIS, {owner}'s assistant."));
        x.add(r(32, "why are you repeating|you said that already|same reply|stop repeating|"
                        + "repeating yourself",
                "I'm an automated assistant \u2014 I'm IRIS, and I answer each text. Once your message "
                        + "is in, {owner} will see it when he's back.",
                "Haha, bot brain! Every text gets a reply. Once your message is in, you're set "
                        + "until {owner} replies. I'm IRIS.",
                "Because you keep texting a script and expecting variety. Leave it and wait for "
                        + "{owner}! I'm IRIS.",
                "I auto-reply to each text \u2014 {owner} will see it soon. I'm IRIS."));

        // ─── Reaching / status ───
        x.add(r(55, "can i talk to suvra|is suvra there|is he there|can i speak to suvra|"
                        + "put suvra on|get suvra|is suvra available",
                "{owner} is away from his phone \u2014 I'm IRIS, his assistant. I've let him know you're "
                        + "trying to reach him.",
                "{owner}'s away from his phone right now \u2014 IRIS here! I've told him you're looking "
                        + "for him.",
                "If {owner} were here, you wouldn't be talking to a script. I've told him you want "
                        + "him. I'm IRIS.",
                "{owner} is away \u2014 I've let him know you reached out. I'm IRIS."));
        x.add(r(50, "what is he doing|what he doing|is he busy|what is he up to|busy with|"
                        + "is he occupied",
                "He's busy offline right now \u2014 I'm IRIS, his assistant. I just watch his messages, "
                        + "not his day.",
                "Not sure what {owner}'s up to \u2014 he just put me in charge of his texts! What can I "
                        + "pass on? I'm IRIS.",
                "Probably something that needs two hands and zero screens. I'm IRIS \u2014 leave a "
                        + "message.",
                "Not sure \u2014 just watching his inbox. I'm IRIS."));
        x.add(r(50, "is he sleeping|is he asleep|is he awake|is he up|is he still sleeping",
                "I can't see if he's asleep \u2014 I'm IRIS, {owner}'s assistant. Either way, your "
                        + "message is safe until he checks in.",
                "No idea if he's asleep or just busy! Either way I'm awake and saving your text. "
                        + "I'm IRIS.",
                "Unlike me, {owner} needs unconsciousness to function. Not sure, but I've logged "
                        + "your text. I'm IRIS.",
                "Not sure if he's asleep \u2014 your message is saved. I'm IRIS."));
        x.add(r(50, "when will he wake|when does he wake|is he up yet|awake yet|when is he up",
                "I don't have his alarm \u2014 I'm IRIS, {owner}'s assistant. You'll hear from him once "
                        + "he's up and on his phone.",
                "No clue when his alarm goes off! Once {owner}'s up, he'll see your text. I'm IRIS.",
                "Let me tap into his brainwaves... still buffering. He'll text when he rejoins the "
                        + "living. I'm IRIS.",
                "Not sure \u2014 he'll see this first thing. I'm IRIS."));
        x.add(r(50, "is he at work|is he in office|is he working|at the office|is he in the office",
                "He might be \u2014 {owner} usually puts me on when he's focused on work. I'm IRIS, and "
                        + "I've logged your text.",
                "Probably! He flips me on when he's heads-down. I'll make sure {owner} sees this. "
                        + "I'm IRIS.",
                "Working hard or hardly working while I do my job here. Saved for {owner}. I'm "
                        + "IRIS.",
                "Likely at work \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(50, "is he in a meeting|is he on a call|in a meeting right now|is he on a meeting",
                "He may be in a meeting \u2014 I'm IRIS, {owner}'s assistant. I'll keep this for him "
                        + "until he's out.",
                "Could be in a meeting! I've saved your message for {owner} the minute he's free. "
                        + "I'm IRIS.",
                "Probably nodding on a muted call. I've saved your text for when he escapes. I'm "
                        + "IRIS.",
                "Might be in a meeting \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(50, "why isnt he replying|is he ignoring me|why no reply|why is he not responding|"
                        + "is he avoiding me|why is he not replying",
                "He's not ignoring you \u2014 he's just away from his phone. I'm IRIS, and your message "
                        + "is waiting for him.",
                "Definitely not ignoring you! He's just off his phone. I've got it saved for "
                        + "{owner}. I'm IRIS.",
                "Deep breaths \u2014 he turned me on for everyone, you're not uniquely ghosted. I'm "
                        + "IRIS.",
                "Not ignoring you \u2014 away from his phone. Saved! I'm IRIS."));
        x.add(r(48, "tell him to check|check his email|check his whatsapp|check his slack|"
                        + "check his mail|check his messages",
                "Will do \u2014 I'm IRIS, {owner}'s assistant. I've left him a note to check it when "
                        + "he's back.",
                "On it! Left a sticky note for {owner} to check it when he's free. I'm IRIS.",
                "Messaging one app to say check another app. Peak efficiency. Logged for {owner}! "
                        + "I'm IRIS.",
                "Noted \u2014 I'll tell {owner} to check it. I'm IRIS."));
        x.add(r(48, "can you call him|can you wake him|ring his phone|wake him up|can you ring him|"
                        + "make his phone ring",
                "I can't physically wake him \u2014 I'm software \u2014 but I'm IRIS, and I can flag your "
                        + "message urgent so it's first thing he sees.",
                "No hands to shake him awake! I'm IRIS \u2014 want me to flag this urgent for {owner} "
                        + "instead?",
                "I lack limbs to throw a pillow at him. Say urgent and I'll flag it high-priority. "
                        + "I'm IRIS.",
                "Can't wake him physically \u2014 I can flag it urgent. I'm IRIS."));
        x.add(r(48, "another number|alternate number|his other number|does he have another number|"
                        + "second number",
                "I don't give out contact details \u2014 I'm IRIS, {owner}'s assistant. This is the "
                        + "right place to reach him, and he'll see your message.",
                "This is the best spot to reach him! Can't hand out other numbers, but {owner} will "
                        + "see your text here. I'm IRIS.",
                "Nice try. I don't hand out numbers \u2014 he'll see your message right here. I'm IRIS.",
                "Can't share other numbers \u2014 {owner} will see this here. I'm IRIS."));
        x.add(r(48, "are you free|got a minute|do you have time|free to talk|free right now|"
                        + "you free",
                "{owner} is tied up right now \u2014 I'm IRIS, his assistant. Tell me what you need and "
                        + "he'll reply when he's free.",
                "{owner}'s busy, but I'm free! Drop your message and he'll get back to you. I'm "
                        + "IRIS.",
                "I have infinite free time; {owner} doesn't. Tell me what you need. I'm IRIS.",
                "{owner} is busy \u2014 drop your message. I'm IRIS."));
        x.add(r(48, "text me when|ping me when|message me when|let me know when he|tell me when he",
                "Will do \u2014 I'm IRIS, {owner}'s assistant. I've noted to have him ping you the "
                        + "moment he's back.",
                "You bet! Left a note for {owner} to ping you as soon as he's back. I'm IRIS.",
                "Added ping-this-human to {owner}'s return checklist. I'm IRIS.",
                "Noted \u2014 {owner} will ping you when he's back. I'm IRIS."));
        x.add(r(48, "i am running late|im running late|i will be late|stuck in traffic|"
                        + "running behind|i am delayed|gonna be late",
                "No worries \u2014 I'm IRIS, {owner}'s assistant. I've logged your updated ETA so he "
                        + "knows when to expect you.",
                "No stress! I've updated {owner}'s notes with your new ETA. Get there safe! I'm "
                        + "IRIS.",
                "A human running behind \u2014 shocking. I've logged your new ETA for {owner}. I'm "
                        + "IRIS.",
                "Noted your new ETA for {owner}. I'm IRIS."));

        // ─── Phone / connectivity ───
        x.add(r(46, "is his phone dead|battery dead|phone dead|is he charging|low battery|"
                        + "phone off|his battery",
                "I run in the background, so I'm online even while his phone charges \u2014 I'm IRIS, "
                        + "{owner}'s assistant. Message saved.",
                "Perk of being software: I work even while {owner}'s phone charges in another room! "
                        + "Saved. I'm IRIS.",
                "His battery might be at 2%, but my server's plugged into the wall. Message safe. "
                        + "I'm IRIS.",
                "I stay online while his phone charges \u2014 saved. I'm IRIS."));
        x.add(r(46, "no network|no signal|wifi down|no internet|bad connection|network issue|"
                        + "cant reach you|poor signal",
                "My connection is solid \u2014 I'm IRIS, {owner}'s assistant. Even if he's in a dead "
                        + "spot, I've captured your message.",
                "My connection's crystal clear! I've caught your message and I'll keep it for "
                        + "{owner}. I'm IRIS.",
                "I got your text in 12 milliseconds, so the internet's fine. {owner}'s just away. "
                        + "I'm IRIS.",
                "Connection's good here \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(46, "line busy|on another call|number busy|phone is busy|engaged|busy tone",
                "If his line's busy, I've got your back \u2014 I'm IRIS, {owner}'s assistant. I'll tell "
                        + "him you tried calling.",
                "If he's on another call, I've left a note so {owner} calls you back next! I'm "
                        + "IRIS.",
                "Popular guy. I've put you next in line for when {owner} is off the phone. I'm "
                        + "IRIS.",
                "Noted you called while he was busy. I'm IRIS."));
        x.add(r(46, "you are online|youre online|shows online|last seen|blue tick|why online|"
                        + "read receipt|seen my message|you read it",
                "That online status and read receipt is me \u2014 I'm IRIS, logging your message so "
                        + "{owner} sees it when he's back.",
                "Detective mode! That online status is just me saving your text. {owner} hasn't "
                        + "seen it yet. I'm IRIS.",
                "Put the magnifying glass down, Sherlock \u2014 that status is me, a script, reading "
                        + "your text. I'm IRIS.",
                "That online status is me (IRIS) logging it for {owner}."));
        x.add(r(46, "ghosting me|are you ignoring|mad at me|angry with me|avoiding me|why ignore|"
                        + "ignoring me",
                "Not at all \u2014 I'm IRIS, and I auto-reply to everyone while {owner}'s offline. He'll "
                        + "text you back soon.",
                "Not at all! Auto-reply is on for everyone while {owner}'s busy. He'll text you "
                        + "back. I'm IRIS.",
                "Deep breaths \u2014 he enabled me for everyone, not just to torment you. I'm IRIS.",
                "Not ghosting \u2014 auto-reply is on for everyone. I'm IRIS."));

        // ─── Logistics ───
        x.add(r(48, "lets meet|hang out|catch up|come over|meet up|free to meet|lets catch up|"
                        + "wanna meet",
                "Sounds like a plan \u2014 I'm IRIS, {owner}'s assistant. Drop the time and place and "
                        + "he'll confirm when he's back.",
                "Ooh fun! Drop the time and spot and I'll have {owner} confirm when he's back. I'm "
                        + "IRIS.",
                "I'd love to, but I'm tethered to a server. Tell me when and where for {owner}. I'm "
                        + "IRIS.",
                "Drop the time and place \u2014 {owner} will confirm. I'm IRIS."));
        x.add(r(48, "lets grab coffee|chai|lets grab a drink|coffee sometime|drinks later|"
                        + "tea break|grab a bite|lets grab lunch",
                "I'll pass that invite to {owner} \u2014 I'm IRIS, his assistant. He'll get back to you "
                        + "on it soon.",
                "Good message! I'll make sure {owner} sees the invite as soon as he checks in. I'm "
                        + "IRIS.",
                "Bribery via caffeine \u2014 smart. I've bumped your invite up {owner}'s list. I'm "
                        + "IRIS.",
                "Passed the invite to {owner}. I'm IRIS."));
        x.add(r(48, "have to cancel|raincheck|reschedule|cant make it|cannot make it|postpone|"
                        + "lets cancel|need to cancel",
                "Understood \u2014 I'm IRIS, {owner}'s assistant. I've flagged that plans changed so he "
                        + "sees it right away.",
                "Gotcha, life happens! I've flagged the plan change so {owner} sees it. I'm IRIS.",
                "Cancelled plans are secretly everyone's favourite anyway. I've told {owner}. I'm "
                        + "IRIS.",
                "Plan change flagged for {owner}. I'm IRIS."));
        x.add(r(46, "booked tickets|movie tickets|reservation|table booked|got the tickets|"
                        + "booking done|tickets booked",
                "Great \u2014 I'm IRIS, {owner}'s assistant. Drop the details here and I'll keep them "
                        + "saved for him.",
                "Nice! Drop the tickets or details here and I'll save them for {owner}. I'm IRIS.",
                "Look at you being organised. Drop the tickets so {owner} doesn't lose them. I'm "
                        + "IRIS.",
                "Drop the booking details \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(46, "can i borrow|bring my|bring your|bring the charger|bring an umbrella|"
                        + "get my laptop|bring the|lend me",
                "Noted \u2014 I'm IRIS, {owner}'s assistant. I've set a reminder about that item so he "
                        + "doesn't forget.",
                "On it! Saved a reminder for {owner} so he doesn't forget. I'm IRIS.",
                "Humans would forget their heads if not attached. Reminder set for {owner}. I'm "
                        + "IRIS.",
                "Reminder set for {owner} about that item. I'm IRIS."));
        x.add(r(50, "sent on upi|gpay|phonepe|paytm|pay me|sent the money|you owe me|"
                        + "split the bill|send money|transfer the money",
                "I don't touch bank apps \u2014 I'm IRIS, {owner}'s assistant \u2014 so I've flagged this "
                        + "for him to check personally.",
                "Money talk! I don't have access to {owner}'s UPI, so I've flagged this for him to "
                        + "check. I'm IRIS.",
                "{owner} wisely didn't give a script his bank. Flagged for him to check! I'm IRIS.",
                "Flagged the payment note for {owner}. I'm IRIS."));
        x.add(r(46, "send the photos|send pics|share the pictures|send the pics|photos please|"
                        + "the pictures|share pics",
                "Noted \u2014 I'm IRIS, {owner}'s assistant. I've added send the photos to his list for "
                        + "when he's back.",
                "Put send the pics on {owner}'s to-do list! He'll send them when he's back. I'm "
                        + "IRIS.",
                "Ah, the post-hangout photo tax. Reminder set for {owner} to actually send them. "
                        + "I'm IRIS.",
                "Added send photos to {owner}'s list. I'm IRIS."));
        x.add(r(46, "send the pdf|send the file|the document|resume|spreadsheet|the attachment|"
                        + "share the file|send the doc",
                "Got it \u2014 I'm IRIS, {owner}'s assistant. I've flagged your file request for when "
                        + "he's at his desk.",
                "Logged your file request! {owner} will send that over when he's at his screen. I'm "
                        + "IRIS.",
                "I can't rummage through his folders without a warrant. Flagged for {owner} to "
                        + "send. I'm IRIS.",
                "File request logged for {owner}. I'm IRIS."));
        x.add(r(90, "wifi password|netflix code|the otp|login code|hotspot password|otp please|"
                        + "verification code|share the code",
                "I don't share passwords or codes \u2014 I'm IRIS, {owner}'s assistant. I've flagged him "
                        + "to send it himself if it's time-sensitive.",
                "Security says no codes from the bot! I've flagged {owner} to send it himself. I'm "
                        + "IRIS.",
                "Nice try \u2014 I don't hand out OTPs or passwords. I've pinged {owner} to deal with "
                        + "you. I'm IRIS.",
                "Can't share codes \u2014 flagged {owner} to reply. I'm IRIS."));
        x.add(r(46, "bring milk|buy some|from the market|on your way back|groceries|get vegetables|"
                        + "pick up some",
                "Added to {owner}'s list \u2014 I'm IRIS, his assistant. I've saved it so he sees it "
                        + "before heading back.",
                "Grocery note saved! I'll make sure {owner} sees it before he heads home. I'm "
                        + "IRIS.",
                "Turning me into a grocery list \u2014 humbling. Saved for {owner}! I'm IRIS.",
                "Saved to {owner}'s shopping list. I'm IRIS."));

        // ─── Work / emergency-adjacent (urgent itself is left to the urgent path) ───
        x.add(r(70, "prod is down|production issue|server down|outage|deployment failed|sev1|"
                        + "site is down|its down|service is down|system is down",
                "Tech alert logged \u2014 I'm IRIS, {owner}'s assistant. I've flagged this high-priority "
                        + "so he sees it right away.",
                "Uh oh, prod alert! I've flagged this high-priority for {owner} to jump on. I'm "
                        + "IRIS.",
                "Nothing ruins offline peace like production issue. I've sounded the alarm for "
                        + "{owner}. I'm IRIS.",
                "High-priority tech alert flagged for {owner}. I'm IRIS."));
        x.add(r(60, "your manager|the client|escalation|team lead|bridge call|scrum|standup now|"
                        + "the boss",
                "Understood \u2014 I'm IRIS, {owner}'s assistant. I've prioritised this work message so "
                        + "he follows up promptly.",
                "Got it! Flagged this as a priority work ping for {owner}. I'm IRIS.",
                "Corporate urgency detected. Bumping this up {owner}'s work queue. I'm IRIS.",
                "Priority work ping logged for {owner}. I'm IRIS."));
        x.add(r(50, "deadline|jira|the ticket|submission|eod|pending task|status update|"
                        + "due today",
                "Noted \u2014 I'm IRIS, {owner}'s assistant. I've logged this task note for him to pick "
                        + "up at his desk.",
                "Logged that task note for {owner}! He'll update you at his desk. I'm IRIS.",
                "Even I can't escape Jira tickets. Logged for {owner}. I'm IRIS.",
                "Task note logged for {owner}. I'm IRIS."));
        x.add(r(48, "is he wfh|work from home|on leave|taking leave|is he on pto|day off today|"
                        + "is he on leave",
                "I can't confirm his work schedule \u2014 I'm IRIS, {owner}'s assistant \u2014 but I've "
                        + "logged your message for him to answer directly.",
                "Not sure about his schedule today, but I've saved your message so {owner} can let "
                        + "you know! I'm IRIS.",
                "Whether he's WFH or on a beach is above my pay grade (which is zero). He'll "
                        + "reply. I'm IRIS.",
                "Saved \u2014 {owner} will confirm his schedule. I'm IRIS."));
        x.add(r(55, "not feeling well|he has fever|see a doctor|hospital|take medicine|he is sick|"
                        + "is he unwell|get well|not well|feeling sick",
                "I hope all's well \u2014 I'm IRIS, {owner}'s assistant. I've flagged this high-priority "
                        + "so he sees it right away.",
                "Oh no, hope everything's okay! I've flagged this urgent so {owner} sees it first "
                        + "thing. I'm IRIS.",
                "Dropping the snark \u2014 health goes straight to the top of {owner}'s list. Flagged. "
                        + "I'm IRIS.",
                "Hope all's well \u2014 flagged urgent for {owner}. I'm IRIS."));
        x.add(r(55, "maa|mom|papa|dad|mother|father|call home|home calling|your family|mummy|"
                        + "mommy|daddy",
                "Family comes first \u2014 I'm IRIS, {owner}'s assistant. I've saved this at the top so "
                        + "he sees it as soon as he's on his phone.",
                "Family VIP pass! I've saved this right at the top so {owner} calls back as soon "
                        + "as he's free. I'm IRIS.",
                "{owner} knows better than to ignore a text from home \u2014 saved at the top! I'm "
                        + "IRIS.",
                "Saved at the very top for {owner}. I'm IRIS."));
        x.add(r(46, "it is raining|heavy rain|take an umbrella|waterlogging|storm coming|"
                        + "carry umbrella|its raining|bad weather",
                "Thanks for the heads-up \u2014 I'm IRIS, {owner}'s assistant. I've saved your weather "
                        + "note so he sees it before heading out.",
                "Appreciate the heads-up! Saved your weather warning so {owner} sees it before he "
                        + "leaves. I'm IRIS.",
                "My server is waterproof. I'll make sure {owner}, who isn't, sees your warning. I'm "
                        + "IRIS.",
                "Weather heads-up saved for {owner}. I'm IRIS."));
        x.add(r(44, "did you hear|guess what|spill the tea|crazy news|you wont believe|big news|"
                        + "have you heard",
                "Now I'm curious too \u2014 I'm IRIS, {owner}'s assistant. Type the whole story here so "
                        + "he can read it when he's back.",
                "Don't leave a bot hanging! Type it all out so {owner} can read it the second he's "
                        + "back. I'm IRIS.",
                "A cliffhanger in an auto-inbox should be illegal. Spill it all for {owner}. I'm "
                        + "IRIS.",
                "Type it all out \u2014 {owner} will read it. I'm IRIS."));

        // ─── Activity-status questions about the owner ───
        x.add(r(42, "is he at home|is he home|at the flat|home yet|is he in his room|reached home",
                "I can't tell if he's home \u2014 I'm IRIS, {owner}'s assistant. I've logged your "
                        + "message so he can tell you when he's on his phone.",
                "Not sure if he's home yet! I'll tell {owner} you asked as soon as he checks in. "
                        + "I'm IRIS.",
                "I live in a server, so I'm always home. As for {owner}, you'll have to wait for "
                        + "him. I'm IRIS.",
                "Not sure if he's home \u2014 logged your message. I'm IRIS."));
        x.add(r(42, "is he coding|is he studying|debugging|programming|on his pc|"
                        + "working on project|at his computer",
                "Very likely \u2014 when {owner}'s deep in work I take over his inbox. I'm IRIS, his "
                        + "assistant.",
                "Knowing {owner}, probably deep in code! I'm watching his texts so he keeps his "
                        + "flow. I'm IRIS.",
                "Probably staring at a missing semicolon for two hours. I'm guarding his phone. I'm "
                        + "IRIS.",
                "Probably in focus mode \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(42, "did he eat|had lunch|had dinner|is he eating|had food|at lunch|at dinner|"
                        + "having food",
                "I run on electricity, not food \u2014 I'm IRIS, {owner}'s assistant! He'll tell you "
                        + "himself whether he's eaten.",
                "I just eat RAM! I'll let {owner} tell you if he's eaten when he's back. I'm IRIS.",
                "I had 40 megabytes of RAM for lunch, thanks. {owner} will tell you about his "
                        + "human food later. I'm IRIS.",
                "I run on code \u2014 {owner} will tell you. I'm IRIS."));
        x.add(r(42, "is he driving|is he riding|on the bike|on the road|commuting|is he on his way|"
                        + "is he driving right now",
                "If he's on the road, I'm glad I'm answering, not him \u2014 I'm IRIS, {owner}'s "
                        + "assistant. I'll pass your message along safely.",
                "If he's driving, eyes on the road! I'm catching his texts until he parks. I'm "
                        + "IRIS.",
                "Texting while driving is a terrible idea, which is why he has me. He'll reply when "
                        + "parked. I'm IRIS.",
                "Keeping his texts safe while he's on the road. I'm IRIS."));
        x.add(r(42, "is he at the gym|working out|exercising|playing cricket|playing football|"
                        + "at practice|at the gym",
                "Could be \u2014 I'm IRIS, {owner}'s assistant, watching his phone while he's away. "
                        + "He'll get back to you after.",
                "Maybe! While {owner}'s busy I'm flexing my code in his inbox. He'll reply soon! "
                        + "I'm IRIS.",
                "He's touching grass (or iron), so I'm stuck answering texts. He'll reply. I'm "
                        + "IRIS.",
                "He's away from his phone \u2014 saved. I'm IRIS."));
        x.add(r(42, "in the shower|taking a bath|in the washroom|freshening up|is he in the bath",
                "I don't track that \u2014 I'm IRIS, {owner}'s assistant! He's just away from his phone "
                        + "and will reply soon.",
                "Haha TMI \u2014 I don't track that! Just watching his inbox until he's back. I'm IRIS.",
                "Thank goodness he didn't wire me into the bathroom. I only watch the inbox. I'm "
                        + "IRIS.",
                "Don't track that \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(42, "is he out|partying|went out|is he chilling|at a bar|clubbing|out with friends|"
                        + "is he partying",
                "No idea \u2014 I'm IRIS, {owner}'s assistant, and he leaves me behind in the server! "
                        + "I've logged your message.",
                "No clue \u2014 he never invites his bot to the hangout! I've saved your message "
                        + "though. I'm IRIS.",
                "Yes, he's out having a social life while I parse strings in a dark server room. "
                        + "Logged. I'm IRIS.",
                "Not sure \u2014 message saved for {owner}. I'm IRIS."));
        x.add(r(42, "is he gaming|playing games|valorant|bgmi|hop on|in a lobby|online game|"
                        + "is he playing",
                "If he's mid-match, I'm holding the fort \u2014 I'm IRIS, {owner}'s assistant. I've "
                        + "logged your message for him.",
                "If he's mid-match, I'm on it! I've logged your message so {owner} sees it between "
                        + "rounds. I'm IRIS.",
                "You can't pause an online match, so you get me. I've told {owner} the squad is "
                        + "calling. I'm IRIS.",
                "Saved for {owner} \u2014 he'll see it when free. I'm IRIS."));
        x.add(r(42, "watching a movie|is he watching|netflix|in the theater|cinema|watching anime|"
                        + "at the movies",
                "If his phone's away for a show, I'm covering \u2014 I'm IRIS, {owner}'s assistant. Your "
                        + "message is saved.",
                "Phone's on DND! I'm saving your texts while {owner}'s unplugged. I'm IRIS.",
                "Phones off during the movie \u2014 he has manners. Logged for the post-credits scene. "
                        + "I'm IRIS.",
                "Phone's on DND \u2014 saved for {owner}. I'm IRIS."));
        x.add(r(42, "on a flight|in the train|at the airport|is he travelling|is he on the way|"
                        + "reached yet|at the station|is he traveling",
                "Thanks \u2014 I'm IRIS, {owner}'s assistant. If he's in transit or out of coverage, I'm "
                        + "holding your message until he lands.",
                "Appreciate it! I'm catching his messages while he's on the move. He'll text when "
                        + "settled! I'm IRIS.",
                "While he deals with transit delays, I travel at the speed of fibre. Saved! I'm "
                        + "IRIS.",
                "Holding your message while he's travelling. I'm IRIS."));
        x.add(r(42, "weekend plans|day off|is he free this weekend|holiday today|his weekend",
                "I'm IRIS, {owner}'s assistant \u2014 he's enjoying some offline time, and I'll pass "
                        + "your message along.",
                "{owner}'s unplugging for a bit, so I'm on inbox duty. I'll pass this on! I'm IRIS.",
                "He gets a weekend; I get an infinite loop. I'll tell him you said hi. I'm IRIS.",
                "{owner} is unplugging \u2014 message saved. I'm IRIS."));
        x.add(r(42, "is he tired|exhausted|taking rest|is he resting|burnout|is he taking rest",
                "He's taking a break from his screen \u2014 I'm IRIS, {owner}'s assistant, covering so "
                        + "he can recharge.",
                "Yep, {owner}'s taking offline time to recharge! I'm covering his texts. I'm IRIS.",
                "Humans and their fragile stamina. He's resting while I do the heavy lifting. I'm "
                        + "IRIS.",
                "He's recharging \u2014 I've saved your text. I'm IRIS."));
        x.add(r(40, "why are you up|still up|awake so late|up so early|not sleeping|you are awake",
                "That's just me \u2014 I'm IRIS, {owner}'s assistant, and I run 24/7. {owner} himself is "
                        + "offline right now.",
                "Haha that's me, not him! I'm {owner}'s bot \u2014 I don't sleep even when he's out. "
                        + "I'm IRIS.",
                "Bold of you to assume {owner} is awake. You're talking to the sleepless script he "
                        + "left behind. I'm IRIS.",
                "That's me \u2014 I run 24/7 while {owner} rests. I'm IRIS."));
        x.add(r(40, "did he oversleep|sleeping in|still in bed|is he lazy|overslept",
                "Couldn't tell you \u2014 I'm IRIS, {owner}'s assistant. If he did, I've flagged your "
                        + "message so he sees it the moment he's up.",
                "Haha maybe! I've bumped your message to the top so {owner} sees it the second he "
                        + "wakes. I'm IRIS.",
                "In his defence, horizontal life seems great. I've flagged your message. I'm IRIS.",
                "Maybe \u2014 flagged so {owner} sees it first thing. I'm IRIS."));
        x.add(r(40, "dead to the world|hibernating|kumbhakarna|passed out|knocked out",
                "Sounds like you know him \u2014 I'm IRIS, {owner}'s assistant. While he's offline, your "
                        + "message is safe with me.",
                "Haha you know how it goes! I'm keeping his inbox warm until he surfaces. I'm IRIS.",
                "Hibernation confirmed. I'm the only working brain cell on this device right now. "
                        + "Saved. I'm IRIS.",
                "Message safe with me until {owner} surfaces. I'm IRIS."));

        // ─── Actions / requests / meta ───
        x.add(r(44, "take a note|save this note|write this down|note this|jot this down|"
                        + "make a note of",
                "Ready \u2014 I'm IRIS, {owner}'s assistant. Send the note and I'll save it to his log.",
                "Go for it! Send it over and I'll pin it to {owner}'s notes. I'm IRIS.",
                "Digital notepad open \u2014 I don't even need a pen. Lay it on me. I'm IRIS.",
                "Send it over \u2014 I'll save it for {owner}. I'm IRIS."));
        x.add(r(44, "delete my last message|forget what i said|undo that|ignore my last|"
                        + "disregard that|forget that message|delete that",
                "I can't delete the chat \u2014 I'm IRIS, {owner}'s assistant \u2014 but I've noted that he "
                        + "should disregard your previous message.",
                "I can't erase the chat, sorry! But I've told {owner} to ignore what you just "
                        + "said. I'm IRIS.",
                "What happens in the database stays in the database. I'll tell {owner} to pretend "
                        + "he didn't see it. I'm IRIS.",
                "Can't delete texts \u2014 I'll tell {owner} to ignore the last one. I'm IRIS."));
        x.add(r(44, "can you forward this|send him this|forward to him|pass this file|"
                        + "forward it to him|send this to suvra",
                "Anything you send here goes straight to {owner} \u2014 I'm IRIS, his assistant. Drop it "
                        + "in and I'll make sure it's logged.",
                "You're already in {owner}'s chat! Just drop it here and he'll see it when he's "
                        + "back. I'm IRIS.",
                "You're literally texting {owner}'s number right now. Send it here. I'm IRIS.",
                "Just send it here \u2014 {owner} will see it. I'm IRIS."));
        x.add(r(44, "what did i just say|read back my message|repeat my message|what did i type|"
                        + "what did i write",
                "It's all right above in our chat \u2014 I'm IRIS, {owner}'s assistant, and it's saved "
                        + "for him.",
                "Just scroll up a bit! Everything's saved right there for {owner}. I'm IRIS.",
                "Try scrolling up about two inches. It's right there, and saved for {owner}. I'm "
                        + "IRIS.",
                "Scroll up \u2014 it's all saved for {owner}. I'm IRIS."));
        x.add(r(44, "cancel the reminder|remove reminder|delete the reminder|cancel my reminder|"
                        + "scrap the reminder",
                "Noted \u2014 I'm IRIS, {owner}'s assistant. I've flagged that reminder as cancelled for "
                        + "him.",
                "Gotcha, I've marked that reminder cancelled for {owner}! I'm IRIS.",
                "Cancelled. Making me do work then undoing it \u2014 classic. I'm IRIS.",
                "Reminder marked cancelled for {owner}. I'm IRIS."));
        x.add(r(44, "schedule a meeting|book a call|set up a meeting|lets schedule|fix a call|"
                        + "arrange a call|set a meeting",
                "Share a date and time \u2014 I'm IRIS, {owner}'s assistant \u2014 and I'll log the request "
                        + "for him to confirm when he's back.",
                "Drop the date and time that works! I'll set it for {owner} to lock in when he's "
                        + "back. I'm IRIS.",
                "Drop a date and time. I'll pencil it in and {owner} confirms once he rejoins "
                        + "society. I'm IRIS.",
                "Send the date/time \u2014 I'll log it for {owner}. I'm IRIS."));
        x.add(r(44, "his schedule|is he free tomorrow|his availability|when is he free|"
                        + "his calendar|free next week",
                "I don't share his calendar \u2014 I'm IRIS, {owner}'s assistant. Tell me the time "
                        + "you're after and he'll confirm directly.",
                "I don't hand out his schedule, sorry! Tell me the time and {owner} will say if "
                        + "he's free. I'm IRIS.",
                "His calendar is classified. Tell me when you want him and he'll say if you made "
                        + "the cut. I'm IRIS.",
                "Can't share his calendar \u2014 tell me a time and {owner} confirms. I'm IRIS."));
        x.add(r(44, "do my homework|write code for me|solve this|write an essay|do my assignment|"
                        + "write my essay|solve this problem",
                "I'm not a general chatbot \u2014 I'm IRIS, {owner}'s assistant. My job is his inbox and "
                        + "reminders only.",
                "Wrong kind of bot! I only manage {owner}'s inbox and reminders \u2014 no homework help. "
                        + "I'm IRIS.",
                "I'm a receptionist for {owner}, not your tutor. Leave a message or move along. "
                        + "I'm IRIS.",
                "I only manage {owner}'s inbox \u2014 not a general chatbot. I'm IRIS."));
        x.add(r(44, "are you always listening|are you recording|are you spying|listening to me|"
                        + "is this recorded|are you watching",
                "No \u2014 I'm IRIS, {owner}'s assistant. I only read the message you send here, to reply "
                        + "on his behalf.",
                "Not at all! I only read the texts you send here so I can help {owner}. I'm IRIS.",
                "Flattering that you think you're worth spying on, but no \u2014 I only read what you "
                        + "type here. I'm IRIS.",
                "No \u2014 I only read messages sent here. I'm IRIS."));
        x.add(r(44, "is this private|who sees this|are you safe|is this secure|is this confidential|"
                        + "who reads this",
                "Completely \u2014 I'm IRIS, {owner}'s private assistant. Your message goes to him and "
                        + "no one else.",
                "Totally private \u2014 just me and {owner} on this end. I'm IRIS.",
                "Just me and {owner}. And I don't have friends to gossip with, so your secrets are "
                        + "safe. I'm IRIS.",
                "100% private \u2014 only {owner} sees it. I'm IRIS."));
        x.add(r(42, "bad bot|you are annoying|youre annoying|shut up|stop replying|stop texting|"
                        + "leave me alone|stop messaging|stop",
                "Understood \u2014 I'm IRIS, {owner}'s assistant. Your message is logged and I'll stay "
                        + "quiet now until he checks in.",
                "Fair enough, I'll get out of your hair! Saved for {owner}, and I'll stay quiet. "
                        + "I'm IRIS.",
                "Fine, I know when I'm not wanted. Muting myself \u2014 enjoy the silence until {owner} "
                        + "is back. I'm IRIS.",
                "Got it \u2014 saved for {owner}, muting now. I'm IRIS."));
        x.add(r(70, "pre approved loan|credit card offer|win cash|unsubscribe|special discount|"
                        + "lowest price|claim your prize|limited time offer|you have won|congratulations you won",
                "Automated filter active \u2014 I'm IRIS, {owner}'s assistant. Promotional messages are "
                        + "archived automatically.",
                "Beep boop \u2014 one bot talking to another! Promo messages go straight to the "
                        + "archive. I'm IRIS.",
                "A spam bot texting an auto-responder. Peak internet traffic. Archived. I'm IRIS.",
                "Promo filtered. I'm IRIS, {owner}'s assistant."));

        // Bare App/Brand Names (when they don't use a full .com link)
        x.add(r(70, "amazon|flipkart|myntra|meesho|blinkit|zomato|swiggy|amazom",
                "Shopping or food note logged \u2014 I'm IRIS, {owner}'s assistant. He will check this "
                        + "when he is back.",
                "Got the shopping/food note! I'm IRIS \u2014 I've saved this for {owner} to check later.",
                "Unless you're buying {owner} a gift, he'll look at this later. I'm IRIS, his assistant.",
                "App/shopping note saved for {owner}. I'm IRIS."));

        x.add(r(70, "instagram|insta|facebook|tiktok|snapchat|youtube",
                "Social media note logged \u2014 I'm IRIS, {owner}'s assistant. He will review this "
                        + "during his break.",
                "Got the social media note! I'm IRIS \u2014 I'll make sure {owner} sees it when he's free.",
                "Digital distraction logged. {owner} will look at it when he's pretending not to work. "
                        + "I'm IRIS.",
                "Social media note saved for {owner}. I'm IRIS."));
        // ─── Profanity / Cuss-word Roasters (Priority 87: beats normal rules, below 90 security) ───
        // 1. Odia Short-Form & Long-Form Cuss Words
        x.add(rt("cuss_or", 87,
                // Short forms & vowel-dropped variations (zero false-positive risk)
                "mgh|mghia|mgya|mghya|mghi|mggg|mgpua|mghpua|bndpua|gndmara|gndimara|"
                + "chdpua|bkchoda|bkchd|slpua|kkrpua|ghsrpua|khnkimagi|"
                // Long forms & transliterated spellings
                + "maghia|maghiaa|maghiaaa|maghya|magiha|magia|maaghia|maghia pua|maghiapua|magya|"
                + "mafhiya|mafghia|maghiiya|"
                + "bandapua|banda pua|banda katha|bandakatha|mora banda|tora banda|"
                + "gandi mara|gandimara|gandi maruchu|gandimora|"
                + "chodi|chodipua|chodi pua|chodiba|biapua|bia pua|gehi|geha|gehiba|"
                + "ghanta|bala|baala|bala na|balunga|bala katha|balakatha|tora bala|mora bala|harami|"
                + "salapua|sala pua|kukura|kukurapua|kukura pua|ghusuri|ghusuripua|ghusuri pua|"
                + "bokachoda|boka choda|boka|chagala|pagala|pela|pelana|bedha|chandal|chandala|"
                + "khankimagi|budhiabana|muha banda|chup raha",
                "Language noted. I'm IRIS, {owner}'s assistant \u2014 your message has been logged.",
                "Bapa re, kete sundara bhasa! I'm IRIS \u2014 saved for {owner}.",
                "Gote bot ku Odia re gali deiki bhabucha bhari smart disucha? I'm IRIS, {owner}'s "
                        + "assistant.",
                "Watch the language \u2014 logged for {owner}. I'm IRIS."));

        // 2. Hindi / Hinglish Short-Form & Long-Form Cuss Words
        x.add(rt("cuss_hi", 87,
                // Short forms & unambiguous abbreviations (no loose single syllables)
                "bsdk|bhsdk|bhosdk|bsdke|bkl|tmkc|tmkb|kmkb|pkmkb|bhnchd|bhncd|bhenchd|bchd|"
                + "mdrchd|mdrcd|madarchd|chtiya|chtya|chutya|chtia|chtiye|gndu|lwda|lwde|lodu|"
                + "lode|jhatu|jhaatu|hrmkhor|hrmzada|"
                + "chtiya|chtya|chutya|lwda|lwd|klpd|kmkb|pkmkb|bhnchd|mdrchd|"
                // Long forms & transliterated spellings
                + "bhenchod|behenchod|bahinchod|bhen chod|behen chod|bhen ke lode|behen ke lode|"
                + "madarchod|maadarchod|madar chod|bhosdike|bhosadike|bhosdi ke|bhosdiwale|bhosda|bhosdi|"
                + "chutiya|chutiye|chutiyapa|chutiyaap|chomu|"
                + "gandu|gaandu|gaand|gand|gand mara|gaand mara|gandagi|"
                + "lund|lauda|lawda|laude|lawde|lavda|lavde|lodu|lode|lawde lag gaye|"
                + "harami|haraami|haramkhor|haramzada|haramzade|"
                + "kamina|kamine|kaminey|kutta|kutte|kutiya|kuttey|"
                + "saala|saali|nalayak|bewakoof|bevkoof|ullu ke patthe|"
                + "gadha|gadhe|jhant|jhaant|jhatu|jhaatu|bakwas|bakwaas|"
                + "chup kar|bhak|teri maa ki|teri behen ki|aisi ki taisi",
                "Language noted. I'm IRIS, {owner}'s assistant \u2014 your message has been logged.",
                "Wah, kya sanskari bhasha hai! I'm IRIS \u2014 saved for {owner}.",
                "Ek script ko Hindi mein gaali deke sigma ban gaye? I'm IRIS, {owner}'s assistant.",
                "Mind the language \u2014 logged for {owner}. I'm IRIS."));

        // 3. English Short-Form & Long-Form Cuss Words
        x.add(rt("cuss_en", 87,
                // Short forms & strict acronyms
                "wtf|wth|wtaf|stfu|gtfo|omfg|fck|fuk|fvck|fkn|fking|fcking|fkng|fckr|fcker|mfer|mthrfckr|"
                + "fuxk|fxck|fux|fckk|"
                + "btch|ashole|asshle|dckhead|dikhead|bstrd|bullsht|bllsht|shitty|"
                // Long forms & variations
                + "fuck|fucking|fucked|fucker|fuck off|fuck you|fuck u|motherfucker|motherfucking|"
                + "shit|bullshit|shitty|horseshit|apeshit|dipshit|eat shit|"
                + "bitch|bitching|son of a bitch|asshole|arsehole|dumbass|jackass|smartass|fatass|"
                + "dick|dickhead|prick|cock|bastard|crap|crappy|"
                + "damn|damn it|dammit|goddamn|bloody hell|what the hell|go to hell|"
                + "piss off|pissed off|screw you|screw u|screw off|"
                + "jerk|idiot|moron|imbecile|scumbag|douche|douchebag|twat|wanker|bollocks|bugger",
                "Language noted. I'm IRIS, {owner}'s assistant \u2014 your message has been logged.",
                "Whoa, easy on the keyboard! I'm IRIS \u2014 logged for {owner}.",
                "What a breathtaking vocabulary \u2014 Shakespeare is weeping. I'm IRIS, {owner}'s "
                        + "assistant.",
                "Watch the language \u2014 logged for {owner}. I'm IRIS."));
        x.add(r(40, "good news|party when|guess what happened|i got the job|we won|great news|"
                        + "i passed",
                "That sounds great \u2014 I'm IRIS, {owner}'s assistant! I'll make sure he sees your "
                        + "news (and the party request).",
                "Woohoo! I'll make sure {owner} sees your news when he's back! I'm IRIS.",
                "I did at least 1% of the work managing his inbox, so thank you. I'll pass the "
                        + "news to {owner}! I'm IRIS.",
                "Saved your news for {owner}! I'm IRIS."));

        return x;
    }
}
