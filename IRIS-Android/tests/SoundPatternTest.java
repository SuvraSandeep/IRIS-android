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
        // rankByConsistency()/bestSubset(): calibrate() failing does not mean the most-recently-
        // recorded sample is the problem — any earlier take could be the real outlier. Rather
        // than surgically removing one slot in place (which previously corrupted enrollment data
        // across repeated retries — see MainActivity), the recovery model is: record one spare
        // take, then keep only the best-agreeing subset of the resulting slightly-larger batch.
        // Plant a genuinely different sound at a non-final position and confirm the ranking
        // finds exactly that position, not just the last index.
        List<float[][]> withMiddleOutlier=new ArrayList<>();
        for(int i=0;i<3;i++)withMiddleOutlier.add(SoundPattern.extract(clip(3000+i*100,22400+i*320,false)));
        withMiddleOutlier.add(SoundPattern.extract(clip(4000,24000,true))); // reversed = genuinely different, at index 3
        for(int i=4;i<10;i++)withMiddleOutlier.add(SoundPattern.extract(clip(3000+i*100,22400+i*320,false)));
        boolean calibrateFailed=false;try{SoundPattern.calibrate(withMiddleOutlier);}catch(Exception e){calibrateFailed=true;}
        check(calibrateFailed,"a genuinely different sample must break calibration (test setup)");
        int[] ranked=SoundPattern.rankByConsistency(withMiddleOutlier);
        check(ranked.length==withMiddleOutlier.size(),"rankByConsistency must return one entry per template");
        check(ranked[0]==3,"rankByConsistency must rank the sample planted in the middle worst, not just the last index: got "+ranked[0]);
        List<float[][]> repaired=new ArrayList<>(withMiddleOutlier);repaired.remove(ranked[0]);
        boolean stillFails=false;try{SoundPattern.calibrate(repaired);}catch(Exception e){stillFails=true;}
        check(!stillFails,"removing the correctly-ranked worst outlier must let calibration succeed");
        check(SoundPattern.rankByConsistency(null).length==0,"rankByConsistency must not throw on null");
        check(SoundPattern.rankByConsistency(Collections.singletonList(reference)).length==0,"rankByConsistency needs at least 2 templates to compare");
        // bestSubset(): given 11 templates (10 required + 1 spare), must keep the 10 most
        // mutually consistent and drop exactly the planted outlier — this is what lets a spare
        // take replace a bad one WITHOUT ever mutating any list by position (see MainActivity's
        // ENROLLMENT-boundary spare-take recovery, which relies on this to avoid the index-
        // corruption bug an earlier surgical-removal design had).
        List<float[][]> withSpare=new ArrayList<>(withMiddleOutlier);
        withSpare.add(SoundPattern.extract(clip(3450,22720,false))); // the spare replacement take
        int[] kept=SoundPattern.bestSubset(withSpare,10);
        check(kept.length==10,"bestSubset must return exactly `keep` indices when enough templates exist");
        boolean outlierKept=false;for(int i:kept)if(i==3)outlierKept=true;
        check(!outlierKept,"bestSubset must drop the planted outlier, not keep it");
        List<float[][]> subset=new ArrayList<>();for(int i:kept)subset.add(withSpare.get(i));
        boolean subsetFails=false;try{SoundPattern.calibrate(subset);}catch(Exception e){subsetFails=true;}
        check(!subsetFails,"the best-10 subset chosen by bestSubset must actually calibrate");
        check(SoundPattern.bestSubset(withMiddleOutlier,20).length==withMiddleOutlier.size(),"bestSubset must return everything if `keep` exceeds the batch size");
        System.out.println("Passed recorded-sound feature, pace/gain, order, duration and invalid-input checks (synthetic audio only)");
    }
}
