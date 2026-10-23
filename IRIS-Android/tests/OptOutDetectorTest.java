package com.iris.assistant;

/**
 * Offline checks for OptOutDetector: English, Hindi, Odia, and Bengali opt-out detection.
 */
public final class OptOutDetectorTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // English
        check(OptOutDetector.isOptOut("stop"), "bare stop");
        check(OptOutDetector.isOptOut("Stop!"), "stop with punctuation");
        check(OptOutDetector.isOptOut("please stop"), "please stop");
        check(OptOutDetector.isOptOut("stop replying"), "stop replying");
        check(OptOutDetector.isOptOut("stop bot"), "stop bot");
        check(OptOutDetector.isOptOut("shut up"), "shut up");
        check(OptOutDetector.isOptOut("don't reply"), "don't reply");
        check(OptOutDetector.isOptOut("dont text me"), "dont text me");
        check(OptOutDetector.isOptOut("mute this chat"), "mute this chat");
        check(OptOutDetector.isOptOut("quiet bot"), "quiet bot");
        check(OptOutDetector.isOptOut("leave me alone"), "leave me alone");
        check(OptOutDetector.isOptOut("stfu"), "stfu");

        // Hindi
        check(OptOutDetector.isOptOut("chup"), "chup");
        check(OptOutDetector.isOptOut("chup kar"), "chup kar");
        check(OptOutDetector.isOptOut("chup karo"), "chup karo");
        check(OptOutDetector.isOptOut("chup raho"), "chup raho");
        check(OptOutDetector.isOptOut("reply mat karo"), "reply mat karo");
        check(OptOutDetector.isOptOut("message mat kar"), "message mat kar");
        check(OptOutDetector.isOptOut("band karo"), "band karo");
        check(OptOutDetector.isOptOut("shant raho"), "shant raho");
        check(OptOutDetector.isOptOut("bakwas band karo"), "bakwas band karo");

        // Odia
        check(OptOutDetector.isOptOut("chup ruha"), "chup ruha");
        check(OptOutDetector.isOptOut("katha huani"), "katha huani");
        check(OptOutDetector.isOptOut("reply karani"), "reply karani");
        check(OptOutDetector.isOptOut("band kara"), "band kara");
        check(OptOutDetector.isOptOut("au reply diani"), "au reply diani");
        check(OptOutDetector.isOptOut("shanta ruha"), "shanta ruha");

        // Bengali
        check(OptOutDetector.isOptOut("chup thak"), "chup thak");
        check(OptOutDetector.isOptOut("chup korun"), "chup korun");
        check(OptOutDetector.isOptOut("thamo"), "thamo");
        check(OptOutDetector.isOptOut("ar reply diyo na"), "ar reply diyo na");
        check(OptOutDetector.isOptOut("ar message dio na"), "ar message dio na");
        check(OptOutDetector.isOptOut("kotha bolo na"), "kotha bolo na");
        check(OptOutDetector.isOptOut("bondho koro"), "bondho koro");

        // False positives should NOT match
        check(!OptOutDetector.isOptOut("meet me at the bus stop"), "bus stop is not opt-out");
        check(!OptOutDetector.isOptOut("add a full stop"), "full stop is not opt-out");
        check(!OptOutDetector.isOptOut("non-stop flights"), "non-stop is not opt-out");
        check(!OptOutDetector.isOptOut("can't stop listening"), "can't stop is not opt-out");
        check(!OptOutDetector.isOptOut("hello there"), "greeting is not opt-out");
        check(!OptOutDetector.isOptOut("are you free today"), "question is not opt-out");

        // Confirmation text
        String reply = OptOutDetector.confirmationReply("Suvra");
        check(reply.contains("Suvra"), "confirmation names owner");
        check(reply.contains("muted"), "confirmation confirms muted");

        System.out.println("Passed " + checks + " OptOutDetector checks");
    }
}
