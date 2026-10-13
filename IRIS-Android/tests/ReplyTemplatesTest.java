package com.iris.assistant;

import java.util.List;

/**
 * Offline checks for the shipped reply texts.
 *
 * The headline check is the transparency one: every single shipped variant, across every category
 * and every tone, must name both IRIS and Suvra. That is decision 3 turned into something a test
 * can enforce, so a later edit cannot quietly produce a reply that reads as though Suvra wrote it
 * himself.
 *
 * The second is the urgent-during-quiet-hours redirect. Urgency must not pierce quiet hours, but
 * the sender must be told how to actually get through, so those variants have to mention calling.
 */
public class ReplyTemplatesTest {

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
        everyShippedVariantIdentifiesIrisAndSuvra();
        everyAnsweredCategoryHasVariants();
        severalVariantsPerSituation();
        everyAnswerableSituationHasAtLeastFive();
        everyModeHasFiveVariantsThatName();
        quietHoursOverrideTheCategory();
        urgentInQuietHoursRedirectsToACall();
        urgentInNormalHoursStillSuggestsCalling();
        acknowledgementsExistOnlyForSarcasm();
        silentCategoriesHaveNoText();
        placeholdersAreFilled();
        unknownPlaceholdersAreLeftVisible();
        rotationIsDeterministicAndInRange();
        identifiesIrisDetectsOmissions();
        System.out.println("Passed " + checks + " reply template checks");
    }

    /** Decision 3, enforced across the entire shipped set. */
    private static void everyShippedVariantIdentifiesIrisAndSuvra() {
        int inspected = 0;
        for (MessageCategory.Kind category : MessageCategory.Kind.values()) {
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                for (boolean quiet : new boolean[]{false, true}) {
                    List<String> variants = ReplyTemplates.variants(category, tone, quiet);
                    for (String variant : variants) {
                        inspected++;
                        checks++;
                        if (!ReplyTemplates.identifiesIris(variant)) {
                            throw new AssertionError("FAILED: a shipped variant does not name both "
                                    + "IRIS and " + ReplyTemplates.OWNER
                                    + "\n  category: " + category + "\n  tone: " + tone
                                    + "\n  quiet: " + quiet + "\n  text: " + variant);
                        }
                    }
                }
            }
        }
        check(inspected > 80, "a substantial set was actually inspected (" + inspected + ")");
    }

    /** Anything the tone layer will answer must have something to say. */
    private static void everyAnsweredCategoryHasVariants() {
        for (MessageCategory.Kind category : MessageCategory.Kind.values()) {
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                if (!ReplyTone.answers(category, tone)) continue;
                check(!ReplyTemplates.variants(category, tone, false).isEmpty(),
                        category + " in " + tone + " is answered, so it must have text");
                check(!ReplyTemplates.variants(category, tone, true).isEmpty(),
                        category + " in " + tone + " must also have quiet-hours text");
            }
        }
    }

    /** One wording repeated verbatim makes a contact conclude they are talking to a machine. */
    private static void severalVariantsPerSituation() {
        check(ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                        ReplyTone.Tone.PROFESSIONAL, false).size() >= 2,
                "greetings offer more than one wording");
        check(ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                        ReplyTone.Tone.PROFESSIONAL, true).size() >= 2,
                "quiet hours offer more than one wording");
        List<String> variants = ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                ReplyTone.Tone.PROFESSIONAL, false);
        check(!variants.get(0).equals(variants.get(1)), "the variants genuinely differ");
    }

    /**
     * The owner's requirement: at least five wordings for every situation IRIS actually answers.
     *
     * Sweeps every category, every tone and both quiet states. For each combination that the
     * policy would speak in, it asserts five or more variants AND that they are all distinct --
     * five copies of one sentence is not five variants. Combinations the policy never reaches
     * (a silent category/tone) are skipped, since wording nobody sends need not exist.
     */
    private static void everyAnswerableSituationHasAtLeastFive() {
        for (MessageCategory.Kind kind : MessageCategory.Kind.values()) {
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                for (boolean quiet : new boolean[]{false, true}) {
                    // In quiet hours the category is overridden by the quiet set (or quietUrgent),
                    // so the thing actually sent is whatever variants() returns; an empty list
                    // means the policy stays silent here and there is nothing to require.
                    List<String> v = ReplyTemplates.variants(kind, tone, quiet);
                    if (v.isEmpty()) continue;
                    // Only hold answerable combinations to the bar. OTHER is off by default but is
                    // real wording once enabled, so it is included; MEDIA_ONLY and a silent
                    // acknowledgement return empty above and are skipped.
                    check(v.size() >= 5, kind + "/" + tone + "/" + (quiet ? "quiet" : "normal")
                            + " has at least five variants (was " + v.size() + ")");
                    java.util.Set<String> distinct = new java.util.HashSet<>(v);
                    check(distinct.size() == v.size(),
                            kind + "/" + tone + "/" + (quiet ? "quiet" : "normal")
                                    + " variants are all distinct");
                }
            }
        }
    }

    /**
     * Every active mode has at least five distinct wordings per tone, each naming IRIS and Suvra,
     * and the sleeping mode alone redirects an urgent sender to a call.
     */
    private static void everyModeHasFiveVariantsThatName() {
        String ownerLower = ReplyTemplates.OWNER.toLowerCase(java.util.Locale.ROOT);
        for (ResponderMode mode : ResponderMode.values()) {
            if (!mode.isActive()) {
                check(ReplyTemplates.modeVariants(mode, MessageCategory.Kind.OTHER,
                                ReplyTone.Tone.PROFESSIONAL).isEmpty(),
                        "NONE has no mode wording");
                continue;
            }
            for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
                List<String> v = ReplyTemplates.modeVariants(
                        mode, MessageCategory.Kind.GREETING, tone);
                check(v.size() >= 5, mode + "/" + tone + " has five mode variants (was "
                        + v.size() + ")");
                check(new java.util.HashSet<>(v).size() == v.size(),
                        mode + "/" + tone + " mode variants are distinct");
                for (String s : v) {
                    String lower = s.toLowerCase(java.util.Locale.ROOT);
                    check(lower.contains("iris") && lower.contains(ownerLower),
                            mode + "/" + tone + " mode variant names both IRIS and the owner");
                }
                // Urgent handling: sleeping redirects to a call, the others answer plainly.
                List<String> urgent = ReplyTemplates.modeVariants(
                        mode, MessageCategory.Kind.URGENT, tone);
                if (mode == ResponderMode.SLEEPING) {
                    for (String s : urgent) {
                        String lower = s.toLowerCase(java.util.Locale.ROOT);
                        check(lower.contains("call") || lower.contains("ring"),
                                "sleeping mode redirects an urgent sender to a call, in " + tone);
                    }
                } else {
                    // The other modes do not tell a driver or a meeting-goer to take a call.
                    check(urgent.equals(ReplyTemplates.modeVariants(
                                    mode, MessageCategory.Kind.GREETING, tone)),
                            mode + " answers urgent the same as anything else, no call redirect");
                }
            }
        }
    }

    /** Quiet hours decide the content, whatever the message said. */
    private static void quietHoursOverrideTheCategory() {
        List<String> greetingQuiet = ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                ReplyTone.Tone.PROFESSIONAL, true);
        List<String> thanksQuiet = ReplyTemplates.variants(MessageCategory.Kind.THANKS,
                ReplyTone.Tone.PROFESSIONAL, true);
        equal(greetingQuiet, thanksQuiet,
                "in quiet hours an ordinary greeting and a thanks get the same sleeping reply");
        check(greetingQuiet.get(0).toLowerCase(java.util.Locale.ROOT).contains("asleep"),
                "the quiet-hours reply says he is asleep");
    }

    /**
     * Decision 13. Urgency does not pierce quiet hours; instead the sender is redirected to the
     * one channel that reaches a sleeping person.
     */
    private static void urgentInQuietHoursRedirectsToACall() {
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            List<String> variants = ReplyTemplates.variants(MessageCategory.Kind.URGENT,
                    tone, true);
            check(!variants.isEmpty(), "urgent in quiet hours has text in " + tone);
            for (String variant : variants) {
                String lower = variant.toLowerCase(java.util.Locale.ROOT);
                check(lower.contains("call") || lower.contains("ring"),
                        "urgent-in-quiet-hours tells the sender to call, in " + tone
                                + "\n  text: " + variant);
            }
        }

        // And it is genuinely different from the ordinary sleeping reply.
        List<String> urgentQuiet = ReplyTemplates.variants(MessageCategory.Kind.URGENT,
                ReplyTone.Tone.PROFESSIONAL, true);
        List<String> ordinaryQuiet = ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                ReplyTone.Tone.PROFESSIONAL, true);
        check(!urgentQuiet.equals(ordinaryQuiet),
                "urgent gets its own quiet-hours wording, not the generic one");
    }

    private static void urgentInNormalHoursStillSuggestsCalling() {
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            for (String variant : ReplyTemplates.variants(MessageCategory.Kind.URGENT,
                    tone, false)) {
                String lower = variant.toLowerCase(java.util.Locale.ROOT);
                check(lower.contains("call"),
                        "an urgent reply offers a faster channel, in " + tone);
            }
        }
    }

    /** Only sarcasm answers an acknowledgement, so only sarcasm needs the words. */
    private static void acknowledgementsExistOnlyForSarcasm() {
        check(!ReplyTemplates.variants(MessageCategory.Kind.ACKNOWLEDGEMENT,
                        ReplyTone.Tone.SARCASTIC, false).isEmpty(),
                "sarcastic acknowledgements have text");
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            boolean answered = ReplyTone.answers(MessageCategory.Kind.ACKNOWLEDGEMENT, tone);
            check(answered == (tone == ReplyTone.Tone.SARCASTIC),
                    "acknowledgements are answered only in sarcastic, checked for " + tone);
        }
    }

    private static void silentCategoriesHaveNoText() {
        check(ReplyTemplates.variants(MessageCategory.Kind.NONE,
                        ReplyTone.Tone.PROFESSIONAL, false).isEmpty(),
                "an unrecognised message has no reply text at all");
        check(ReplyTemplates.variants(MessageCategory.Kind.MEDIA_ONLY,
                        ReplyTone.Tone.PROFESSIONAL, false).isEmpty(),
                "media-only has no reply text, matching its off-by-default policy");
        check(ReplyTemplates.variants(null, ReplyTone.Tone.PROFESSIONAL, false).isEmpty(),
                "a null category has no text rather than throwing");
    }

    private static void placeholdersAreFilled() {
        String filled = ReplyTemplates.fill(
                "It is {time} and {owner} is asleep. About {hours_until_morning} hours. "
                        + "Good {part_of_day}, {name}.",
                "Bidisha", "23:30", "night", 7, "Monday");
        check(filled.contains("23:30"), "the clock is substituted");
        check(filled.contains("Suvra"), "the owner name is substituted");
        check(filled.contains("7"), "the hours are substituted");
        check(filled.contains("night"), "the part of day is substituted");
        check(filled.contains("Bidisha"), "the sender name is substituted");
        check(!filled.contains("{"), "no placeholder braces survive");

        // A missing hours value must read as language, not as a negative number.
        String vague = ReplyTemplates.fill("in {hours_until_morning} hours", "", "", "", -1, "");
        check(vague.contains("a few"), "an unknown wait reads as 'a few', never as -1");
        check(!vague.contains("-1"), "a negative duration never reaches a recipient");

        // An empty part of day still produces a readable sentence.
        String neutral = ReplyTemplates.fill("Good {part_of_day}.", "", "", "", -1, "");
        check(neutral.contains("day"), "an unknown part of day falls back to 'day'");

        equal(ReplyTemplates.fill(null, "a", "b", "c", 1, "d"), "",
                "a null template fills to empty rather than throwing");
    }

    /**
     * A mistyped placeholder must stay visible. Blanking it would hide the owner's typo and ship a
     * sentence with a hole in it.
     */
    private static void unknownPlaceholdersAreLeftVisible() {
        String out = ReplyTemplates.fill("Hello {naem}, this is IRIS.", "Bidisha", "10:00",
                "morning", -1, "Monday");
        check(out.contains("{naem}"), "an unrecognised placeholder is left as written");
    }

    private static void rotationIsDeterministicAndInRange() {
        List<String> variants = ReplyTemplates.variants(MessageCategory.Kind.GREETING,
                ReplyTone.Tone.PROFESSIONAL, false);
        equal(ReplyTemplates.choose(variants, 0), variants.get(0), "rotation 0 picks the first");
        equal(ReplyTemplates.choose(variants, 1), variants.get(1), "rotation 1 picks the second");
        equal(ReplyTemplates.choose(variants, variants.size()), variants.get(0),
                "rotation wraps around");
        equal(ReplyTemplates.choose(variants, 0), ReplyTemplates.choose(variants, 0),
                "the same rotation always gives the same text, so a dry run is reproducible");

        // Must never index out of bounds, whatever the counter has reached.
        for (int rotation : new int[]{-1, -7, 999, Integer.MAX_VALUE}) {
            String chosen = ReplyTemplates.choose(variants, rotation);
            check(chosen != null && !chosen.isEmpty(),
                    "rotation " + rotation + " still yields a variant");
        }
        equal(ReplyTemplates.choose(null, 0), "", "a null list yields empty");
        equal(ReplyTemplates.choose(new java.util.ArrayList<String>(), 0), "",
                "an empty list yields empty");
    }

    /** Powers the editor's non-blocking warning on owner-written text. */
    private static void identifiesIrisDetectsOmissions() {
        check(ReplyTemplates.identifiesIris("I'm IRIS, Suvra's personal intelligence."),
                "a compliant text is recognised");
        check(ReplyTemplates.identifiesIris("iris here, suvra is away"),
                "case does not matter");
        check(!ReplyTemplates.identifiesIris("Suvra is asleep right now."),
                "naming only the owner is not enough, since it reads as though he wrote it");
        check(!ReplyTemplates.identifiesIris("This is IRIS speaking."),
                "naming only IRIS is not enough");
        check(!ReplyTemplates.identifiesIris("Away from my phone, back later."),
                "a plausible human away-message is correctly flagged as non-identifying");
        check(!ReplyTemplates.identifiesIris(null), "null does not identify");
        check(!ReplyTemplates.identifiesIris(""), "empty does not identify");
    }
}
