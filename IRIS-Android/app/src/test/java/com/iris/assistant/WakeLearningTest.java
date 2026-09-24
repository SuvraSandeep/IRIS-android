package com.iris.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class WakeLearningTest {
 static float[][] sound(double cosine){float[][] p=new float[30][24];for(float[] r:p){r[0]=(float)cosine;r[1]=(float)Math.sqrt(1-cosine*cosine);}return p;}
 static RecordedPhrase phrase(double policy)throws Exception{
  List<float[][]> bank=Arrays.asList(sound(1),sound(1),sound(1),sound(1));return RecordedPhrase.create(bank,bank,policy);
 }
 @Test public void easeAffectsSoundAndOwnerAndCanUndoStrictness()throws Exception{
  RecordedPhrase strict=phrase(.85),easy=phrase(.65);
  assertFalse(strict.accepts(sound(.965)));assertTrue(easy.accepts(sound(.965)));
  OwnerVoiceProfile p=OwnerVoiceProfileTest.profileVoskOnly().withPolicy(.85);
  float[] sample=OwnerVoiceProfileTest.vv(.70);
  assertFalse(p.accepts(null,sample,.85));
  OwnerVoiceProfile relaxed=p.withPolicy(.65);
  assertTrue(relaxed.accepts(null,sample,.65));assertEquals(.65,relaxed.threshold(),1e-9);
  assertEquals(.05,relaxed.phraseEvidence.threshold,1e-9);
  assertFalse(relaxed.accepts(null,null,.65));assertFalse(relaxed.accepts(null,OwnerVoiceProfileTest.vv(0),.65));
 }
 @Test public void authenticatedSoundCorrectionDoesNotChangeSpeaker()throws Exception{
  OwnerVoiceProfile p=OwnerVoiceProfileTest.profileVoskOnly();float[][] missed=sound(.90);
  assertFalse(p.phraseEvidence.accepts(missed));
  OwnerVoiceProfile q=p.withSoundFeedback(missed,OwnerVoiceProfileTest.vv(1),false,true);
  assertTrue(q.phraseEvidence.accepts(missed));assertArrayEquals(p.voskCentroid(),q.voskCentroid(),0);
  assertEquals(4,q.phraseEvidence.validation.size());assertNotEquals(p.revision(),q.revision());
  assertTrue(new OwnerVoiceProfile(new JSONObject(q.data.toString())).phraseEvidence.accepts(missed));
  assertThrows(Exception.class,()->p.withSoundFeedback(missed,OwnerVoiceProfileTest.vv(0),false,true));
  assertThrows(Exception.class,()->p.withSoundFeedback(sound(0),OwnerVoiceProfileTest.vv(1),false,true));
 }
 @Test public void wrongSoundCanBeRejectedWithoutBlockingOwner()throws Exception{
  OwnerVoiceProfile p=OwnerVoiceProfileTest.profileVoskOnly();float[][] wrong=sound(.98);
  assertTrue(p.phraseEvidence.accepts(wrong));
  OwnerVoiceProfile q=p.withSoundFeedback(wrong,OwnerVoiceProfileTest.vv(1),false,false);
  assertFalse(q.phraseEvidence.accepts(wrong));assertTrue(q.phraseEvidence.accepts(sound(1)));
  assertTrue(q.accepts(null,OwnerVoiceProfileTest.vv(1),.65));
  assertThrows(Exception.class,()->p.withSoundFeedback(sound(1),OwnerVoiceProfileTest.vv(1),false,false));
 }
 @Test public void correctionStaysOnItsMicrophone()throws Exception{
  OwnerVoiceProfile p=OwnerVoiceProfileTest.profileVoskOnly();
  List<float[]> absent=Arrays.asList(new float[0],new float[0],new float[0],new float[0]);
  List<float[]> owner=Arrays.asList(OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1));
  p=p.withHeadset(absent,owner,absent,owner,phrase(.65));
  OwnerVoiceProfile q=p.withSoundFeedback(sound(.9),OwnerVoiceProfileTest.vv(1),true,true);
  assertTrue(q.headset.phraseEvidence.accepts(sound(.9)));assertFalse(q.phraseEvidence.accepts(sound(.9)));
 }
 @Test public void invalidControlsAndContradictoryFeedbackReject()throws Exception{
  assertThrows(Exception.class,()->WakePolicy.phraseTolerance(Double.NaN));
  assertThrows(Exception.class,()->phrase(.2));
  RecordedPhrase p=phrase(.85).withFeedback(sound(.9),true);
  assertThrows(Exception.class,()->p.withFeedback(sound(.9),false));
  JSONObject j=new JSONObject(p.data.toString()).put("positives","broken");
  assertThrows(Exception.class,()->new RecordedPhrase(j));
 }
 @Test public void legacyPhraseLoadsAndNewFormatCannotBeIgnored()throws Exception{
  JSONObject legacy=new JSONObject(phrase(.85).data.toString()).put("version",SoundPattern.VERSION);legacy.remove("tolerance");
  RecordedPhrase p=new RecordedPhrase(legacy);assertTrue(p.accepts(sound(1)));
  assertEquals(RecordedPhrase.FORMAT,p.withPolicy(.65).data.getString("version"));
 }
}
