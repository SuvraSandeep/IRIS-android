package com.iris.assistant;

import java.util.Arrays;

/** Bounded utterance capture shared by training and live wake. Never retains idle history. */
final class PhraseCapture {
    static final int FRAME=320, PRE_ROLL=4800, MAX_SAMPLES=128000;
    private final short[] pending=new short[FRAME], pre=new short[PRE_ROLL], audio=new short[MAX_SAMPLES];
    private int pendingCount,preCount,preOffset,count,voiced,quiet;
    private boolean active;
    private double noise=60,activeMin;
    // Estimate ambient energy from the quietest frame in each idle second. Once speech starts,
    // freeze the estimate so the phrase cannot raise its own detection threshold.
    private double idleMin=Double.POSITIVE_INFINITY;
    private int idleFrames;
    short[] add(short[] input,int n){
        short[] complete=null;
        for(int i=0;i<n;i++){
            pending[pendingCount++]=input[i];
            if(pendingCount==FRAME){
                pendingCount=0;
                short[] next=frame();
                if(next!=null){if(complete!=null)Arrays.fill(complete,(short)0);complete=next;}
            }
        }
        return complete;
    }
    private short[] frame(){
        double e=0;for(short s:pending)e+=s*(double)s;
        double rms=Math.sqrt(e/FRAME);
        boolean speech=rms>=Math.max(150,noise*3);
        if(!active){
            // A bounded pre-roll preserves initial consonants without mixing earlier phrases.
            for(short s:pending){pre[preOffset]=s;preOffset=(preOffset+1)%pre.length;preCount=Math.min(pre.length,preCount+1);}
            idleMin=Math.min(idleMin,rms);
            if(++idleFrames>=50){noise=Math.max(60,idleMin);idleFrames=0;idleMin=Double.POSITIVE_INFINITY;}
            if(!speech)return null;
            active=true;activeMin=rms;count=0;voiced=FRAME;quiet=0;
            for(int i=0;i<preCount;i++)audio[count++]=pre[(preOffset-preCount+i+pre.length)%pre.length];
            return null;
        }
        activeMin=Math.min(activeMin,rms);
        System.arraycopy(pending,0,audio,count,FRAME);count+=FRAME;
        if(speech){voiced+=FRAME;quiet=0;}else quiet+=FRAME;
        // Keep the established 1.2 s pause between phrases; hard limit prevents noise from
        // wedging the endpoint forever. Too-long/noisy clips still face phrase AND owner checks.
        if(quiet<19200&&count<MAX_SAMPLES)return null;
        if(count>=MAX_SAMPLES)noise=Math.max(60,activeMin);
        short[] result=voiced>=6400?Arrays.copyOf(audio,count):null;
        resetUtterance();return result;
    }
    private void resetUtterance(){
        Arrays.fill(audio,(short)0);Arrays.fill(pre,(short)0);
        active=false;count=voiced=quiet=preCount=preOffset=0;
        idleFrames=0;idleMin=Double.POSITIVE_INFINITY;
    }
    void clear(){resetUtterance();Arrays.fill(pending,(short)0);pendingCount=0;noise=60;}
}
