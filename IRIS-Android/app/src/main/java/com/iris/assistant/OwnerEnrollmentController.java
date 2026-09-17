package com.iris.assistant;

import java.util.*;

/** Candidate samples are separate from active identity and from held-out validation.
 *
 *  Redesigned per WAKE-TRAINING-REDESIGN.md: holds TWO parallel embedding lists per phase
 *  (ecapaSamples/voskSamples for enrollment, ecapaValidation/voskValidation for held-out
 *  verification) instead of the old normal/soft volume-group split. Every take contributes to
 *  BOTH lists at the same index (the caller extracts both embeddings from the same trimmed
 *  clip in one pipeline step — see MainActivity's per-take pipeline), so ecapaSamples.get(i)
 *  and voskSamples.get(i) always correspond to the same recording. There is no addSpare() and
 *  no overflow/group-routing logic: a calibration failure simply retries the whole 4-take
 *  enrollment batch (add() is called again from index 0), which is why this class no longer
 *  needs index-based group inference at all. */
final class OwnerEnrollmentController {
    final List<float[]> ecapaSamples = new ArrayList<>();
    final List<float[]> voskSamples = new ArrayList<>();
    final List<float[]> ecapaValidation = new ArrayList<>();
    final List<float[]> voskValidation = new ArrayList<>();
    void clear() {
        ecapaSamples.clear(); voskSamples.clear();
        ecapaValidation.clear(); voskValidation.clear();
    }
    /** Adds one take's pair of embeddings to the correct phase (enrollment vs. verification)
     *  based on index, mirroring OwnerTrainingPlan.verification(index). Both vectors must be
     *  valid in their own embedding space or this throws — a caller with a null/invalid
     *  embedding (e.g. one model failed to load) should not silently record a partial take. */
    void add(int index, float[] ecapaVector, float[] voskVector) {
        if (!WakePolicy.ownerDim(ecapaVector, ecapaVector, .99, WakePolicy.ECAPA_EMBED_DIM))
            throw new IllegalArgumentException("Invalid ECAPA-TDNN speaker evidence");
        if (!WakePolicy.owner(voskVector, voskVector, .99))
            throw new IllegalArgumentException("Invalid Vosk speaker evidence");
        boolean verify = OwnerTrainingPlan.verification(index);
        List<float[]> ecapaTarget = verify ? ecapaValidation : ecapaSamples;
        List<float[]> voskTarget = verify ? voskValidation : voskSamples;
        // Reject near-identical replayed data within each phase; the other phase stays
        // independent (a verification take being similar to an enrollment take is expected and
        // correct — that's the whole point of verification).
        for (float[] previous : ecapaTarget) if (Arrays.equals(previous, ecapaVector)) throw new IllegalArgumentException("Duplicate take; record a new sample");
        ecapaTarget.add(ecapaVector.clone());
        voskTarget.add(voskVector.clone());
    }
    /** Removes the most recently added take from BOTH lists for the given phase — used when a
     *  single verification take fails and must be retried (see MainActivity), since a
     *  verification take is disposable, unlike an enrollment take which is only ever discarded
     *  as part of retrying the WHOLE 4-take batch (clear() + start over), never individually. */
    void removeLastVerification() {
        if (!ecapaValidation.isEmpty()) ecapaValidation.remove(ecapaValidation.size() - 1);
        if (!voskValidation.isEmpty()) voskValidation.remove(voskValidation.size() - 1);
    }
    OwnerVoiceProfile build(String phrase, String hash, double threshold) throws Exception {
        return OwnerVoiceProfile.create(phrase, hash, ecapaSamples, voskSamples, ecapaValidation, voskValidation, threshold);
    }
}
