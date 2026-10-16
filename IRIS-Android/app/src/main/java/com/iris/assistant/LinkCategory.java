package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a shared link by its domain (shopping, social, meeting, maps, food, tech, docs,
 * music, entertainment, finance, or generic) and produces a fitting per-tone reply. Pure and
 * offline-testable. A meeting link is flagged urgent so the responder can escalate it.
 */
public final class LinkCategory {

    public enum Kind {
        NONE, MEETING, SHOPPING, SOCIAL, MAPS, FOOD, TECH, DOCS, MUSIC, ENTERTAINMENT,
        FINANCE, GENERIC
    }

    private LinkCategory() { }

    // A URL or bare domain. Mirrors SmartReplies' URL shape so both agree on what a link is.
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.)\\S+|\\b[\\w-]+\\.(com|in|net|org|io|co|me|ly|app|dev|us|uk|gg|tv|"
            + "info|link|xyz)(/\\S*)?\\b", Pattern.CASE_INSENSITIVE);

    public static boolean hasUrl(String text) {
        return text != null && URL.matcher(text).find();
    }

    public static boolean isUrgent(Kind kind) { return kind == Kind.MEETING; }

    /** The category of the first link in the text, or NONE if there is no link. */
    public static Kind classify(String text) {
        if (text == null) return Kind.NONE;
        Matcher m = URL.matcher(text);
        if (!m.find()) return Kind.NONE;
        String url = m.group().toLowerCase(Locale.ROOT);
        // youtu.be / youtube shorts -> social.
        if (url.contains("youtu.be") || url.contains("youtube.com/shorts")) return Kind.SOCIAL;
        for (int i = 0; i < DOMAINS.length; i++) {
            for (String d : DOMAINS[i]) {
                if (url.contains(d)) return KINDS[i];
            }
        }
        return Kind.GENERIC;
    }

    // Domain groups, parallel to KINDS.
    private static final Kind[] KINDS = {
            Kind.MEETING, Kind.SHOPPING, Kind.SOCIAL, Kind.MAPS, Kind.FOOD, Kind.TECH,
            Kind.DOCS, Kind.MUSIC, Kind.ENTERTAINMENT, Kind.FINANCE
    };
    private static final String[][] DOMAINS = {
            {"meet.google.com", "zoom.us", "teams.microsoft.com", "webex.com"},
            {"amazon.in", "amzn.in", "amzn.to", "amazon.com", "flipkart.com", "myntra.com",
                    "ajio.com", "meesho.com", "nykaa.com", "tatacliq.com", "blinkit.com",
                    "zepto.com", "bigbasket.com"},
            {"instagram.com", "ig.me", "tiktok.com", "x.com", "twitter.com", "reddit.com",
                    "redd.it", "pin.it", "pinterest.com", "facebook.com", "fb.watch"},
            {"maps.google.com", "goo.gl/maps", "maps.app.goo.gl", "maps.apple.com"},
            {"swiggy.com", "zomato.com"},
            {"github.com", "gitlab.com", "stackoverflow.com", "vercel.app", "vercel.com",
                    "atlassian.net", "pastebin.com"},
            {"drive.google.com", "docs.google.com", "dropbox.com", "1drv.ms", "onedrive.live.com",
                    "wetransfer.com"},
            {"spotify.com", "music.apple.com", "music.youtube.com", "jiosaavn.com", "wynk.in"},
            {"netflix.com", "primevideo.com", "hotstar.com", "bookmyshow.com"},
            {"splitwise.com", "paypal.me", "razorpay.me", "cash.app"}
    };

    /** A reply for the category and tone. Order: PROFESSIONAL, CASUAL, SARCASTIC, BRIEF. */
    public static String reply(Kind kind, ReplyTone.Tone tone) {
        String[] variants = REPLIES[kind == null ? Kind.GENERIC.ordinal() : kind.ordinal()];
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= variants.length || variants[idx] == null || variants[idx].isEmpty()) {
            return variants[0];
        }
        return variants[idx];
    }

    // Indexed by Kind.ordinal(); NONE unused but present for alignment.
    private static final String[][] REPLIES = new String[Kind.values().length][];
    static {
        REPLIES[Kind.NONE.ordinal()] = g();
        REPLIES[Kind.MEETING.ordinal()] = new String[]{
                "Meeting URL identified \u2014 I'm IRIS, {owner}'s assistant. I've elevated it to the "
                        + "top of his queue so he can jump in.",
                "Meeting link! Flagging it high-priority so {owner} hops on the call asap. I'm IRIS.",
                "Video-call link detected. Sounding the corporate alarm so {owner} can scramble to "
                        + "join. I'm IRIS, his assistant.",
                "High-priority meeting link flagged for {owner}. I'm IRIS."};
        REPLIES[Kind.SHOPPING.ordinal()] = new String[]{
                "Shopping link logged \u2014 I'm IRIS, {owner}'s assistant. I don't open shopping links, "
                        + "but it's saved for him to check.",
                "Got the shopping link! Pinned for {owner} to check the deal when he's back. I'm "
                        + "IRIS.",
                "Shopping link detected. {owner}'s bank balance is trembling, but I've saved it for "
                        + "him. I'm IRIS.",
                "Product link saved for {owner}. I'm IRIS."};
        REPLIES[Kind.SOCIAL.ordinal()] = new String[]{
                "Social link received \u2014 I'm IRIS, {owner}'s assistant. I've queued it for his break "
                        + "time.",
                "Ooh, content! Saved that reel for {owner} to watch on his break. I'm IRIS.",
                "Digital distraction detected \u2014 logged for when {owner} pretends to take a break "
                        + "from real work. I'm IRIS.",
                "Reel/video link queued for {owner}. I'm IRIS."};
        REPLIES[Kind.MAPS.ordinal()] = new String[]{
                "Location received \u2014 I'm IRIS, {owner}'s assistant. I've highlighted the address at "
                        + "the top of his log.",
                "Got the map pin! Highlighted it so {owner} knows where to go. I'm IRIS.",
                "Coordinates received. I won't leak {owner}'s GPS to you, but I've pinned yours for "
                        + "him. I'm IRIS.",
                "Location saved for {owner}. I'm IRIS."};
        REPLIES[Kind.FOOD.ordinal()] = new String[]{
                "Food/menu link logged \u2014 I'm IRIS, {owner}'s assistant. I've pinned it so he sees "
                        + "the order.",
                "Food link! Pinned so {owner} doesn't miss the order. I'm IRIS.",
                "Food detected. If you're ordering biryani for {owner}, consider it a VIP override. "
                        + "I'm IRIS.",
                "Food/menu link pinned for {owner}. I'm IRIS."};
        REPLIES[Kind.TECH.ordinal()] = new String[]{
                "Tech/repo link logged \u2014 I'm IRIS, {owner}'s assistant. Saved for when he's back at "
                        + "his desk.",
                "Code/tech link logged! {owner} will check it at his terminal. I'm IRIS.",
                "Repos and tickets \u2014 even his inbox can't escape work. Logged for {owner}. I'm "
                        + "IRIS.",
                "Code link logged for {owner}. I'm IRIS."};
        REPLIES[Kind.DOCS.ordinal()] = new String[]{
                "Document/drive link received \u2014 I'm IRIS, {owner}'s assistant. I've logged the file "
                        + "for his review.",
                "Got the drive/doc link! Saved so {owner} can pull it up when free. I'm IRIS.",
                "Cloud file logged. Assuming it's not a 2 GB zip of vacation photos, {owner} will "
                        + "review it. I'm IRIS.",
                "File/doc link saved for {owner}. I'm IRIS."};
        REPLIES[Kind.MUSIC.ordinal()] = new String[]{
                "Music link received \u2014 I'm IRIS, {owner}'s assistant. I've added it to his listening "
                        + "queue.",
                "Tunes! Saved that track into {owner}'s queue for his headphones. I'm IRIS.",
                "I run on electricity, not music, but I've saved the soundtrack for {owner}. I'm "
                        + "IRIS.",
                "Music link queued for {owner}. I'm IRIS."};
        REPLIES[Kind.ENTERTAINMENT.ordinal()] = new String[]{
                "Booking/streaming link logged \u2014 I'm IRIS, {owner}'s assistant. Saved at the top "
                        + "for him.",
                "Got the show/ticket link! Pinned so {owner} sees the plans. I'm IRIS.",
                "Entertainment link detected \u2014 pinned so {owner} doesn't leave you hanging on "
                        + "plans. I'm IRIS.",
                "Ticket/streaming link pinned for {owner}. I'm IRIS."};
        REPLIES[Kind.FINANCE.ordinal()] = new String[]{
                "Payment/expense link received \u2014 I'm IRIS, {owner}'s assistant. I've flagged it for "
                        + "his personal review.",
                "Expense link logged! I don't touch {owner}'s wallet, but I've flagged it for him. "
                        + "I'm IRIS.",
                "A bill split. {owner} wisely locked me out of his bank, but I've pinned the debt "
                        + "for him. I'm IRIS.",
                "Payment link flagged for {owner}. I'm IRIS."};
        REPLIES[Kind.GENERIC.ordinal()] = g();
    }

    private static String[] g() {
        return new String[]{
                "Link received \u2014 I'm IRIS, {owner}'s assistant. I don't open links, but it's saved "
                        + "for him to check.",
                "Got the link! Saved so {owner} can open it when he checks his phone. I'm IRIS.",
                "Unknown link logged. I didn't click it (basic cybersecurity), but it's waiting for "
                        + "{owner}. I'm IRIS.",
                "Link saved for {owner}. I'm IRIS."};
    }
}
