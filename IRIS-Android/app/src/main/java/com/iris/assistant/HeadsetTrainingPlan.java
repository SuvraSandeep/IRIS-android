package com.iris.assistant;

/** Adds a second route to an already-verified phone-route identity, so it does not need to
 *  re-establish "who the owner is" from scratch — only that this route's audio reliably matches
 *  the same owner via the same dual-embedding ensemble check. Redesigned per
 *  WAKE-TRAINING-REDESIGN.md alongside OwnerTrainingPlan: flat 4-enrollment + 2-verification
 *  sequence, no volume-based groups, no sound-pattern examples (SoundWakeProfile deleted). */
final class HeadsetTrainingPlan {
    static final int ENROLLMENT = 4;
    static final int VERIFY = 2;
    static final int TOTAL = ENROLLMENT + VERIFY;
    static boolean verification(int index) { return index >= ENROLLMENT; }
    /** Same overall-fraction fix as OwnerTrainingPlan.label() — see that method's doc for the
     *  real UX bug this addresses (a phase-local fraction next to an overall-fraction counter
     *  on the same screen read as an inconsistency, even though both were individually
     *  correct). */
    static String label(int index) {
        if (index >= TOTAL) return "All " + TOTAL + " headset takes verified";
        String phase = index < ENROLLMENT ? "Headset voice sample" : "Headset verification";
        return phase + " " + (index + 1) + " of " + TOTAL;
    }
}
