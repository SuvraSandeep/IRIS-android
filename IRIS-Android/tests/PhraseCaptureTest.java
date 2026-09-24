package com.iris.assistant;
import java.util.*;
public final class PhraseCaptureTest {
 static int checks; static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static List<short[]> feed(PhraseCapture c,short[] audio,int block){
  List<short[]> clips=new ArrayList<>();
  for(int i=0;i<audio.length;i+=block){int n=Math.min(block,audio.length-i);short[] frame=Arrays.copyOfRange(audio,i,i+n);short[] clip=c.add(frame,n);if(clip!=null)clips.add(clip);}
  return clips;
 }
 static short[] sound(int lead,int tail){
  short[] pcm=new short[lead+16000+tail];
  for(int i=0;i<16000;i++){double t=i/16000.;pcm[lead+i]=(short)(4000*Math.sin(2*Math.PI*(i<8000?300:700)*t)*Math.sin(Math.PI*i/16000));}
  return pcm;
 }
 public static void main(String[] args){
  short[] reference=null;
  for(int block:new int[]{160,320,512,777})for(int idle:new int[]{0,16000,16000*20}){
   List<short[]> clips=feed(new PhraseCapture(),sound(idle+4800,24000),block);
   check(clips.size()==1,"one phrase after idle, block="+block+" idle="+idle);
   short[] clip=clips.get(0);check(clip.length<48000,"idle history leaked into clip");
   check(SoundPattern.valid(SoundPattern.extract(clip)),"captured phrase unusable");
   if(reference==null)reference=clip;else check(Arrays.equals(reference,clip),"training/live chunk size or idle changed PCM");
  }
  PhraseCapture c=new PhraseCapture();List<short[]> first=feed(c,sound(4800,24000),320);
  List<short[]> second=feed(c,sound(16000*10,24000),512);
  check(first.size()==1&&second.size()==1,"consecutive phrases lost");
  check(Arrays.equals(first.get(0),second.get(0)),"previous phrase contaminated next phrase");
  check(feed(new PhraseCapture(),new short[16000*60],320).isEmpty(),"silence produced clips");
  short[] noise=new short[16000*20];Arrays.fill(noise,(short)800);
  List<short[]> bounded=feed(new PhraseCapture(),noise,512);
  check(!bounded.isEmpty(),"continuous audio wedged endpoint");for(short[] x:bounded)check(x.length<=128000,"unbounded capture");
  c=new PhraseCapture();short[] click=new short[24000];Arrays.fill(click,3200,6400,(short)2000);
  check(feed(c,click,512).isEmpty(),"short noise became a phrase");
  List<short[]> afterClick=feed(c,sound(4800,24000),320);
  check(afterClick.size()==1,"next wake lost after short sound");
  check(SoundPattern.distance(SoundPattern.extract(reference),SoundPattern.extract(afterClick.get(0)))<.001,"old short sound contaminated next wake");
  c=new PhraseCapture();feed(c,sound(4800,0),320);c.clear();
  check(feed(c,new short[32000],320).isEmpty(),"route reset retained old phrase");
  System.out.println("Passed "+checks+" phrase capture checks (idle, chunk boundaries, repeated wake, noise, route reset)");
 }
}
