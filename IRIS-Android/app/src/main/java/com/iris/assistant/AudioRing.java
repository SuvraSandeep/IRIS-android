package com.iris.assistant;
import java.util.Arrays;
/** Bounded, sample-clocked audio. Readers detect overwritten data instead of dropping words. */
final class AudioRing {
    private final short[] data;private long end;
    AudioRing(int capacity){if(capacity<1)throw new IllegalArgumentException();data=new short[capacity];}
    synchronized long append(short[] pcm,int n){for(int i=0;i<n;i++)data[(int)(end++%data.length)]=pcm[i];return end;}
    synchronized long end(){return end;}
    synchronized long first(){return Math.max(0,end-data.length);}
    synchronized short[] slice(long from,long to){
        if(from<first()||to>end||from>to)throw new IllegalStateException("Audio buffer overrun");
        short[] out=new short[(int)(to-from)];for(int i=0;i<out.length;i++)out[i]=data[(int)((from+i)%data.length)];return out;
    }
    synchronized void clear(){Arrays.fill(data,(short)0);end=0;}
}
