package com.iris.assistant;

import java.util.Arrays;
import java.util.Set;

/** Offline checks for the loose phrase matcher. */
public final class PhraseMatcherTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Canonicalization: stopwords dropped, synonyms applied.
        Set<String> c = PhraseMatcher.canonicalContent("send me the pics");
        check(c.contains("send") && c.contains("photo"), "pics canonicalises to photo, send kept");
        check(!c.contains("me") && !c.contains("the"), "stopwords dropped");

        check(Arrays.equals(PhraseMatcher.contentTokens("send the photos"),
                new String[]{"send", "photo"}), "content tokens of 'send the photos'");
        check(Arrays.equals(PhraseMatcher.contentTokens("share the pictures"),
                new String[]{"send", "photo"}), "share/pictures canonicalise to send/photo");

        // containsAll: order-free, synonym- and typo-tolerant.
        check(PhraseMatcher.containsAll(
                PhraseMatcher.canonicalContent("send me the pics tomorrow"),
                new String[]{"send", "photo"}), "paraphrase contains all required");
        check(!PhraseMatcher.containsAll(
                PhraseMatcher.canonicalContent("i went home for dinner"),
                new String[]{"send", "photo"}), "unrelated message does not");
        check(PhraseMatcher.containsAll(
                PhraseMatcher.canonicalContent("can you forward the document"),
                new String[]{"send", "file"}), "forward/document -> send/file");

        // Fuzzy: short words exact-only, longer words tolerate edits, first letter must agree.
        check(PhraseMatcher.fuzzyEquals("photo", "photo"), "exact");
        check(PhraseMatcher.fuzzyEquals("photoo", "photo"), "one extra letter in a 5-char word");
        check(!PhraseMatcher.fuzzyEquals("sent", "send"), "4-char words are exact only (sent!=send)");
        check(PhraseMatcher.fuzzyEquals("meating", "meeting"), "one edit in a 7-char word");
        check(!PhraseMatcher.fuzzyEquals("foto", "photo"), "different first letter never matches");

        // Negation detection on the padded flat.
        check(PhraseMatcher.hasNegation(" dont send the photos "), "dont is negation");
        check(PhraseMatcher.hasNegation(" do not call him "), "do not is negation");
        check(PhraseMatcher.hasNegation(" never mind "), "never mind is negation");
        check(!PhraseMatcher.hasNegation(" send the photos "), "no negation");

        check(PhraseMatcher.editDistance("", "abc") == 3, "edit distance to empty");
        check(PhraseMatcher.editDistance("abc", "abc") == 0, "edit distance identical");

        System.out.println("Passed " + checks + " phrase matcher checks");
    }
}
