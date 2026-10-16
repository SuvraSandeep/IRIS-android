package com.iris.assistant;

import java.util.Locale;

/** Offline checks for link-domain classification and replies. */
public final class LinkCategoryTest {
    private static int checks;
    private static void eq(LinkCategory.Kind a, LinkCategory.Kind b, String what) {
        checks++;
        if (a != b) throw new AssertionError("FAILED: " + what + " expected " + b + " got " + a);
    }
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        eq(LinkCategory.classify("check this https://amazon.in/dp/B0ABC"), LinkCategory.Kind.SHOPPING, "amazon");
        eq(LinkCategory.classify("https://amzn.to/3xyz"), LinkCategory.Kind.SHOPPING, "amzn.to");
        eq(LinkCategory.classify("join https://meet.google.com/abc-defg-hij"), LinkCategory.Kind.MEETING, "google meet");
        eq(LinkCategory.classify("https://us02web.zoom.us/j/123"), LinkCategory.Kind.MEETING, "zoom");
        eq(LinkCategory.classify("https://www.instagram.com/reel/xyz"), LinkCategory.Kind.SOCIAL, "instagram reel");
        eq(LinkCategory.classify("https://youtu.be/abc"), LinkCategory.Kind.SOCIAL, "youtu.be -> social");
        eq(LinkCategory.classify("https://youtube.com/shorts/abc"), LinkCategory.Kind.SOCIAL, "yt shorts -> social");
        eq(LinkCategory.classify("https://maps.app.goo.gl/xyz"), LinkCategory.Kind.MAPS, "maps");
        eq(LinkCategory.classify("https://zomato.com/x"), LinkCategory.Kind.FOOD, "zomato");
        eq(LinkCategory.classify("https://github.com/u/repo"), LinkCategory.Kind.TECH, "github");
        eq(LinkCategory.classify("https://drive.google.com/file/d/x"), LinkCategory.Kind.DOCS, "drive");
        eq(LinkCategory.classify("https://open.spotify.com/track/x"), LinkCategory.Kind.MUSIC, "spotify");
        eq(LinkCategory.classify("https://bookmyshow.com/x"), LinkCategory.Kind.ENTERTAINMENT, "bms");
        eq(LinkCategory.classify("https://splitwise.com/x"), LinkCategory.Kind.FINANCE, "splitwise");
        eq(LinkCategory.classify("https://some-random-blog.com/post"), LinkCategory.Kind.GENERIC, "generic");
        eq(LinkCategory.classify("no link here"), LinkCategory.Kind.NONE, "no url");

        // Meeting is urgent; others are not.
        t(LinkCategory.isUrgent(LinkCategory.Kind.MEETING), "meeting is urgent");
        t(!LinkCategory.isUrgent(LinkCategory.Kind.SHOPPING), "shopping not urgent");

        // Every category reply names IRIS and mentions owner (after fill), in every tone.
        for (LinkCategory.Kind k : LinkCategory.Kind.values()) {
            if (k == LinkCategory.Kind.NONE) continue;
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                String r = LinkCategory.reply(k, tone)
                        .replace("{owner}", ReplyTemplates.OWNER).toLowerCase(Locale.ROOT);
                t(r.contains("iris"), k + "/" + tone + " names IRIS");
                t(r.contains(ReplyTemplates.OWNER.toLowerCase(Locale.ROOT)), k + "/" + tone + " names owner");
            }
        }
        System.out.println("Passed " + checks + " link category checks");
    }
}
