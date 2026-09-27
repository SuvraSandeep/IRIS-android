package com.iris.assistant;

/** Bounded, continuous gain for 16 kHz PCM. Never classifies speech or authorizes a wake. */
public final class QuietAudioProcessor {
    private double gain = 1;
    public void process(short[] pcm, int count) {
        if (pcm == null || count <= 0 || count > pcm.length) return;
        double mean = 0, energy = 0, peak = 0;
        for (int i=0;i<count;i++) mean += pcm[i];
        mean /= count;
        for (int i=0;i<count;i++) {
            double v=pcm[i]-mean; energy+=v*v; peak=Math.max(peak,Math.abs(v));
        }
        double rms=Math.sqrt(energy/count);
        // Do not amplify digital silence. Cap gain and leave headroom for sudden speech.
        double target=rms < 12 ? 1 : Math.min(8, Math.max(1, 1800/rms));
        gain += (target < gain ? .8 : .12)*(target-gain);
        double applied=peak == 0 ? 1 : Math.min(gain, 28000/peak);
        for(int i=0;i<count;i++) pcm[i]=(short)Math.round(Math.max(-28000,Math.min(28000,(pcm[i]-mean)*applied)));
    }
    /** Same 20 ms gain updates for buffered commands and recorded preprocessing. */
    public void processFrames(short[] pcm) {
        if(pcm==null)return;
        short[] frame=new short[320];
        try {
            for(int offset=0;offset<pcm.length;offset+=320){
                int n=Math.min(320,pcm.length-offset);
                System.arraycopy(pcm,offset,frame,0,n);process(frame,n);
                System.arraycopy(frame,0,pcm,offset,n);
            }
        } finally { java.util.Arrays.fill(frame,(short)0); }
    }
    public static short[] prepare(short[] input) {
        if(input == null) return null;
        short[] result=input.clone();new QuietAudioProcessor().processFrames(result);
        return result;
    }
}
