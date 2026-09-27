package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
public class CommandReliabilityTest {
 @Test public void preloadCannotStartCommandsWithoutARequest(){
  CommandLoadGate gate=new CommandLoadGate();assertFalse(gate.consume(1));
 }
 @Test public void switchingProviderOrStoppingCancelsPendingOfflineStart(){
  CommandLoadGate gate=new CommandLoadGate();gate.request(1);gate.cancel();assertFalse(gate.consume(1));
  gate.request(2);assertFalse(gate.consume(3));assertTrue(gate.consume(2));assertFalse(gate.consume(2));
 }
 @Test public void newestExplicitOfflineWindowCanUseTheFinishingModelLoad(){
  CommandLoadGate gate=new CommandLoadGate();gate.request(1);gate.cancel();gate.request(3);
  assertFalse(gate.consume(1));assertTrue(gate.consume(3));
 }
 @Test public void bufferedCommandsUseTheSameGainAsMicrophoneFrames(){
  short[] original=new short[16000];for(int i=0;i<original.length;i++)original[i]=(short)(220*Math.sin(i*.19)+80);
  short[] expected=original.clone();QuietAudioProcessor live=new QuietAudioProcessor();
  for(int i=0;i<expected.length;i+=320){short[] frame=Arrays.copyOfRange(expected,i,i+320);live.process(frame,320);System.arraycopy(frame,0,expected,i,320);}
  short[] buffered=original.clone();QuietAudioProcessor continuous=new QuietAudioProcessor();
  for(int i=0;i<buffered.length;i+=3200){short[] block=Arrays.copyOfRange(buffered,i,i+3200);continuous.processFrames(block);System.arraycopy(block,0,buffered,i,3200);}
  assertArrayEquals(expected,buffered);assertArrayEquals(expected,QuietAudioProcessor.prepare(original));
  long in=0,out=0;for(int i=0;i<original.length;i++){in+=(long)original[i]*original[i];out+=(long)buffered[i]*buffered[i];}
  assertTrue(out>in*4);
 }
}
