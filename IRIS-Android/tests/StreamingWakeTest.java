package com.iris.assistant;
import java.util.*;
public class StreamingWakeTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static float[] row(int band){float[] r=new float[24];r[band]=1;return r;}
 static float[][] phrase(){float[][] p=new float[40][24];for(int i=0;i<40;i++)p[i]=row(i/5);return p;}
 public static void main(String[] args){
  SoundPattern.Frontend frontend=new SoundPattern.Frontend();
  java.util.Random random=new java.util.Random(7);
  for(int attempt=0;attempt<30;attempt++){
   short[] wave=new short[400];for(int i=0;i<wave.length;i++)wave[i]=(short)(attempt%3==0?0:random.nextInt(20000)-10000);
   check(Arrays.equals(SoundPattern.frame(wave,0),frontend.frame(wave,0)),"Reused FFT scratch changed features");
  }
  AudioRing ring=new AudioRing(8);ring.append(new short[]{1,2,3,4,5},5);check(Arrays.equals(ring.slice(2,5),new short[]{3,4,5}),"Wake boundary");
  ring.append(new short[]{6,7,8,9,10},5);check(Arrays.equals(ring.slice(5,10),new short[]{6,7,8,9,10}),"Buffered command after delayed verification");
  boolean overrun=false;try{ring.slice(0,3);}catch(IllegalStateException expected){overrun=true;}check(overrun,"Do not silently lose command audio");
  ring.clear();check(ring.end()==0&&ring.slice(0,0).length==0,"Route/stop erase");
  StreamingWakeDetector detector=new StreamingWakeDetector(Arrays.asList(phrase(),phrase(),phrase(),phrase()),.08);
  long clock=0;StreamingWakeDetector.Match found=null;
  for(int i=0;i<50;i++){clock+=320;check(detector.feature(row(20),clock)==null,"Background shouldn't match");}
  for(float[] frame:phrase()){clock+=320;StreamingWakeDetector.Match m=detector.feature(frame,clock);if(m!=null)found=m;}
  // Follow immediately with other speech features, no silence/end-of-utterance needed.
  for(int i=0;i<10;i++){clock+=320;StreamingWakeDetector.Match m=detector.feature(row(15+i%4),clock);if(m!=null)found=m;}
  check(found!=null,"Phrase inside continuous speech must produce candidate");check(found.end<clock,"Command boundary precedes following speech");
  StreamingWakeDetector wrong=new StreamingWakeDetector(Collections.singletonList(phrase()),.08);clock=0;
  for(int i=0;i<200;i++){clock+=320;check(wrong.feature(row(18+i%3),clock)==null,"Wrong phrase stays rejected");}
  short[] pcm=new short[24000];for(int i=3200;i<16000;i++)pcm[i]=(short)(5000*Math.sin(i*(.07+.000001*i)));
  float[][] features=SoundPattern.extract(pcm);check(SoundPattern.valid(features),"Frontend retains valid enrollment features");
  short[] continuous=Arrays.copyOfRange(pcm,3200,16000);
  check(SoundPattern.valid(SoundPattern.extract(SoundPattern.boundedContext(continuous))),"Already segmented voice must not require recorded silence");

  // Replay actual waveform features, rather than handcrafted feature rows alone. Vary frame
  // alignment, volume and capture chunking; enrollment and live must agree on the same sound.
  for(int offset:new int[]{0,137,319,16000})for(double gain:new double[]{.4,1,1.5}){
   short[] replay=new short[pcm.length+offset+3200];
   for(int i=0;i<pcm.length;i++)replay[offset+i]=(short)(pcm[i]*gain);
   StreamingWakeDetector.Match match=LiveWakeProbe.check(replay,Collections.singletonList(features),.12,p->SoundPattern.distance(p,features)<=.12);
   check(match!=null,"Real PCM missed at offset "+offset+" gain "+gain);
   check(SoundPattern.valid(match.pattern),"Must preserve exactly the accepted live frames");
   check(match.end<=offset+20000,"Wake boundary consumed following audio");
  }
  check(LiveWakeProbe.check(pcm,Collections.singletonList(features),.12,p->false)==null,"Negative evidence must veto candidate before owner verification");
  StreamingWakeDetector afterReject=new StreamingWakeDetector(Collections.singletonList(features),.12);
  short[] repeated=new short[pcm.length*2];System.arraycopy(pcm,0,repeated,0,pcm.length);System.arraycopy(pcm,0,repeated,pcm.length,pcm.length);
  int detections=0;for(int i=0;i<repeated.length;i+=320){short[] block=Arrays.copyOfRange(repeated,i,Math.min(repeated.length,i+320));if(afterReject.add(block,block.length)!=null)detections++;}
  check(detections>=2,"Detector failed to recover for a second call");
  // The saved phrase matcher permits 0.45x duration. The streaming gate must not
  // silently impose a stricter 0.65x limit on the same authenticated sound.
  float[][] fast=new float[20][];for(int i=0;i<20;i++)fast[i]=phrase()[i*2];
  check(SoundPattern.distance(fast,phrase())<.08,"Fast phrase passes saved evidence");
  StreamingWakeDetector quick=new StreamingWakeDetector(Collections.singletonList(phrase()),.08);
  long fastClock=0;StreamingWakeDetector.Match quickMatch=null;
  for(int i=0;i<30;i++){fastClock+=320;quick.feature(row(20),fastClock);}
  for(float[] f:fast){fastClock+=320;StreamingWakeDetector.Match m=quick.feature(f,fastClock);if(m!=null)quickMatch=m;}
  for(int i=0;i<10;i++){fastClock+=320;StreamingWakeDetector.Match m=quick.feature(row(20),fastClock);if(m!=null)quickMatch=m;}
  check(quickMatch!=null,"Live detector rejects a faster phrase accepted by saved evidence");
  // A lower raw distance must not replace a candidate that passed full phrase evidence.
  StreamingWakeDetector retained=new StreamingWakeDetector(Collections.singletonList(phrase()),.08,p->p.length>=36&&p.length<40);
  long retainedClock=0;StreamingWakeDetector.Match retainedMatch=null;
  for(int i=0;i<30;i++){retainedClock+=320;retained.feature(row(20),retainedClock);}
  for(float[] f:phrase()){retainedClock+=320;StreamingWakeDetector.Match m=retained.feature(f,retainedClock);if(m!=null)retainedMatch=m;}
  for(int i=0;i<10;i++){retainedClock+=320;StreamingWakeDetector.Match m=retained.feature(row(20),retainedClock);if(m!=null)retainedMatch=m;}
  check(retainedMatch!=null,"Rejected lower-distance match displaced valid candidate");
  System.out.println("Passed streaming phrase-in-speech, negative stream, bounded handoff, overrun and erasure checks");
 }
}
