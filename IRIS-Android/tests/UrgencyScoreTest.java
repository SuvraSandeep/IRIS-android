package com.iris.assistant;

/** Offline checks for the implicit-urgency scorer. */
public final class UrgencyScoreTest {
    private static int checks;
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Distress word alone clears the bar.
        t(UrgencyScore.isUrgent("there's been an accident please call", 1), "distress word is urgent");
        t(UrgencyScore.isUrgent("i'm at the hospital", 1), "hospital is urgent");
        t(UrgencyScore.isUrgent("i'm locked out and stranded", 1), "locked out/stranded urgent");

        // A frantic burst (4+ in window) + shouting crosses the bar.
        t(UrgencyScore.isUrgent("WHERE ARE YOU", 4), "burst + shouting is urgent");

        // Mild signals alone do NOT cross (teenagers type like this at rest).
        t(!UrgencyScore.isUrgent("heyyy where are you", 1), "casual lowercase is not urgent");
        t(!UrgencyScore.isUrgent("OK", 1), "short caps is not urgent");
        t(!UrgencyScore.isUrgent("lol that's so funny!!!", 1), "panic punctuation alone not urgent");
        t(!UrgencyScore.isUrgent("SOUNDS GOOD", 1), "two-word shout alone not urgent (35<60)");

        // Shouting + panic punctuation together cross the bar.
        t(UrgencyScore.score("PICK UP THE PHONE!!!", 1) >= UrgencyScore.THRESHOLD,
                "shout + panic punctuation is urgent");

        // Null-safe.
        t(UrgencyScore.score(null, 10) == 0, "null text scores 0");

        // Soft distress + laughing emojis = hyperbole, NOT an emergency (the false-RING bug).
        t(!UrgencyScore.isUrgent("for a second the username scared me \uD83D\uDE02\uD83D\uDE02", 1),
                "scared + laugh emoji is not urgent");
        t(!UrgencyScore.isUrgent("that scared me lol", 1), "scared + lol is not urgent");
        t(!UrgencyScore.isUrgent("i'm stuck haha", 1), "stuck + haha is not urgent");
        // Soft distress WITHOUT laugh context still clears the bar.
        t(UrgencyScore.isUrgent("something scared me badly", 1), "scared alone still urgent");
        // Hard emergencies fire even next to a laughing emoji.
        t(UrgencyScore.isUrgent("accident on the highway \uD83D\uDE02", 1),
                "hard emergency fires despite laugh emoji");
        t(UrgencyScore.hasLaughContext("scared me \uD83D\uDE02"), "laugh context detected");
        t(!UrgencyScore.hasLaughContext("there's been an accident"), "no false laugh context");

        System.out.println("Passed " + checks + " urgency score checks");
    }
}
