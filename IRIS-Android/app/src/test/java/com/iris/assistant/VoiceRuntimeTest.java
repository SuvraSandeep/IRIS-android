package com.iris.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class VoiceRuntimeTest {
 @Test public void exactLoggedTranscriptCannotBecomeAnSms(){
  assertFalse(SmsIntentPolicy.mayAddressContact("me that dying"));
  assertFalse(SmsIntentPolicy.mayAddressContact("me the time"));
  assertFalse(SmsIntentPolicy.mayAddressContact("us what happened"));
  assertTrue(SmsIntentPolicy.mayAddressContact("mom I am late"));
 }
 @Test public void offlineCommandsRequireConfidenceEvidence(){
  assertFalse(CommandEvidence.clear("{\"text\":\"tell me that dying\"}"));
  assertFalse(CommandEvidence.clear("{\"result\":[{\"conf\":0.2}]}"));
  assertFalse(CommandEvidence.clear("{\"result\":[{\"conf\":0.9},{\"conf\":0.1}]}"));
  assertTrue(CommandEvidence.clear("{\"result\":[{\"conf\":0.9},{\"conf\":0.8}]}"));
 }
 @Test public void cachedProfilesRemainIsolatedAndChangedDocumentsRevalidate()throws Exception{
  String json=OwnerVoiceProfileTest.profileVoskOnly().data.toString();
  OwnerVoiceProfile a=OwnerProfileCache.read(json),b=OwnerProfileCache.read(json);
  a.data.put("ownerThreshold",0);a.phraseEvidence.samples.get(0)[0][0]=0;
  assertEquals(.65,b.threshold(),1e-9);assertTrue(b.phraseEvidence.accepts(OwnerVoiceProfileTest.pattern(0)));
  OwnerVoiceProfile again=OwnerProfileCache.read(json);assertEquals(.65,again.threshold(),1e-9);
  assertThrows(Exception.class,()->OwnerProfileCache.read(new JSONObject(json).put("voskCentroid",new JSONArray()).toString()));
 }
 @Test public void loggedBorderlineVoiceCanBeExplicitlyCorrectedWithoutLoweringThreshold()throws Exception{
  List<float[]> absent=Arrays.asList(new float[0],new float[0],new float[0],new float[0]);
  List<float[]> owner=Arrays.asList(OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1),OwnerVoiceProfileTest.vv(1));
  OwnerVoiceProfile p=OwnerVoiceProfile.create("iris","a".repeat(64),absent,owner,absent,owner,.65,OwnerVoiceProfileTest.phraseEvidence());
  float[] borderline=OwnerVoiceProfileTest.vv(.614);
  assertFalse(p.accepts(null,borderline,.65));
  OwnerVoiceProfile q=p.withOwnerFeedback(OwnerVoiceProfileTest.pattern(0),borderline,false);
  assertTrue(q.accepts(null,borderline,.65));assertEquals(p.threshold(),q.threshold(),0);assertTrue(q.validates());
  assertFalse(q.accepts(null,OwnerVoiceProfileTest.vv(0),.65));
  assertThrows(Exception.class,()->p.withOwnerFeedback(OwnerVoiceProfileTest.pattern(0),OwnerVoiceProfileTest.vv(0),false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(OwnerVoiceProfileTest.pattern(1),borderline,false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(OwnerVoiceProfileTest.pattern(0),null,false));
 }
}
