package com.iris.assistant;
import java.util.*;
/** Dedicated streaming subsequence detector over the user's acoustic examples.
 * Candidate generation only: full phrase evidence and owner verification must still pass.
 * No text/ASR gate. Enrollment and negative-feedback features remain authoritative. */
final class StreamingWakeDetector {
    static final class Match {
        final long start,end;final double distance;final float[][] pattern;
        Match(long s,long e,double d){this(s,e,d,null);}
        Match(long s,long e,double d,float[][] p){start=s;end=e;distance=d;pattern=p;}
    }
    private static final class Track {
        final float[][] template;double[] previous,next;long[] starts,nextStarts;
        Track(float[][] t){template=t;previous=new double[t.length+1];starts=new long[t.length+1];next=new double[t.length+1];nextStarts=new long[t.length+1];Arrays.fill(previous,Double.POSITIVE_INFINITY);previous[0]=0;}
    }
    private final List<Track> tracks=new ArrayList<>();private final double threshold;
    private final short[] window=new short[400];private int buffered;private long samples,frameIndex,lastEnd=-32000;
    private Match best;private int sinceBest;
    private final float[][] history=new float[560][];
    private final java.util.function.Predicate<float[][]> accept;
    StreamingWakeDetector(List<float[][]> examples,double threshold){
        this(examples,threshold,p->SoundPattern.variantScore(p,examples)<=threshold);
    }
    StreamingWakeDetector(List<float[][]> examples,double threshold,java.util.function.Predicate<float[][]> accept){
        this.accept=java.util.Objects.requireNonNull(accept);
        if(examples==null||examples.isEmpty()||!Double.isFinite(threshold)||threshold<=0||threshold>.32)throw new IllegalArgumentException("Invalid streaming profile");
        for(float[][] p:examples){if(!SoundPattern.valid(p))throw new IllegalArgumentException("Invalid template");tracks.add(new Track(p));}
        this.threshold=threshold;
    }
    Match add(short[] pcm,int count){
        Match found=null;
        for(int i=0;i<count;i++){
            window[buffered++]=pcm[i];samples++;
            if(buffered==400){Match m=feature(SoundPattern.frame(window,0),samples);if(m!=null)found=m;System.arraycopy(window,320,window,0,80);buffered=80;}
        }return found;
    }
    // Package visible for controlled feature-sequence tests, independent from the audio frontend.
    Match feature(float[] row,long sampleEnd){
        frameIndex++;history[(int)(frameIndex%history.length)]=row.clone();Match current=null;
        for(Track t:tracks){
            int size=t.template.length;double[] next=t.next;long[] starts=t.nextStarts;next[0]=0;starts[0]=frameIndex;
            for(int j=1;j<=size;j++){
                double cost=0;for(int k=0;k<24;k++)cost+=row[k]*t.template[j-1][k];cost=Math.max(0,1-cost);
                double diagonal=t.previous[j-1],stay=t.previous[j]+.015,skip=next[j-1]+.015;
                if(diagonal<=stay&&diagonal<=skip){next[j]=diagonal+cost;starts[j]=j==1?frameIndex:t.starts[j-1];}
                else if(stay<=skip){next[j]=stay+cost;starts[j]=t.starts[j];}
                else{next[j]=skip+cost;starts[j]=starts[j-1];}
                if(frameIndex-starts[j]+1>Math.ceil(size*2.2))next[j]=Double.POSITIVE_INFINITY;
            }
            t.next=t.previous;t.nextStarts=t.starts;t.previous=next;t.starts=starts;long length=frameIndex-starts[size]+1;
            double score=next[size]/Math.max(length,size);
            if(length>=Math.max(12,Math.ceil(size*.65))&&length<=Math.ceil(size*2.2)&&score<=threshold){
                long from=sampleEnd-400-(length-1)*320;
                if(from>=lastEnd&&(current==null||score<current.distance)&&length<=SoundPattern.MAX_FRAMES){
                    float[][] pattern=new float[(int)length][];
                    for(int f=0;f<pattern.length;f++)pattern[f]=history[(int)((starts[size]+f)%history.length)];
                    // Confirm the exact live frames, not a second, independently trimmed clip.
                    // Reject weak/prefix/negative candidates before stopping the streaming search.
                    current=new Match(Math.max(0,from),sampleEnd,score,pattern);
                }
            }
        }
        if(current!=null&&(best==null||current.distance<best.distance-1e-5)){best=current;sinceBest=0;}
        if(best!=null&&++sinceBest>=5){Match found=best;best=null;sinceBest=0;if(accept.test(found.pattern)){lastEnd=found.end;return found;}}
        return null;
    }
}
