package com.iris.assistant;

/** Enrollment and held-out verification are distinct; verification never trains a centroid.
 *
 *  Redesigned per WAKE-TRAINING-REDESIGN.md: the old 5-normal + 5-quiet + 2-verify-normal +
 *  2-verify-quiet = 14-step plan (plus up to 2 hidden "spare" recovery slots) existed only to
 *  give the now-deleted DTW sound-pattern matcher separate normal/quiet-volume calibration
 *  groups. With identity now decided purely by speaker embeddings (dedicated ECAPA-TDNN +
 *  Vosk's x-vector, ensemble-scored — see WakePolicy.finalScore()), which are far less volume-
 *  sensitive than a raw energy-pattern matcher, there is no reason to ask for separate normal-
 *  and quiet-voice takes at training time: quiet/soft wake is handled by the existing runtime
 *  sensitivity setting (WakePolicy.threshold()) instead. This drops the plan to a flat
 *  4-enrollment + 2-verification = 6-step sequence, with NO volume-based groups and NO spare-
 *  take/overflow recovery machinery — a calibration failure simply retries the whole 4-take
 *  enrollment batch (see MainActivity's enrollment-boundary block). */
final class OwnerTrainingPlan {
    static final int ENROLLMENT = 4;
    static final int VERIFY = 2;
    static final int TOTAL = ENROLLMENT + VERIFY;
    /** True once past the 4 enrollment takes — a verification take never touches either
     *  centroid (WakePolicy.enrollment() is only ever run over the first ENROLLMENT takes). */
    static boolean verification(int index) { return index >= ENROLLMENT; }
    /** Real UX bug fixed here: this used to show a PHASE-LOCAL fraction ("Voice sample 1 of
     *  4" during enrollment, "Verification 1 of 2" during verification) while the on-screen
     *  step-dots/counter next to it always shows the OVERALL fraction out of TOTAL (6) — e.g.
     *  "Voice sample 1 of 4" next to "0 / 6" on the very same screen, both individually
     *  correct but reading as an outright inconsistency/bug to anyone looking at the two
     *  numbers together (confirmed via screenshot). Now shows the overall take number
     *  everywhere, with the phase name kept as a plain label rather than a second fraction. */
    static String label(int index) {
        if (index >= TOTAL) return "All " + TOTAL + " takes verified";
        String phase = index < ENROLLMENT ? "Voice sample" : "Verification";
        return phase + " " + (index + 1) + " of " + TOTAL;
    }
}
