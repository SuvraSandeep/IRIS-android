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
     *  based on index, mirroring OwnerTrainingPlan.verification(index). The Vosk vector must
     *  always be valid or this throws — Vosk's speaker model is bundled and always available,
     *  so a null/invalid Vosk vector really does mean something went wrong with this specific
     *  take. The ECAPA-TDNN vector may legitimately be ABSENT (null, or WakePolicy.isAbsent())
     *  — e.g. the dedicated model hasn't been hosted/loaded yet — and is normalized to a
     *  zero-length sentinel rather than rejected outright; WakePolicy.finalScore()'s ensemble
     *  scoring and WakePolicy.enrollment()'s centroid builder both already treat an absent
     *  ECAPA signal as "use Vosk alone", so storage must tolerate it too, all the way through
     *  to OwnerVoiceProfile's schema (see WakePolicy.isAbsent()'s doc for the full history of
     *  why this matters: without this, training was completely unusable while the ECAPA model
     *  is unavailable, even though the ensemble design always intended graceful degrade). A
     *  caller with a genuinely CORRUPT (wrong-length/non-finite) ECAPA vector should still not
     *  silently record a partial take — only a clean absent/null vector is tolerated. */
    void add(int index, float[] ecapaVector, float[] voskVector) {
        if (!WakePolicy.owner(voskVector, voskVector, .99))
            throw new IllegalArgumentException("Invalid Vosk speaker evidence");
        if (ecapaVector != null && ecapaVector.length != 0
                && !WakePolicy.ownerDim(ecapaVector, ecapaVector, .99, WakePolicy.ECAPA_EMBED_DIM))
            throw new IllegalArgumentException("Invalid ECAPA-TDNN speaker evidence");
        float[] ecapaStored = WakePolicy.isAbsent(ecapaVector) ? new float[0] : ecapaVector;
        boolean verify = OwnerTrainingPlan.verification(index);
        List<float[]> ecapaTarget = verify ? ecapaValidation : ecapaSamples;
        List<float[]> voskTarget = verify ? voskValidation : voskSamples;
        // Reject near-identical replayed data within each phase; the other phase stays
        // independent (a verification take being similar to an enrollment take is expected and
        // correct — that's the whole point of verification). Two absent-ECAPA takes are NOT
        // duplicates of each other (Arrays.equals on two zero-length arrays is trivially true,
        // but they carry no signal at all) — only compare when ECAPA is actually present.
        if (ecapaStored.length > 0)
            for (float[] previous : ecapaTarget) if (Arrays.equals(previous, ecapaStored)) throw new IllegalArgumentException("Duplicate take; record a new sample");
        ecapaTarget.add(ecapaStored.clone());
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
