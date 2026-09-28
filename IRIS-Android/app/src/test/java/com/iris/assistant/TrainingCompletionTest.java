package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class TrainingCompletionTest {
 @Test public void authenticationCancellationLeavesAnActionableReview(){
  OwnerTrainingStage stage=new OwnerTrainingStage();stage.enter(OwnerTrainingStage.Kind.REVIEW,"Eight takes ready",0,0);
  assertTrue(stage.canRetrySave());assertFalse(stage.expired(120000));
  stage.enter(OwnerTrainingStage.Kind.SAVE_FAILED,"Disk failure",0,0);assertTrue(stage.canRetrySave());
  stage.enter(OwnerTrainingStage.Kind.ANALYSIS,"Saving",0,0);assertFalse(stage.canRetrySave());
 }
 @Test public void eightPairedTakesBuildAndRoundTripWithoutARequiredNinthTake()throws Exception {
  OwnerEnrollmentController bank=new OwnerEnrollmentController();
  for(int i=0;i<8;i++)bank.addPaired(i,OwnerVoiceProfileTest.ev(1-(i%4)*.001),OwnerVoiceProfileTest.vv(1-(i%4)*.001),OwnerVoiceProfileTest.pattern(0),i>=4);
  OwnerVoiceProfile saved=bank.snapshot().build("My recorded phrase","a".repeat(64),.65).withEcapaModelHash("b".repeat(64));
  OwnerVoiceProfile readback=new OwnerVoiceProfile(new org.json.JSONObject(saved.data.toString()));
  assertTrue(readback.accepts(OwnerVoiceProfileTest.ev(1),OwnerVoiceProfileTest.vv(1),.65));
  assertFalse(readback.accepts(OwnerVoiceProfileTest.ev(0),OwnerVoiceProfileTest.vv(0),.65));
  assertEquals(4,readback.data.getJSONArray("voskValidation").length());
 }
 @Test public void saveSnapshotPreservesAllEightPairedTakesWhenUiSessionIsCleared(){
  OwnerEnrollmentController bank=new OwnerEnrollmentController();
  for(int i=0;i<4;i++){
   bank.ecapaSamples.add(new float[]{i+1});bank.voskSamples.add(new float[]{i+10});bank.phraseSamples.add(new float[][]{{i+20}});
   bank.ecapaValidation.add(new float[]{i+30});bank.voskValidation.add(new float[]{i+40});bank.phraseValidation.add(new float[][]{{i+50}});
  }
  OwnerEnrollmentController save=bank.snapshot();bank.phraseSamples.get(0)[0][0]=-1;bank.ecapaValidation.get(0)[0]=-1;bank.clear();
  assertEquals(4,save.voskSamples.size());assertEquals(4,save.voskValidation.size());
  assertEquals(20,save.phraseSamples.get(0)[0][0],0);assertEquals(30,save.ecapaValidation.get(0)[0],0);
 }
}
