package com.iris.assistant;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class VoiceEnduranceTest {
 @After public void reset(){VoiceEndurance.start(false);}
 @Test public void observationsCannotSilentlyLabelFirstCallSuccess(){VoiceEndurance.start(false);VoiceEndurance.begin("Phone quiet");VoiceEndurance.event("OWNER_ACCEPTED");VoiceEndurance.event("ACK_STARTED");VoiceEndurance.event("REARM");assertTrue(VoiceEndurance.report().contains("Completed attempts: 0"));VoiceEndurance.finish("Missed / failed");assertTrue(VoiceEndurance.report().contains("0/1 first calls worked"));VoiceEndurance.finish("First call worked");assertTrue(VoiceEndurance.report().contains("0/1 first calls worked"));}
 @Test public void autoCycleCountsDoNotBecomeAccuracyClaims(){VoiceEndurance.start(true);for(int i=0;i<501;i++){VoiceEndurance.event("OWNER_ACCEPTED");VoiceEndurance.event("ACK_STARTED");VoiceEndurance.event("REARM");}assertTrue(VoiceEndurance.report().contains("Automatic completed cycles: 500"));assertFalse(VoiceEndurance.report().contains("first calls worked"));}
 @Test public void refinementPreservesScoreEvidence()throws Exception{OwnerVoiceProfile p=OwnerAdaptationTest.profile();assertEquals(1,VoiceRefinementReport.minimum(p,false),.00001);OwnerVoiceProfile q=p.withLongSpeech(OwnerAdaptationTest.vector(192,.95),OwnerAdaptationTest.vector(128,.95),false);assertTrue(VoiceRefinementReport.minimum(q,false)>=q.threshold());assertTrue(VoiceRefinementReport.compare(p,q,false).contains("All four"));}
}
