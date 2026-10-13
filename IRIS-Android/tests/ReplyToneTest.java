package com.iris.assistant;

import java.util.Arrays;

/**
 * Offline checks for tone resolution and the silence decision.
 *
 * Three properties carry the risk here.
 *
 * **Sarcasm must be unreachable by accident.** It is the one tone that can damage a relationship,
 * so it may only arrive by the owner naming a contact.
 *
 * **A relationship must beat a name pattern.** The owner's "eric" rule is meant for Ericsson
 * colleagues, but a relative named Erica would match any substring version of it. Whole-word
 * matching and relationship priority are both pinned here.
 *
 * **Silence must depend on tone.** "ok" earns nothing when IRIS is professional and earns a remark
 * when it is sarcastic, which is the owner's rule and the reason tone resolves first.
 */
public class ReplyToneTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        resolutionOrderIsMostSpecificFirst();
        relationshipBeatsNamePattern();
        sarcasmIsUnreachableWithoutNamingSomeone();
        unconfiguredRespondersAreFormal();
        relationshipDefaultTones();
        namePatternsAreWholeWord();
        patternOrderIsMeaningful();
        acknowledgementsSpeakOnlyWhenSarcastic();
        unrecognisedIsAlwaysSilent();
        mediaIsOffByDefault();
        everythingElseAnswers();
        parsingFailsSafe();
        System.out.println("Passed " + checks + " reply tone checks");
    }

    private static void resolutionOrderIsMostSpecificFirst() {
        // Explicit per-contact tone beats every other source, including a relationship.
        equal(ReplyTone.resolve(ReplyTone.Tone.BRIEF, ReplyTone.Relation.WORK,
                        ReplyTone.Tone.PROFESSIONAL, ReplyTone.Tone.CASUAL,
                        ReplyTone.Tone.PROFESSIONAL),
                ReplyTone.Tone.BRIEF, "an explicit contact tone wins outright");

        // With no explicit tone, the relationship decides.
        equal(ReplyTone.resolve(null, ReplyTone.Relation.FAMILY, ReplyTone.Tone.PROFESSIONAL,
                        null, ReplyTone.Tone.PROFESSIONAL),
                ReplyTone.Tone.CASUAL, "a relationship beats a pattern and the global default");

        // With neither, a matched name pattern decides.
        equal(ReplyTone.resolve(null, ReplyTone.Relation.UNKNOWN, ReplyTone.Tone.PROFESSIONAL,
                        ReplyTone.Tone.CASUAL, ReplyTone.Tone.BRIEF),
                ReplyTone.Tone.PROFESSIONAL, "a name pattern beats a window and the default");

        // Then an active time window.
        equal(ReplyTone.resolve(null, ReplyTone.Relation.UNKNOWN, null,
                        ReplyTone.Tone.PROFESSIONAL, ReplyTone.Tone.CASUAL),
                ReplyTone.Tone.PROFESSIONAL, "a time window beats the global default");

        // Then the global default.
        equal(ReplyTone.resolve(null, ReplyTone.Relation.UNKNOWN, null, null,
                        ReplyTone.Tone.CASUAL),
                ReplyTone.Tone.CASUAL, "the global default applies when nothing else does");
    }

    /**
     * The concrete case this ordering exists for. "eric" is meant to catch Ericsson colleagues;
     * a relative named Erica must not be answered formally because of it.
     */
    private static void relationshipBeatsNamePattern() {
        equal(ReplyTone.resolve(null, ReplyTone.Relation.FAMILY, ReplyTone.Tone.PROFESSIONAL,
                        null, ReplyTone.Tone.PROFESSIONAL),
                ReplyTone.Tone.CASUAL,
                "a family member matching a work pattern is still treated as family");
        equal(ReplyTone.resolve(null, ReplyTone.Relation.WORK, ReplyTone.Tone.CASUAL,
                        null, ReplyTone.Tone.CASUAL),
                ReplyTone.Tone.PROFESSIONAL,
                "a work contact stays professional even against a casual pattern");
    }

    /** Sarcasm can only arrive by the owner naming someone. */
    private static void sarcasmIsUnreachableWithoutNamingSomeone() {
        check(ReplyTone.toneFor(ReplyTone.Relation.FAMILY) != ReplyTone.Tone.SARCASTIC,
                "family does not default to sarcasm");
        check(ReplyTone.toneFor(ReplyTone.Relation.FRIEND) != ReplyTone.Tone.SARCASTIC,
                "friends do not default to sarcasm");
        check(ReplyTone.toneFor(ReplyTone.Relation.WORK) != ReplyTone.Tone.SARCASTIC,
                "colleagues certainly do not default to sarcasm");
        check(ReplyTone.toneFor(ReplyTone.Relation.UNKNOWN) == null,
                "an unlabelled contact implies no tone at all");
        equal(ReplyTone.resolve(ReplyTone.Tone.SARCASTIC, ReplyTone.Relation.UNKNOWN, null, null,
                        ReplyTone.Tone.PROFESSIONAL),
                ReplyTone.Tone.SARCASTIC, "sarcasm applies when set explicitly on the contact");
    }

    private static void unconfiguredRespondersAreFormal() {
        equal(ReplyTone.resolve(null, null, null, null, null), ReplyTone.Tone.PROFESSIONAL,
                "with nothing configured at all, the tone is professional");
        equal(ReplyTone.resolve(null, ReplyTone.Relation.UNKNOWN, null, null, null),
                ReplyTone.Tone.PROFESSIONAL, "an unknown relation with no default is professional");
    }

    private static void relationshipDefaultTones() {
        equal(ReplyTone.toneFor(ReplyTone.Relation.FAMILY), ReplyTone.Tone.CASUAL,
                "family is casual, because formality to a sibling reads as coldness");
        equal(ReplyTone.toneFor(ReplyTone.Relation.FRIEND), ReplyTone.Tone.CASUAL,
                "friends are casual");
        equal(ReplyTone.toneFor(ReplyTone.Relation.WORK), ReplyTone.Tone.PROFESSIONAL,
                "work is professional");
        equal(ReplyTone.toneFor(null), null, "a null relation implies nothing");
    }

    /** Whole-word matching, using the owner's real contact names from the probe. */
    private static void namePatternsAreWholeWord() {
        check(ReplyTone.nameMatches("Rajesh Bhai Eric Kolkata Shapoorji", "eric"),
                "a real colleague name matches the eric rule");
        check(ReplyTone.nameMatches("Debraj Naskar Eric Kol", "eric"),
                "the second real colleague name matches too");
        check(ReplyTone.nameMatches("Eric", "eric"), "the name alone matches");
        check(ReplyTone.nameMatches("ERIC KOLKATA", "Eric"), "matching ignores case both ways");

        // The false positives a substring match would have produced.
        check(!ReplyTone.nameMatches("Erica", "eric"), "Erica is NOT a colleague");
        check(!ReplyTone.nameMatches("Frederica Sen", "eric"), "Frederica is NOT a colleague");
        check(!ReplyTone.nameMatches("Ericsson Support", "eric"),
                "Ericsson needs its own pattern rather than being caught by eric");
        check(!ReplyTone.nameMatches("America Desk", "eric"), "America is not a colleague");

        check(!ReplyTone.nameMatches(null, "eric"), "a null name matches nothing");
        check(!ReplyTone.nameMatches("Eric", null), "a null pattern matches nothing");
        check(!ReplyTone.nameMatches("Eric", "  "), "a blank pattern matches nothing");
        check(!ReplyTone.nameMatches("", "eric"), "an empty name matches nothing");
    }

    private static void patternOrderIsMeaningful() {
        java.util.List<String> patterns = Arrays.asList("eric", "bidisha");
        equal(ReplyTone.firstMatchingPattern("Rajesh Bhai Eric Kolkata", patterns), 0,
                "the first matching pattern is reported");
        equal(ReplyTone.firstMatchingPattern("Bidisha", patterns), 1,
                "a later pattern is found by its own index");
        equal(ReplyTone.firstMatchingPattern("Someone Else", patterns), -1,
                "no match reports -1");
        equal(ReplyTone.firstMatchingPattern("Eric", null), -1, "a null list reports -1");
    }

    /** The owner's rule: silence when polite, a remark when not. */
    private static void acknowledgementsSpeakOnlyWhenSarcastic() {
        check(!ReplyTone.answers(MessageCategory.Kind.ACKNOWLEDGEMENT,
                        ReplyTone.Tone.PROFESSIONAL),
                "'ok' earns no professional reply");
        check(!ReplyTone.answers(MessageCategory.Kind.ACKNOWLEDGEMENT, ReplyTone.Tone.CASUAL),
                "'ok' earns no casual reply");
        check(!ReplyTone.answers(MessageCategory.Kind.ACKNOWLEDGEMENT, ReplyTone.Tone.BRIEF),
                "'ok' earns no brief reply either");
        check(ReplyTone.answers(MessageCategory.Kind.ACKNOWLEDGEMENT, ReplyTone.Tone.SARCASTIC),
                "'ok' DOES earn a sarcastic reply, which is the whole point");

        // End to end against the real classifier, so the two classes agree.
        MessageCategory.Result ok = MessageCategory.classify("ok");
        equal(ok.kind, MessageCategory.Kind.ACKNOWLEDGEMENT, "'ok' classifies as acknowledgement");
        check(!ReplyTone.answers(ok.kind, ReplyTone.Tone.PROFESSIONAL),
                "classified 'ok' is silent when professional");
        check(ReplyTone.answers(ok.kind, ReplyTone.Tone.SARCASTIC),
                "classified 'ok' speaks when sarcastic");
        for (String word : new String[]{"k", "hmm", "theek", "noted"}) {
            MessageCategory.Result r = MessageCategory.classify(word);
            check(!ReplyTone.answers(r.kind, ReplyTone.Tone.PROFESSIONAL),
                    "'" + word + "' is silent when professional");
            check(ReplyTone.answers(r.kind, ReplyTone.Tone.SARCASTIC),
                    "'" + word + "' speaks when sarcastic");
        }
    }

    private static void unrecognisedIsAlwaysSilent() {
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            check(!ReplyTone.answers(MessageCategory.Kind.NONE, tone),
                    "an unrecognised message is silent in " + tone
                            + ", because IRIS cannot compose an answer");
        }
        check(!ReplyTone.answers(null, ReplyTone.Tone.SARCASTIC),
                "a null category is silent rather than throwing");

        // The catch-all exists so the owner CAN answer ordinary messages, and is off until he
        // says so. IRIS has no language model, so every reply it could give is a generic
        // acknowledgement that cannot refer to what was said -- acceptable as a choice, never
        // acceptable as a default.
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            check(!ReplyTone.answers(MessageCategory.Kind.OTHER, tone),
                    "the catch-all is off by default in " + tone);
        }
        // And it must be a real category with real wording, or the switch would do nothing.
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            check(!ReplyTemplates.variants(MessageCategory.Kind.OTHER, tone, false).isEmpty(),
                    "the catch-all has wording to use in " + tone + " once switched on");
        }
        // The owner's real Twilio message, end to end: it reaches the catch-all rather than
        // falling through to nothing, which is what sent him looking for a bug.
        MessageCategory.Result twilio = MessageCategory.classify(
                "Standard auto-reply. You can send outbound messages via the Tryout UI or API, "
                        + "or customize the reply by configuring a custom webhook in the Tryout UI");
        equal(twilio.kind, MessageCategory.Kind.OTHER,
                "the Twilio message lands in the catch-all");
        check(!ReplyTone.answers(twilio.kind, ReplyTone.Tone.SARCASTIC),
                "and is still silent until the owner enables it, even in sarcastic");
    }

    private static void mediaIsOffByDefault() {
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            check(!ReplyTone.answers(MessageCategory.Kind.MEDIA_ONLY, tone),
                    "photos are not auto-answered in " + tone + " by default");
        }
    }

    private static void everythingElseAnswers() {
        MessageCategory.Kind[] speak = {
                MessageCategory.Kind.MENTION, MessageCategory.Kind.URGENT,
                MessageCategory.Kind.FESTIVAL, MessageCategory.Kind.BIRTHDAY,
                MessageCategory.Kind.TIME_GREETING, MessageCategory.Kind.WEEKEND,
                MessageCategory.Kind.THANKS, MessageCategory.Kind.CHECK_IN,
                MessageCategory.Kind.CONGRATS, MessageCategory.Kind.TO_IRIS,
                MessageCategory.Kind.GREETING, MessageCategory.Kind.QUESTION,
                MessageCategory.Kind.SHORT
        };
        for (MessageCategory.Kind kind : speak) {
            check(ReplyTone.answers(kind, ReplyTone.Tone.PROFESSIONAL),
                    kind + " earns a reply by default");
        }
    }

    private static void parsingFailsSafe() {
        equal(ReplyTone.parseTone("SARCASTIC"), ReplyTone.Tone.SARCASTIC, "a valid tone parses");
        equal(ReplyTone.parseTone("casual"), ReplyTone.Tone.CASUAL, "lower case parses");
        equal(ReplyTone.parseTone("  BRIEF  "), ReplyTone.Tone.BRIEF, "padding is tolerated");
        equal(ReplyTone.parseTone("nonsense"), ReplyTone.Tone.PROFESSIONAL,
                "an unknown tone falls back to professional, never to sarcasm");
        equal(ReplyTone.parseTone(null), ReplyTone.Tone.PROFESSIONAL, "null is professional");

        equal(ReplyTone.parseRelation("FAMILY"), ReplyTone.Relation.FAMILY, "a relation parses");
        equal(ReplyTone.parseRelation("work"), ReplyTone.Relation.WORK, "lower case parses");
        equal(ReplyTone.parseRelation("rubbish"), ReplyTone.Relation.UNKNOWN,
                "an unknown relation is UNKNOWN, so it falls through rather than guessing");
        equal(ReplyTone.parseRelation(null), ReplyTone.Relation.UNKNOWN, "null is UNKNOWN");
    }
}
