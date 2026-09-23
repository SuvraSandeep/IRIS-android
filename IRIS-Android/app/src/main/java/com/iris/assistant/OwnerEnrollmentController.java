package com.iris.assistant;

import java.util.*;

/** Paired acoustic and speaker evidence. Held-out recordings never enter enrollment. */
final class OwnerEnrollmentController {
    final List<float[]> ecapaSamples = new ArrayList<>();
    final List<float[]> voskSamples = new ArrayList<>();
    final List<float[]> ecapaValidation = new ArrayList<>();
    final List<float[]> voskValidation = new ArrayList<>();
    final List<float[][]> phraseSamples=new ArrayList<>(), phraseValidation=new ArrayList<>();
    void clear() {
        phraseSamples.clear();phraseValidation.clear();
        ecapaSamples.clear(); voskSamples.clear();
        ecapaValidation.clear(); voskValidation.clear();
    }
    /** Adds one take's pair of embeddings to the correct phase (enrollment vs. verification).
     *  Real bug fixed here: this used to infer the phase internally via
     *  OwnerTrainingPlan.verification(index), silently hardcoding ONE specific training plan
     *  even though this same controller instance is also reused for headset-route training
     *  (see MainActivity's headsetEnrollment field), which has its own HeadsetTrainingPlan.
     *  It only ever routed correctly for the headset route by coincidence, because both plans
     *  currently define identical ENROLLMENT/VERIFY constants — a landmine that would silently
     *  misroute every headset take the moment either plan's constants diverged. The caller now
     *  passes the phase explicitly (already computed as `identityTake`/`verify` in
     *  MainActivity's per-route capture methods via that route's own plan class), so this
     *  class has no implicit dependency on which plan is in use.
     *
     *  The Vosk vector must always be valid or this throws — Vosk's speaker model is bundled
     *  and always available, so a null/invalid Vosk vector really does mean something went
     *  wrong with this specific take. The ECAPA-TDNN vector may legitimately be ABSENT (null,
     *  or WakePolicy.isAbsent()) — e.g. the dedicated model hasn't been hosted/loaded yet —
     *  and is normalized to a zero-length sentinel rather than rejected outright;
     *  WakePolicy.finalScore()'s ensemble scoring and WakePolicy.enrollment()'s centroid
     *  builder both already treat an absent ECAPA signal as "use Vosk alone", so storage must
     *  tolerate it too, all the way through to OwnerVoiceProfile's schema (see
     *  WakePolicy.isAbsent()'s doc for the full history of why this matters: without this,
     *  training was completely unusable while the ECAPA model is unavailable, even though the
     *  ensemble design always intended graceful degrade). A caller with a genuinely CORRUPT
     *  (wrong-length/non-finite) ECAPA vector should still not silently record a partial take
     *  — only a clean absent/null vector is tolerated. */
    void add(int index, float[] ecapaVector, float[] voskVector, boolean verify) {
        if (!WakePolicy.owner(voskVector, voskVector, .99))
            throw new IllegalArgumentException("Invalid Vosk speaker evidence");
        if (ecapaVector != null && ecapaVector.length != 0
                && !WakePolicy.ownerDim(ecapaVector, ecapaVector, .99, WakePolicy.ECAPA_EMBED_DIM))
            throw new IllegalArgumentException("Invalid ECAPA-TDNN speaker evidence");
        float[] ecapaStored = WakePolicy.isAbsent(ecapaVector) ? new float[0] : ecapaVector;
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
    void removeLastEnrollment(){removeEnrollmentAt(voskSamples.size()-1);}
    void removeEnrollmentAt(int index){
        if(index>=0){voskSamples.remove(index);ecapaSamples.remove(index);phraseSamples.remove(index);}
    }
    void removeLastVerification() {
        if(!phraseValidation.isEmpty())phraseValidation.remove(phraseValidation.size()-1);
        if (!ecapaValidation.isEmpty()) ecapaValidation.remove(ecapaValidation.size() - 1);
        if (!voskValidation.isEmpty()) voskValidation.remove(voskValidation.size() - 1);
    }
    OwnerVoiceProfile build(String phrase, String hash, double threshold) throws Exception {
        return OwnerVoiceProfile.create(phrase, hash, ecapaSamples, voskSamples, ecapaValidation, voskValidation, threshold, RecordedPhrase.create(phraseSamples,phraseValidation));
    }
}
