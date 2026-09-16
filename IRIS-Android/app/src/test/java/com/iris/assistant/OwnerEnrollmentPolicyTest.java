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
        assertTrue(OwnerEnrollmentPolicy.identityTake(false,9));
        assertFalse(OwnerEnrollmentPolicy.identityTake(true,0));
        assertFalse(OwnerEnrollmentPolicy.identityTake(false,10));
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
}
