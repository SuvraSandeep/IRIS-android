package com.iris.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class OwnerAdaptationTest {
 static float[] vector(int dim,double cosine){float[] v=new float[dim];v[0]=(float)cosine;v[1]=(float)Math.sqrt(1-cosine*cosine);return v;}
 static List<float[]> bank(int dim){return Arrays.asList(vector(dim,1),vector(dim,1),vector(dim,1),vector(dim,1));}
 static OwnerVoiceProfile profile()throws Exception{return OwnerVoiceProfile.create("iris","a".repeat(64),bank(192),bank(128),bank(192),bank(128),.70,OwnerVoiceProfileTest.phraseEvidence());}
 static float[][] phrase(){return OwnerVoiceProfileTest.pattern(0);}
 @Test public void dualModelVariationCanBeLearnedWithoutLoweringThreshold()throws Exception{
  OwnerVoiceProfile p=profile();float[] e=vector(192,.68),v=vector(128,.68);
  assertFalse(p.accepts(e,v,p.threshold()));
  OwnerVoiceProfile q=p.withOwnerFeedback(phrase(),e,v,false);
  assertTrue(q.accepts(e,v,q.threshold()));assertTrue(q.validates());
  assertTrue(new OwnerVoiceProfile(new JSONObject(q.data.toString())).accepts(e,v,.70));
  assertEquals(p.threshold(),q.threshold(),0);assertEquals(p.phraseEvidence.data.toString(),q.phraseEvidence.data.toString());
  assertArrayEquals(vector(192,1),OwnerVoiceProfile.ecapaVector(q.data.getJSONArray("ownerAnchorEcapa")),0);
  assertArrayEquals(p.ecapaCentroid(),vector(192,1),0);
  assertFalse(q.accepts(vector(192,0),vector(128,0),.70));
 }
 @Test public void missingCorruptDistantAndWrongPhraseEvidenceCannotAdapt()throws Exception{
  OwnerVoiceProfile p=profile();float[] e=vector(192,.68),v=vector(128,.68);
  assertThrows(Exception.class,()->p.withOwnerFeedback(phrase(),null,v,false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(phrase(),e,null,false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(phrase(),new float[192],v,false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(phrase(),vector(192,.3),vector(128,.3),false));
  assertThrows(Exception.class,()->p.withOwnerFeedback(OwnerVoiceProfileTest.pattern(1),e,v,false));
 }
 @Test public void headsetUpdateDoesNotMovePhoneIdentity()throws Exception{
  OwnerVoiceProfile p=profile().withHeadset(bank(192),bank(128),bank(192),bank(128),OwnerVoiceProfileTest.phraseEvidence());
  float[] e=vector(192,.68),v=vector(128,.68);
  OwnerVoiceProfile q=p.withOwnerFeedback(phrase(),e,v,true);
  assertTrue(q.acceptsHeadset(e,v,.70));assertFalse(q.accepts(e,v,.70));
  assertArrayEquals(p.ecapaCentroid(),q.ecapaCentroid(),0);assertArrayEquals(p.voskCentroid(),q.voskCentroid(),0);
  assertTrue(new OwnerVoiceProfile(q.data).acceptsHeadset(e,v,.70));
 }
 @Test public void negativeEvidenceStillVetoesConfirmedFeedback()throws Exception{
  OwnerVoiceProfile p=profile();float[] e=vector(192,.68),v=vector(128,.68);
  JSONObject j=new JSONObject(p.data.toString());j.getJSONArray("voskNegatives").put(OwnerVoiceProfile.voskArray(v));
  OwnerVoiceProfile blocked=new OwnerVoiceProfile(j); // negative is dissimilar enough to held-out owner
  assertThrows(Exception.class,()->blocked.withOwnerFeedback(phrase(),e,v,false));
 }
 static float[] rotate(float[] old,double angle){float[] v=old.clone();v[0]=(float)(old[0]*Math.cos(angle)-old[1]*Math.sin(angle));v[1]=(float)(old[0]*Math.sin(angle)+old[1]*Math.cos(angle));return v;}
 @Test public void repeatedCorrectionsCannotWalkAwayFromOriginalIdentity()throws Exception{
  OwnerVoiceProfile p=profile();String anchor=null;boolean blocked=false;
  for(int i=0;i<7;i++){
   float[] e=rotate(p.ecapaCentroid(),Math.acos(.68)),v=rotate(p.voskCentroid(),Math.acos(.68));
   try {OwnerVoiceProfile q=p.withOwnerFeedback(phrase(),e,v,false);
    if(anchor==null)anchor=q.data.getJSONArray("ownerAnchorEcapa").toString();
    assertEquals(anchor,q.data.getJSONArray("ownerAnchorEcapa").toString());p=q;
   }catch(IllegalArgumentException expected){blocked=true;break;}
  }
  assertTrue(blocked);assertTrue(p.validates());
 }
 @Test public void updateCannotSacrificeAnEarlierHeldOutVoiceStyle()throws Exception{
  float[] e=vector(192,.71),v=vector(128,.71);e[1]=-e[1];v[1]=-v[1];
  List<float[]> ev=Arrays.asList(e,e,e,e),vv=Arrays.asList(v,v,v,v);
  OwnerVoiceProfile p=OwnerVoiceProfile.create("iris","a".repeat(64),bank(192),bank(128),ev,vv,.70,OwnerVoiceProfileTest.phraseEvidence());
  assertTrue(p.validates());
  assertThrows(Exception.class,()->p.withOwnerFeedback(phrase(),vector(192,.68),vector(128,.68),false));
 }
 @Test public void damagedAnchorCannotLoad()throws Exception{
  OwnerVoiceProfile p=profile().withOwnerFeedback(phrase(),vector(192,.68),vector(128,.68),false);
  JSONObject j=new JSONObject(p.data.toString());j.remove("ownerAnchorEcapa");
  assertThrows(Exception.class,()->new OwnerVoiceProfile(j));
 }
}
