package com.iris.assistant;

/** Offline checks for the lexicon sentiment/urgency scorer. */
public final class SentimentScoreTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Distress / anger -> should soften (no sarcasm).
        check(SentimentScore.score("i need help right now").shouldSoften(), "need help now");
        check(SentimentScore.score("there has been an accident").distressed(), "accident");
        check(SentimentScore.score("i am so scared please").distressed(), "so scared");
        check(SentimentScore.score("he is in the hospital").distressed(), "hospital");
        check(SentimentScore.score("this is unacceptable and ridiculous").shouldSoften(),
                "angry message softens");

        // Urgency cues without the word "urgent".
        check(SentimentScore.score("call me right now").urgency >= 0.4, "right now is urgent cue");
        check(SentimentScore.score("come quick please").urgency >= 0.4, "come quick is urgent cue");

        // Ordinary / positive / banter -> NOT distress, NOT soften.
        check(!SentimentScore.score("haha i am dying laughing").distressed(),
                "dying laughing is banter, not distress");
        check(!SentimentScore.score("great news, thank you so much").shouldSoften(),
                "positive message does not soften");
        check(!SentimentScore.score("see you at 5 near the cafe").shouldSoften(),
                "neutral message does not soften");
        check(!SentimentScore.score("good morning").shouldSoften(), "greeting does not soften");

        // Negation flips a positive.
        check(SentimentScore.score("i am not good at all").valence < 0,
                "'not good' is negative");

        // Empty / null safe.
        check(!SentimentScore.score("").shouldSoften(), "empty is neutral");
        check(!SentimentScore.score(null).shouldSoften(), "null is neutral");

        System.out.println("Passed " + checks + " sentiment checks");
    }
}
