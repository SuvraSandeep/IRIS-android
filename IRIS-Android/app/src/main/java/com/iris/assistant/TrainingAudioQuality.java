package com.iris.assistant;

/** Diagnostic energy estimates, not a speech detector or an accuracy percentage. */
final class TrainingAudioQuality {
    final int samples, voicedFrames, clipped;
    final double rms;
    private TrainingAudioQuality(int samples,int voiced,int clipped,double rms){this.samples=samples;this.voicedFrames=voiced;this.clipped=clipped;this.rms=rms;}
    static TrainingAudioQuality measure(short[] pcm){
        if(pcm==null||pcm.length<320)return new TrainingAudioQuality(0,0,0,0);
        double[] frames=new double[pcm.length/320];double energy=0;int clips=0;
        for(int f=0;f<frames.length;f++){double e=0;for(int i=f*320;i<(f+1)*320;i++){e+=pcm[i]*(double)pcm[i];if(Math.abs((int)pcm[i])>32000)clips++;}energy+=e;frames[f]=Math.sqrt(e/320);}
        double[] sorted=frames.clone();java.util.Arrays.sort(sorted);double floor=Math.max(60,sorted[sorted.length/10]);int voiced=0;
        for(double value:frames)if(value>=Math.max(150,floor*3))voiced++;
        return new TrainingAudioQuality(pcm.length,voiced,clips,Math.sqrt(energy/(frames.length*320)));
    }
    boolean enrollmentUsable(){return samples>=32000&&voicedFrames>=60&&clipped<samples/100;}
    String summary(){return String.format(java.util.Locale.ROOT,"PCM: 16 kHz mono; %.1f s captured; %.1f s above estimated noise; RMS %.0f; clipping %.2f%%",samples/16000.0,voicedFrames*.02,rms,samples==0?0:100.0*clipped/samples);}
}
