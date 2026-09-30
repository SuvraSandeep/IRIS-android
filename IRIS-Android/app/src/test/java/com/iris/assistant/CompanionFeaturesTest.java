package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class CompanionFeaturesTest {
 @Test public void bridgeOnlyAcceptsPairedNarrowRequests(){
  String token="a".repeat(64);assertFalse(WatchBridge.authorized("Bearer ",""));assertFalse(WatchBridge.authorized("Bearer "+"b".repeat(64),token));assertTrue(WatchBridge.authorized("Bearer "+token,token));
  assertTrue(WatchBridge.allowed("GET","/status"));assertTrue(WatchBridge.allowed("POST","/find"));assertFalse(WatchBridge.allowed("GET","/find"));assertFalse(WatchBridge.allowed("POST","/command"));assertFalse(WatchBridge.allowed("POST","/find?token=secret"));
 }
 @Test public void longerSpeechKeepsPhraseThresholdAndHeldOutChecks()throws Exception{
  OwnerVoiceProfile p=OwnerAdaptationTest.profile();OwnerVoiceProfile q=p.withLongSpeech(OwnerAdaptationTest.vector(192,.95),OwnerAdaptationTest.vector(128,.95),false);
  assertTrue(q.validates());assertEquals(p.threshold(),q.threshold(),0);assertEquals(p.phraseEvidence.data.toString(),q.phraseEvidence.data.toString());assertNotEquals(p.revision(),q.revision());
  assertThrows(Exception.class,()->p.withLongSpeech(null,OwnerAdaptationTest.vector(128,.95),false));
  assertThrows(Exception.class,()->p.withLongSpeech(OwnerAdaptationTest.vector(192,.2),OwnerAdaptationTest.vector(128,.2),false));
  assertThrows(Exception.class,()->p.withLongSpeech(OwnerAdaptationTest.vector(192,.95),OwnerAdaptationTest.vector(128,.95),true));
 }
 @Test public void longerHeadsetSpeechCannotMovePhoneIdentity()throws Exception{
  OwnerVoiceProfile p=OwnerAdaptationTest.profile().withHeadset(OwnerAdaptationTest.bank(192),OwnerAdaptationTest.bank(128),OwnerAdaptationTest.bank(192),OwnerAdaptationTest.bank(128),OwnerVoiceProfileTest.phraseEvidence());
  OwnerVoiceProfile q=p.withLongSpeech(OwnerAdaptationTest.vector(192,.95),OwnerAdaptationTest.vector(128,.95),true);
  assertArrayEquals(p.voskCentroid(),q.voskCentroid(),0);assertTrue(q.validates());
 }
 @Test public void conflictingCloseAlternativesAskAgain(){
  assertTrue(CommandAmbiguity.needsRepeat(Arrays.asList("call mom","set a timer for 5 minutes"),new float[]{.8f,.75f}));
  assertFalse(CommandAmbiguity.needsRepeat(Arrays.asList("call mom","set a timer for 5 minutes"),new float[]{.9f,.4f}));
  assertFalse(CommandAmbiguity.needsRepeat(Arrays.asList("call mom","call mom"),new float[]{.8f,.75f}));
  assertFalse(CommandAmbiguity.needsRepeat(Arrays.asList("call mom","set a timer for 5 minutes"),new float[]{-1,-1}));
 }
}
