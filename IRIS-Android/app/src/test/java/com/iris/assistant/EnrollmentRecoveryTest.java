package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class EnrollmentRecoveryTest {
 @Test public void headsetRecoveryKeepsThreePairsAndRequiresFreshValidation() throws Exception {
  OwnerEnrollmentController bank=new OwnerEnrollmentController();
  for(int i=0;i<4;i++){
   float[] v=new float[128];v[i==1?1:0]=1;
   bank.add(i,null,v,false);bank.phraseSamples.add(OwnerVoiceProfileTest.pattern(0));
  }
  float[] held=new float[128];held[0]=1;
  bank.add(4,null,held,true);bank.phraseValidation.add(OwnerVoiceProfileTest.pattern(0));
  assertEquals(1,bank.replaceWeakest("OWNER_REJECTED"));
  assertEquals(3,bank.voskSamples.size());assertEquals(3,bank.ecapaSamples.size());assertEquals(3,bank.phraseSamples.size());
  assertTrue(bank.voskValidation.isEmpty());assertTrue(bank.ecapaValidation.isEmpty());assertTrue(bank.phraseValidation.isEmpty());
  bank.add(3,null,held,false);bank.phraseSamples.add(OwnerVoiceProfileTest.pattern(0));
  assertNotNull(WakePolicy.enrollment(bank.voskSamples));
  for(int i=4;i<8;i++){
   bank.add(i,null,held,true);bank.phraseValidation.add(OwnerVoiceProfileTest.pattern(0));
  }
  assertEquals(4,bank.phraseSamples.size());assertEquals(4,bank.phraseValidation.size());
  assertNotNull(RecordedPhrase.create(bank.phraseSamples,bank.phraseValidation));
 }
 @Test public void incompleteBankCannotBeRepaired(){
  try{new OwnerEnrollmentController().replaceWeakest("PHRASE_MISMATCH");fail();}catch(IllegalStateException expected){}
 }
}
