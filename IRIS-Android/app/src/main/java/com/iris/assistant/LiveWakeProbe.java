package com.iris.assistant;
import java.util.*;
import java.util.function.Predicate;
/** Enrollment verification replays real PCM through the SAME detector used by background wake. */
final class LiveWakeProbe {
    static StreamingWakeDetector.Match check(short[] pcm,List<float[][]> samples,double threshold,Predicate<float[][]> accept){
        StreamingWakeDetector detector=new StreamingWakeDetector(samples,threshold,accept);
        short[] block=new short[320];
        // A bounded quiet tail flushes the detector's 100 ms look-ahead when Done was tapped.
        for(int offset=0;offset<pcm.length+3200;offset+=block.length){
            Arrays.fill(block,(short)0);int n=Math.min(block.length,Math.max(0,pcm.length-offset));
            if(n>0)System.arraycopy(pcm,offset,block,0,n);
            StreamingWakeDetector.Match found=detector.add(block,block.length);
            if(found!=null)return found;
        }
        return null;
    }
    static short[] speakerAudio(short[] pcm,StreamingWakeDetector.Match match){
        short[] clip=Arrays.copyOfRange(pcm,(int)Math.max(0,match.start-1600),(int)Math.min(pcm.length,match.end+1600));
        try{return SoundPattern.boundedContext(clip);}finally{Arrays.fill(clip,(short)0);}
    }
}
