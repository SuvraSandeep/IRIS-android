package com.iris.assistant;

import java.util.Arrays;

/**
 * Offline checks for message classification.
 *
 * The weight of this file sits deliberately on what must NOT match. A missed category means IRIS
 * stays quiet, which is the safe default and costs nothing. A false match means IRIS sends a
 * stranger or a colleague a reply to a message it did not understand, from Suvra's account.
 *
 * Two groups of cases carry most of that risk:
 *
 * 1. **Shortforms.** ty, gm, gn, k are two letters or fewer and would be found inside ordinary
 *    words by any substring search.
 * 2. **Other languages.** Every Odia string below was captured from the owner's own phone by the
 *    PROBE diagnostic. They are real messages from a real contact and none of them is English, so
 *    none of them may be classified as a greeting, a thanks or an acknowledgement.
 */
public class MessageCategoryTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void kind(String text, MessageCategory.Kind expected, String what) {
        checks++;
        MessageCategory.Kind actual = MessageCategory.classify(text).kind;
        if (actual != expected) {
            throw new AssertionError("FAILED: " + what + "\n  text:     " + text
                    + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        realOdiaMessagesAreNeverEnglishCategories();
        shortformsAreMatchedWholeWordOnly();
        greetingsIncludingRepeatedLetters();
        timeGreetingsAndTheirShortforms();
        thanksIncludingShortforms();
        congratulationsAreRecognised();
        messagesAimedAtIrisAreRecognised();
        festivalsMatchByPhraseNotDate();
        birthdaysAndWeekends();
        urgentIsRecognised();
        checkInsFromRealTraffic();
        acknowledgementsAreRecognisedSoTheyCanBeIgnored();
        questionsFallThroughLast();
        mediaPlaceholders();
        mentionsOutrankEverything();
        shortMessagesAreTheLoosestCategory();
        unrecognisedMeansSilence();
        edgeInputsFailSafe();
        normalisationBehaviour();
        System.out.println("Passed " + checks + " message category checks");
    }

    /**
     * Captured verbatim from the owner's phone. "ki", "kii", "ka", "bhi", "asa", "mu" are all
     * tokens that a careless shortform match would claim.
     */
    private static void realOdiaMessagesAreNeverEnglishCategories() {
        String[] probeStrings = {
                "Mu bujhiparuni kichi bhi",
                "Bhala laguni",
                "Besi bhabuchu boliki amiti laguchi ki",
                "Jaha heba dekha jiba kii",
                "Mu mora faeda dekhibii",
                "Time nahi sahi chal raha shapoorjee ka"
        };
        for (String text : probeStrings) {
            MessageCategory.Kind actual = MessageCategory.classify(text).kind;
            checks++;
            boolean claimedEnglish = actual == MessageCategory.Kind.GREETING
                    || actual == MessageCategory.Kind.THANKS
                    || actual == MessageCategory.Kind.TIME_GREETING
                    || actual == MessageCategory.Kind.ACKNOWLEDGEMENT
                    || actual == MessageCategory.Kind.FESTIVAL;
            if (claimedEnglish) {
                throw new AssertionError("FAILED: real Odia message classified as an English "
                        + "category\n  text: " + text + "\n  got:  " + actual);
            }
        }
        // "Bhala laguni" is two words, so SHORT may legitimately claim it. That is acceptable:
        // SHORT produces a neutral "tell me more" reply rather than a wrong-language answer.
        //
        // A longer Odia message now lands in OTHER rather than NONE. The invariant that matters is
        // unchanged -- it must not be mistaken for an English category, and OTHER is off by
        // default so nothing is said about it unless the owner asks for that. Asserting NONE here
        // was pinning the implementation rather than the requirement.
        kind("Mu bujhiparuni kichi bhi", MessageCategory.Kind.OTHER,
                "a four-word Odia message is unremarkable, not a recognised category");
    }

    /** The owner asked for shortforms; these are the cases that make them safe. */
    private static void shortformsAreMatchedWholeWordOnly() {
        kind("ty", MessageCategory.Kind.THANKS, "ty alone is thanks");
        kind("gm", MessageCategory.Kind.TIME_GREETING, "gm alone is a morning greeting");
        kind("gn", MessageCategory.Kind.TIME_GREETING, "gn alone is a night greeting");

        // The substring traps. None of these contains a whole-word shortform.
        kind("amiti", MessageCategory.Kind.SHORT, "ty inside 'amiti' is not thanks");
        kind("stingy", MessageCategory.Kind.SHORT, "ty inside 'stingy' is not thanks");
        kind("gmail", MessageCategory.Kind.SHORT, "gm inside 'gmail' is not a greeting");
        kind("gnome", MessageCategory.Kind.SHORT, "gn inside 'gnome' is not a greeting");
        kind("kichi", MessageCategory.Kind.SHORT, "k inside 'kichi' is not an acknowledgement");
        kind("okra", MessageCategory.Kind.SHORT, "ok inside 'okra' is not an acknowledgement");
        check(MessageCategory.classify("please send the party photos by gmail tonight").kind
                        != MessageCategory.Kind.TIME_GREETING,
                "a long sentence containing gmail is not a morning greeting");
    }

    /** Congratulations, and the things that must not be mistaken for them. */
    private static void congratulationsAreRecognised() {
        kind("congrats", MessageCategory.Kind.CONGRATS, "congrats");
        kind("Congratulations!", MessageCategory.Kind.CONGRATS, "congratulations with punctuation");
        kind("congo", MessageCategory.Kind.CONGRATS, "the shortform congo");
        kind("badhai", MessageCategory.Kind.CONGRATS, "badhai");
        kind("mubarak", MessageCategory.Kind.CONGRATS, "mubarak");
        kind("well done", MessageCategory.Kind.CONGRATS, "well done");
        kind("so proud of you", MessageCategory.Kind.CONGRATS, "proud of you");
        kind("hearty congratulations on the promotion", MessageCategory.Kind.CONGRATS,
                "a full sentence of congratulation");

        // Congratulations outranks thanks when both appear, which is the common real form.
        kind("congratulations and thank you so much", MessageCategory.Kind.CONGRATS,
                "congratulations wins over thanks in the same message");

        // Must not match. "congress" and "mubarak" style substrings are the trap.
        check(MessageCategory.classify("the congress session is tomorrow").kind
                        != MessageCategory.Kind.CONGRATS,
                "congress is not congrats");
        check(MessageCategory.classify("i will be done in a minute").kind
                        != MessageCategory.Kind.CONGRATS,
                "'done' alone is not 'well done'");
        check(MessageCategory.classify("that went well").kind
                        != MessageCategory.Kind.CONGRATS,
                "'went well' is not 'well done'");
    }

    /**
     * Recognising that the sender is talking to IRIS, not to Suvra.
     *
     * The must-not-match half carries the weight. A first version of the phrase list included
     * "auto reply" and the owner's own Twilio message -- "Standard auto-reply. You can send
     * outbound messages..." -- matched it, which would have had IRIS introduce itself to anyone
     * who mentioned automation.
     */
    private static void messagesAimedAtIrisAreRecognised() {
        kind("iris", MessageCategory.Kind.TO_IRIS, "the name alone");
        kind("IRIS are you there", MessageCategory.Kind.TO_IRIS, "named, in any case");
        kind("is this a bot", MessageCategory.Kind.TO_IRIS, "asking whether it is a bot");
        kind("are you a robot?", MessageCategory.Kind.TO_IRIS, "asking whether it is a robot");
        kind("am i talking to a machine", MessageCategory.Kind.TO_IRIS, "asking what it is");
        kind("who am i talking to", MessageCategory.Kind.TO_IRIS, "asking who it is");

        // Being named beats a mention of Suvra, because the sender has worked out what they are
        // talking to and answering as though the message were for Suvra is plainly wrong.
        check(MessageCategory.classify("iris tell suvra to call me",
                        java.util.Arrays.asList("suvra", "stg")).kind
                        == MessageCategory.Kind.TO_IRIS,
                "being addressed by name outranks a mention of Suvra");

        // ─── Must NOT match ───
        check(MessageCategory.classify("Standard auto-reply. You can send outbound messages via "
                        + "the Tryout UI or API").kind != MessageCategory.Kind.TO_IRIS,
                "a message ABOUT auto-replies is not a message TO one");
        check(MessageCategory.classify("i set up an automated reply on my work mail").kind
                        != MessageCategory.Kind.TO_IRIS,
                "describing an automated reply is not addressing one");
        check(MessageCategory.classify("the assistant will send the file").kind
                        != MessageCategory.Kind.TO_IRIS,
                "mentioning an assistant is not addressing IRIS");
        check(MessageCategory.classify("i bought a robot vacuum").kind
                        != MessageCategory.Kind.TO_IRIS,
                "a robot vacuum is not a question about being a robot");
        check(MessageCategory.classify("irish coffee please").kind
                        != MessageCategory.Kind.TO_IRIS,
                "irish does not contain iris as a whole word");
        check(MessageCategory.classify("the iris flowers have opened").kind
                        == MessageCategory.Kind.TO_IRIS,
                "the flower is an accepted false positive; the name is too strong a signal to "
                        + "weaken for it");

        // ─── Someone replying after IRIS spoke, without using the name ───
        check(MessageCategory.classify("so when will he actually get back to me then",
                        null, true).kind == MessageCategory.Kind.TO_IRIS,
                "a follow-up while IRIS spoke last is aimed at IRIS");
        check(MessageCategory.classify("so when will he actually get back to me then",
                        null, false).kind == MessageCategory.Kind.OTHER,
                "the same message is unremarkable when IRIS did not speak last");
        // A specific category still wins. Who spoke last does not make a festival wish
        // a conversation with IRIS.
        check(MessageCategory.classify("happy diwali to you and family", null, true).kind
                        == MessageCategory.Kind.FESTIVAL,
                "a festival wish is still a festival wish after IRIS has spoken");
        check(MessageCategory.classify("thanks", null, true).kind
                        == MessageCategory.Kind.THANKS,
                "thanks is still thanks after IRIS has spoken");
        check(MessageCategory.classify("ok", null, true).kind
                        == MessageCategory.Kind.ACKNOWLEDGEMENT,
                "an acknowledgement is not promoted to a conversation with IRIS");
    }

    private static void greetingsIncludingRepeatedLetters() {
        kind("hi", MessageCategory.Kind.GREETING, "hi");
        kind("Hello", MessageCategory.Kind.GREETING, "case is ignored");
        kind("hii", MessageCategory.Kind.GREETING, "doubled letters");
        kind("heyy", MessageCategory.Kind.GREETING, "heyy");
        kind("hi!", MessageCategory.Kind.GREETING, "trailing punctuation");
        kind("namaste", MessageCategory.Kind.GREETING, "namaste");
        kind("yo", MessageCategory.Kind.GREETING, "yo");
        kind("hi there, are we still meeting at six", MessageCategory.Kind.GREETING,
                "a greeting inside a longer message still counts as a greeting");
    }

    private static void timeGreetingsAndTheirShortforms() {
        check(MessageCategory.classify("good morning").detail.equals("morning"),
                "good morning reports the part of day");
        check(MessageCategory.classify("gm").detail.equals("morning"), "gm reports morning");
        check(MessageCategory.classify("good night").detail.equals("night"),
                "good night reports night");
        check(MessageCategory.classify("gn").detail.equals("night"), "gn reports night");
        check(MessageCategory.classify("gn8").detail.equals("night"), "gn8 reports night");
        check(MessageCategory.classify("good evening").detail.equals("evening"),
                "good evening reports evening");
        check(MessageCategory.classify("good afternoon").detail.equals("afternoon"),
                "good afternoon reports afternoon");
        check(MessageCategory.classify("gud mrng").detail.equals("morning"),
                "the common gud mrng spelling is recognised");
        kind("goodnight", MessageCategory.Kind.TIME_GREETING, "goodnight as one word");
    }

    private static void thanksIncludingShortforms() {
        kind("thanks", MessageCategory.Kind.THANKS, "thanks");
        kind("thank you", MessageCategory.Kind.THANKS, "thank you as a phrase");
        kind("thx", MessageCategory.Kind.THANKS, "thx");
        kind("tysm", MessageCategory.Kind.THANKS, "tysm");
        kind("tq", MessageCategory.Kind.THANKS, "tq");
        kind("dhanyabad", MessageCategory.Kind.THANKS, "dhanyabad");
    }

    /**
     * Phrase, never date. Diwali moves every year, so a date table would be silently wrong the
     * first year it was not updated.
     */
    private static void festivalsMatchByPhraseNotDate() {
        check(MessageCategory.classify("happy diwali").detail.equals("happy diwali"),
                "diwali reports which festival");
        kind("Happy Diwali to you and family", MessageCategory.Kind.FESTIVAL,
                "a festival wish inside a longer sentence");
        kind("eid mubarak", MessageCategory.Kind.FESTIVAL, "eid mubarak");
        kind("subho bijoya", MessageCategory.Kind.FESTIVAL, "subho bijoya");
        kind("merry christmas", MessageCategory.Kind.FESTIVAL, "merry christmas");
        kind("hny", MessageCategory.Kind.FESTIVAL, "hny shortform");
        kind("happy makar sankranti", MessageCategory.Kind.FESTIVAL, "makar sankranti");

        // Must not be found inside a longer word.
        check(MessageCategory.classify("we booked a happy holiday package").kind
                        != MessageCategory.Kind.FESTIVAL,
                "'happy holi' is not found inside 'happy holiday'");
    }

    private static void birthdaysAndWeekends() {
        kind("happy birthday", MessageCategory.Kind.BIRTHDAY, "happy birthday");
        kind("hbd", MessageCategory.Kind.BIRTHDAY, "hbd shortform");
        kind("happy weekend", MessageCategory.Kind.WEEKEND, "happy weekend");
        kind("happy friday", MessageCategory.Kind.WEEKEND, "happy friday");
    }

    private static void urgentIsRecognised() {
        kind("this is urgent", MessageCategory.Kind.URGENT, "urgent");
        kind("call me", MessageCategory.Kind.URGENT, "call me");
        kind("need this asap", MessageCategory.Kind.URGENT, "asap");
        kind("emergency", MessageCategory.Kind.URGENT, "emergency");
        // Urgent outranks a greeting, since the pressing part matters more than the pleasantry.
        kind("hi, urgent", MessageCategory.Kind.URGENT, "urgent outranks a greeting");
    }

    /** "Aeee" and "Online asa" were both captured from the owner's phone. */
    private static void checkInsFromRealTraffic() {
        kind("Online asa", MessageCategory.Kind.CHECK_IN,
                "the probe's real 'Online asa' reads as a check-in, which is what it means");
        kind("are you there", MessageCategory.Kind.CHECK_IN, "are you there");
        kind("you up", MessageCategory.Kind.CHECK_IN, "you up");
        kind("wyd", MessageCategory.Kind.CHECK_IN, "wyd shortform");

        // The length bound keeps a narrative mention of being online out.
        check(MessageCategory.classify(
                        "i was online yesterday evening when the whole thing happened").kind
                        != MessageCategory.Kind.CHECK_IN,
                "a long sentence mentioning 'online' is not a check-in");
    }

    /**
     * These exist so they can be deliberately ignored. Without the category, "ok" would fall to
     * SHORT and earn a full "Suvra is asleep" reply, which is pure noise.
     */
    private static void acknowledgementsAreRecognisedSoTheyCanBeIgnored() {
        kind("ok", MessageCategory.Kind.ACKNOWLEDGEMENT, "ok");
        kind("okay", MessageCategory.Kind.ACKNOWLEDGEMENT, "okay");
        kind("k", MessageCategory.Kind.ACKNOWLEDGEMENT, "k");
        kind("hmm", MessageCategory.Kind.ACKNOWLEDGEMENT, "hmm");
        kind("noted", MessageCategory.Kind.ACKNOWLEDGEMENT, "noted");
        kind("theek", MessageCategory.Kind.ACKNOWLEDGEMENT, "theek");
    }

    private static void questionsFallThroughLast() {
        kind("will the report be ready by friday?", MessageCategory.Kind.QUESTION,
                "a real question");
        // More specific categories win even when phrased as a question.
        kind("are you there?", MessageCategory.Kind.CHECK_IN, "a check-in question is a check-in");
        kind("hi?", MessageCategory.Kind.GREETING, "a greeting with a question mark is a greeting");
        check(MessageCategory.classify("the report is ready").kind
                        != MessageCategory.Kind.QUESTION,
                "a statement is not a question");
    }

    private static void mediaPlaceholders() {
        kind("\uD83D\uDCF7 Sent a photo", MessageCategory.Kind.MEDIA_ONLY,
                "the probe's real photo placeholder, emoji and all");
        kind("Sent a sticker", MessageCategory.Kind.MEDIA_ONLY, "sticker placeholder");
        kind("Sent a video", MessageCategory.Kind.MEDIA_ONLY, "video placeholder");
        check(MessageCategory.classify(
                        "i sent a photo of the whiteboard to the group yesterday").kind
                        != MessageCategory.Kind.MEDIA_ONLY,
                "a sentence about sending a photo is not a placeholder");
    }

    private static void mentionsOutrankEverything() {
        java.util.List<String> keys = Arrays.asList("suvra", "stg");
        checks++;
        if (MessageCategory.classify("can suvra confirm this", keys).kind
                != MessageCategory.Kind.MENTION) {
            throw new AssertionError("FAILED: being named is a mention");
        }
        checks++;
        if (MessageCategory.classify("STG please review", keys).kind
                != MessageCategory.Kind.MENTION) {
            throw new AssertionError("FAILED: STG is a mention, case-insensitively");
        }
        // Outranks even urgent, because being named directly is the one case where silence is
        // conspicuous.
        checks++;
        if (MessageCategory.classify("urgent, suvra needed", keys).kind
                != MessageCategory.Kind.MENTION) {
            throw new AssertionError("FAILED: a mention outranks urgent");
        }
        // Whole-word only: a keyword must not be found inside another word.
        checks++;
        if (MessageCategory.classify("the stgx module failed", keys).kind
                == MessageCategory.Kind.MENTION) {
            throw new AssertionError("FAILED: 'stg' inside 'stgx' is not a mention");
        }
        checks++;
        if (MessageCategory.classify("suvra", null).kind == MessageCategory.Kind.MENTION) {
            throw new AssertionError("FAILED: no keywords configured means no mentions");
        }
    }

    private static void shortMessagesAreTheLoosestCategory() {
        kind("Aeee", MessageCategory.Kind.SHORT,
                "the probe's real one-word poke falls to SHORT");
        kind("oi", MessageCategory.Kind.SHORT, "a two-letter nudge");
        kind("two words", MessageCategory.Kind.SHORT, "two words is still SHORT");
        kind("now three words here", MessageCategory.Kind.OTHER,
                "four words is beyond SHORT, so it is unremarkable rather than short");
    }

    /**
     * An ordinary sentence is classified as unremarkable, and still produces silence.
     *
     * These used to expect NONE. They now expect OTHER, because the owner asked to be able to
     * acknowledge ordinary messages and that needs a category to hang the choice on. The
     * requirement is unchanged and is asserted directly below: by default nothing is said, in any
     * tone. OTHER is where an opt-in lives, not a change of default.
     */
    private static void unrecognisedMeansSilence() {
        kind("the quarterly figures came in lower than the forecast we agreed",
                MessageCategory.Kind.OTHER, "an ordinary sentence is unremarkable");
        kind("i will be at the office by half past nine tomorrow",
                MessageCategory.Kind.OTHER, "a plain statement is unremarkable");
        // The real message from the owner's Twilio test, which produced no reply and sent him
        // looking for a bug. It is now a category he can switch on.
        kind("Standard auto-reply. You can send outbound messages via the Tryout UI or API, or "
                        + "customize the reply by configuring a custom webhook in the Tryout UI",
                MessageCategory.Kind.OTHER, "the Twilio test message is unremarkable, not nothing");
        // Whether anything is SAID about an unremarkable message is a tone-policy question and is
        // asserted in ReplyToneTest. This suite deliberately compiles without ReplyTone, so that
        // the classifier can never acquire a dependency on policy.
    }

    private static void edgeInputsFailSafe() {
        kind(null, MessageCategory.Kind.NONE, "null is silence");
        kind("", MessageCategory.Kind.NONE, "empty is silence");
        kind("   ", MessageCategory.Kind.NONE, "whitespace is silence");
        kind("!!!", MessageCategory.Kind.NONE, "punctuation only is silence");
        kind("\uD83D\uDE00", MessageCategory.Kind.NONE, "an emoji alone is silence");
        check(MessageCategory.classify("hi").detail.isEmpty(),
                "a category with no detail reports empty, not null");
    }

    private static void normalisationBehaviour() {
        check(MessageCategory.normalise("Hi!  There").equals("hi there"),
                "punctuation becomes a separator and runs of space collapse");
        check(MessageCategory.normalise("\uD83D\uDCF7 Sent a photo").equals("sent a photo"),
                "emoji are stripped to a separator");
        check(MessageCategory.normalise(null).isEmpty(), "null normalises to empty");
        check(MessageCategory.normalise("  padded  ").equals("padded"), "outer space is trimmed");
    }
}
