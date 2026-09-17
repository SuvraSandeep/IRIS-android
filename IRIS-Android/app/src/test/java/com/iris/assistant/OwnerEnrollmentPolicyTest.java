package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerEnrollmentPolicyTest {
    private short[] speech(){
        short[] pcm=new short[16000*4];
        for(int i=8000;i<48000;i++)pcm[i]=(short)(3000*Math.sin(i*.13));
        return pcm;
    }
    private float[] voice(){float[] v=new float[WakePolicy.EMBED_DIM];v[0]=1;return v;}
    @Test public void sentencesCollectIdentityButDoNotBypassFinalPhraseChecks(){
        assertTrue(OwnerEnrollmentPolicy.identityTake(false,0));
        assertTrue(OwnerEnrollmentPolicy.identityTake(false,OwnerTrainingPlan.ENROLLMENT-1));
        assertFalse(OwnerEnrollmentPolicy.identityTake(true,0));
        assertFalse(OwnerEnrollmentPolicy.identityTake(false,OwnerTrainingPlan.ENROLLMENT));
        assertFalse(OwnerEnrollmentPolicy.identityTake(false,-1));
        assertEquals("",OwnerEnrollmentPolicy.rejectIdentity(speech(),voice()));
        assertFalse(PhraseEvidence.complete("Hello Iris","let alone find it is"));
        assertFalse(PhraseEvidence.complete("Good Good","good"));
    }
    @Test public void insufficientAudioOrInvalidEncoderOutputNeverAddsIdentity(){
        assertFalse(OwnerEnrollmentPolicy.rejectIdentity(new short[64000],voice()).isEmpty());
        assertFalse(OwnerEnrollmentPolicy.rejectIdentity(speech(),null).isEmpty());
        float[] invalid=voice();invalid[0]=Float.NaN;
        assertFalse(OwnerEnrollmentPolicy.rejectIdentity(speech(),invalid).isEmpty());
        short[] clipped=new short[64000];java.util.Arrays.fill(clipped,Short.MAX_VALUE);
        assertFalse(TrainingAudioQuality.measure(clipped).enrollmentUsable());
        assertFalse(TrainingAudioQuality.measure(null).enrollmentUsable());
    }
    @Test public void shortTwoWordWakePhraseIsNotRejectedForBeingBrief(){
        // Real on-device bug this pins: enrollmentUsable() used to require 1.2s of voiced
        // signal (60 frames), copied from an earlier full-sentence enrollment design and never
        // re-validated for a short wake phrase. A genuine, clearly-spoken two-word phrase like
        // "Hello Iris" typically produces well under a second of real voiced signal once
        // leading/trailing pause is excluded -- this rejected every take outright ("Not enough
        // usable sound") even for perfectly fine recordings. Model ~0.6s of real speech (30
        // frames @ 20ms) inside a longer capture with silence before/after, matching how the
        // recorder actually captures a take.
        short[] pcm=new short[16000*3]; // 3s capture, matching a real TimedRecorder take
        int speechStart=16000, speechSamples=9600; // 0.6s of speech starting at the 1s mark
        for(int i=speechStart;i<speechStart+speechSamples;i++)pcm[i]=(short)(3000*Math.sin(i*.13));
        TrainingAudioQuality quality=TrainingAudioQuality.measure(pcm);
        assertTrue("a genuine ~0.6s short phrase must be usable, not rejected as too brief",quality.enrollmentUsable());
    }
}
