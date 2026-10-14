package com.iris.assistant;

/** Offline checks for SMS-shorthand expansion. */
public final class ShorthandTest {
    private static int checks;
    private static void eq(String a, String b, String what) {
        checks++;
        if (!a.equals(b)) throw new AssertionError("FAILED: " + what + " expected \"" + b
                + "\" got \"" + a + "\"");
    }

    public static void main(String[] args) {
        eq(Shorthand.expand("Iris r u there"), "Iris are you there", "r/u expand");
        eq(Shorthand.expand("whats ur server name"), "whats your server name", "ur -> your");
        eq(Shorthand.expand("we are using his battery u know thay"),
                "we are using his battery you know that", "u/thay expand");
        eq(Shorthand.expand("tell his acc number"), "tell his account number", "acc -> account");
        eq(Shorthand.expand("send his loc"), "send his location", "loc -> location");
        // Punctuation and emoji are preserved around the token.
        eq(Shorthand.expand("r u there?"), "are you there?", "trailing punctuation kept");
        eq(Shorthand.expand("u there \uD83D\uDE02"), "you there \uD83D\uDE02", "emoji preserved");
        // Real words that merely contain a shorthand letter are untouched.
        eq(Shorthand.expand("run to the shop now"), "run to the shop now", "no false expansion");
        eq(Shorthand.expand("under the table"), "under the table", "under not expanded");
        System.out.println("Passed " + checks + " shorthand checks");
    }
}
