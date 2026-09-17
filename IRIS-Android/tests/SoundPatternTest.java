package com.iris.assistant;
import java.util.*;
public class SoundPatternTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static short[] clip(double gain,int length,boolean reversed){
        short[] pcm=new short[length+16000];double[] pitches=reversed?new double[]{2700,1600,850,400}:new double[]{400,850,1600,2700};
        for(int i=0;i<length;i++){double hz=pitches[Math.min(3,i*4/length)];double envelope=Math.min(1,Math.min(i/320.0,(length-i)/320.0));pcm[i+8000]=(short)(gain*envelope*(Math.sin(2*Math.PI*hz*i/16000)+.3*Math.sin(2*Math.PI*hz*1.5*i/16000)));}return pcm;
    }
    public static void main(String[] args){
        float[][] reference=SoundPattern.extract(clip(4000,24000,false));
        check(SoundPattern.valid(reference),"valid captured sound");
        check(SoundPattern.distance(reference,reference)<1e-6,"self match");
        List<float[][]> examples=new ArrayList<>();for(int i=0;i<10;i++)examples.add(SoundPattern.extract(clip(3000+i*100,22400+i*320,false)));
        double threshold=SoundPattern.calibrate(examples);
        double same=SoundPattern.score(SoundPattern.extract(clip(2000,24800,false)),examples);
        double wrong=SoundPattern.score(SoundPattern.extract(clip(4000,24000,true)),examples);
        check(same<=threshold,"gain/pace variation should match: "+same+" > "+threshold);
        check(wrong>threshold,"reversed temporal pattern must reject: "+wrong);
        short[] paused=clip(4000,24000,false);Arrays.fill(paused,16000,17600,(short)0);check(SoundPattern.valid(SoundPattern.extract(paused)),"internal silence must not invalidate speech");
        check(!SoundPattern.valid(SoundPattern.extract(new short[64000])),"silence rejects");
        short[] clipped=new short[64000];Arrays.fill(clipped,Short.MAX_VALUE);check(!SoundPattern.valid(SoundPattern.extract(clipped)),"clipping rejects");
        check(!Double.isFinite(SoundPattern.distance(reference,new float[0][])),"missing features reject");
        float[][] corrupt=new float[20][24];corrupt[0][0]=Float.NaN;check(!SoundPattern.valid(corrupt),"NaN rejects");
        check(!Double.isFinite(SoundPattern.distance(reference,SoundPattern.extract(clip(4000,8000,false)))),"truncated sound rejects");
        check(!Double.isFinite(SoundPattern.score(reference,examples.subList(0,2))),"single-template bypass rejects");
        System.out.println("Passed recorded-sound feature, pace/gain, order, duration and invalid-input checks (synthetic audio only)");
    }
}
