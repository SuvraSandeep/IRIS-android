package com.iris.assistant;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;
public class VariantPhraseTest {
 static float[][] style(double angle,int frames){float[][] p=new float[frames][24];for(float[] f:p){f[0]=(float)Math.cos(angle);f[1]=(float)Math.sin(angle);}return p;}
 static List<float[][]> examples(){return Arrays.asList(style(0,30),style(.5,40),style(1,25),style(1.5,35));}
 static List<float[][]> fresh(){return Arrays.asList(style(.05,36),style(.55,32),style(1.05,30),style(1.45,28));}
 @Test public void diverseStylesAreLearnedWithoutDiscardingFourthTake()throws Exception{
  assertThrows(Exception.class,()->SoundPattern.calibrate(examples()));
  RecordedPhrase p=RecordedPhrase.createVariations(examples(),fresh(),.65);
  for(float[][] x:examples())assertTrue(p.accepts(x));
  for(float[][] x:fresh())assertTrue(p.accepts(x));
  assertFalse(p.accepts(OwnerVoiceProfileTest.pattern(2)));
  assertTrue(new RecordedPhrase(new JSONObject(p.data.toString())).variants());
  assertTrue(p.withPolicy(.85).variants());
 }
 @Test public void heldOutTakesRemainIndependentAndUnrelatedSoundsReject(){
  List<float[][]> checks=fresh();checks.set(3,OwnerVoiceProfileTest.pattern(2));
  assertThrows(Exception.class,()->RecordedPhrase.createVariations(examples(),checks,.65));
 }
 @Test public void trainingAndLiveUseSameDecisionAndStillRequireOwner()throws Exception{
  RecordedPhrase p=RecordedPhrase.createVariations(examples(),fresh(),.65);
  float[] owner=OwnerVoiceProfileTest.vv(1),stranger=OwnerVoiceProfileTest.vv(0);
  for(float[][] x:fresh()){
   assertEquals("",RecordedWakeCheck.rejectVariant(x,examples(),p.threshold,owner,owner,.65));
   assertEquals("",RecordedWakeCheck.reject(x,p.accepts(x),owner,owner,.65));
   assertEquals("OWNER_REJECTED",RecordedWakeCheck.reject(x,p.accepts(x),stranger,owner,.65));
  }
 }
 @Test public void invalidAndTamperedEvidenceReject()throws Exception{
  RecordedPhrase p=RecordedPhrase.createVariations(examples(),fresh(),.65);
  assertFalse(p.accepts(null));assertFalse(p.accepts(new float[0][]));
  assertThrows(Exception.class,()->new RecordedPhrase(new JSONObject(p.data.toString()).put("threshold",.99)));
 }
}
