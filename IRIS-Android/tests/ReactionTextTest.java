package com.iris.assistant;

/** Offline checks for emoji-reaction detection. */
public final class ReactionTextTest {
    private static int checks;
    private static void yes(String b, String what) {
        checks++;
        if (!ReactionText.looksLikeReaction(b)) throw new AssertionError("FAILED (want reaction): " + what + " :: " + b);
    }
    private static void no(String b, String what) {
        checks++;
        if (ReactionText.looksLikeReaction(b)) throw new AssertionError("FAILED (want message): " + what + " :: " + b);
    }

    public static void main(String[] args) {
        // WhatsApp reaction shapes.
        yes("Reacted \uD83D\uDC4D to \"see you tomorrow\"", "whatsapp thumbs-up reaction");
        yes("Reacted \uD83D\uDE02 to your message", "whatsapp laugh reaction");
        yes("\uD83D\uDC4D to \"let's meet\"", "emoji-leading reaction");
        // Instagram / iMessage shapes.
        yes("Liked your message", "instagram liked");
        yes("Loved \"your photo\"", "loved a photo");
        yes("Laughed at your message", "imessage laughed");

        // Real messages that must NOT be treated as reactions.
        no("I reacted badly to the news yesterday", "a real sentence containing 'reacted'");
        no("can you react to my email", "a request, not a reaction");
        no("to be honest i liked the movie", "liked without message/photo object near");
        no("see you tomorrow", "ordinary message");
        no("\uD83D\uDC4D", "a bare emoji is handled elsewhere, not a reaction");
        no("", "empty");

        System.out.println("Passed " + checks + " reaction text checks");
    }
}
