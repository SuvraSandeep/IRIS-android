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
    static String label(int index) {
        if (index >= TOTAL) return "All " + TOTAL + " headset takes verified";
        if (index < ENROLLMENT) return "Headset voice sample " + (index + 1) + " of " + ENROLLMENT;
        return "Headset verification " + (index - ENROLLMENT + 1) + " of " + VERIFY;
    }
}
