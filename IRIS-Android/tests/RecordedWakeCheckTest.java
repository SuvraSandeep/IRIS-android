package com.iris.assistant;
import java.util.*;
public class RecordedWakeCheckTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static short[] phrase(int offset,double gain,int base){
  short[] pcm=new short[128000];
  for(int i=0;i<16000;i++){
   double t=i/16000.0,f=base+(i<8000?0:400);
   pcm[offset+i]=(short)(gain*4000*Math.sin(2*Math.PI*f*t)*Math.sin(Math.PI*i/16000));
  }
  return pcm;
 }
 public static void main(String[] args){
  List<float[][]> bank=new ArrayList<>();for(int i=0;i<4;i++)bank.add(SoundPattern.extract(phrase(8000+i*320,.8+i*.1,300)));
  double threshold=SoundPattern.calibrate(bank);float[] owner=new float[128];owner[0]=1;float[] stranger=new float[128];stranger[1]=1;
  for(int offset:new int[]{3200,16000,80000})for(double gain:new double[]{.5,1,1.4}){
   float[][] pattern=SoundPattern.extract(phrase(offset,gain,300));
   check(RecordedWakeCheck.reject(pattern,bank,threshold,owner,owner,.75).isEmpty(),"same phrase with different volume/leading silence");
   check(RecordedWakeCheck.reject(pattern,bank,threshold,stranger,owner,.75).equals("OWNER_REJECTED"),"wrong owner admitted");
  }
  check(!RecordedWakeCheck.reject(SoundPattern.extract(phrase(8000,1,2600)),bank,threshold,owner,owner,.75).isEmpty(),"different sound admitted");
  check(!RecordedWakeCheck.reject(SoundPattern.extract(new short[48000]),bank,threshold,owner,owner,.75).isEmpty(),"silence admitted");
  check(!RecordedWakeCheck.reject(bank.get(0),bank,threshold,null,owner,.75).isEmpty(),"missing owner evidence admitted");
  check(!RecordedWakeCheck.reject(bank.get(0),bank,Double.NaN,owner,owner,.75).isEmpty(),"invalid profile admitted");
  check(!RecordedWakeCheck.reject(bank.get(0),bank,threshold,owner,owner,Double.NaN).isEmpty(),"invalid owner threshold admitted");
  System.out.println("Passed "+checks+" recorded-phrase AND owner decision checks (synthetic audio, not device accuracy)");
 }
}
