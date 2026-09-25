package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class ContinuousVoicePolicyTest {
 @Test public void coldStartWaitsForDedicatedModelBeforeDeclaringFingerprintMismatch(){
  assertEquals(WakeModelState.State.LOADING_OWNER,WakeModelState.evaluate(true,true,true,false,false));
  assertEquals(WakeModelState.State.READY,WakeModelState.evaluate(true,true,true,true,true));
  assertEquals(WakeModelState.State.MODEL_CHANGED,WakeModelState.evaluate(true,true,true,true,false));
  assertEquals(WakeModelState.State.LOADING_VOSK,WakeModelState.evaluate(false,false,true,false,false));
  assertEquals(WakeModelState.State.READY,WakeModelState.evaluate(true,true,false,false,true));
 }
 @Test public void pairedEnrollmentRejectsDuplicateCallbackWithoutCorruptingCounts(){
  OwnerEnrollmentController bank=new OwnerEnrollmentController();
  bank.addPaired(0,OwnerVoiceProfileTest.ev(1),OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.pattern(0),false);
  assertThrows(IllegalStateException.class,()->bank.addPaired(0,OwnerVoiceProfileTest.ev(.9),OwnerVoiceProfileTest.vv(.9),OwnerVoiceProfileTest.pattern(0),false));
  assertEquals(1,bank.voskSamples.size());assertEquals(1,bank.ecapaSamples.size());assertEquals(1,bank.phraseSamples.size());
  assertEquals(0,bank.phraseValidation.size());
 }

 @Test public void dedicatedOwnerEvidenceCanResolveBorderlineVoskButNeverMissingEvidence(){
  float[][] sound=OwnerVoiceProfileTest.pattern(0);float[] owner=OwnerVoiceProfileTest.vv(1),borderline=OwnerVoiceProfileTest.vv(.614),e=OwnerVoiceProfileTest.ev(1);
  assertEquals("",RecordedWakeCheck.rejectEnsemble(sound,true,e,borderline,e,owner,.65));
  assertEquals("SPEAKER_EVIDENCE",RecordedWakeCheck.rejectEnsemble(sound,true,null,borderline,e,owner,.65));
  assertEquals("SPEAKER_EVIDENCE",RecordedWakeCheck.rejectEnsemble(sound,true,e,null,e,owner,.65));
  assertEquals("PHRASE_MISMATCH",RecordedWakeCheck.rejectEnsemble(sound,false,e,owner,e,owner,.65));
  assertEquals("OWNER_REJECTED",RecordedWakeCheck.rejectEnsemble(sound,true,OwnerVoiceProfileTest.ev(0),OwnerVoiceProfileTest.vv(0),e,owner,.65));
 }
 @Test public void dedicatedProfileRoundtripPreservesFingerprintAndHeldOutChecks()throws Exception{
  OwnerVoiceProfile profile=OwnerVoiceProfileTest.profile().withEcapaModelHash("b".repeat(64));
  OwnerVoiceProfile loaded=new OwnerVoiceProfile(profile.data);assertTrue(loaded.usesEcapa());assertTrue(loaded.validates());assertEquals("b".repeat(64),loaded.data.getString("ecapaModelHash"));
  assertThrows(Exception.class,()->profile.withEcapaModelHash(""));
 }
}
