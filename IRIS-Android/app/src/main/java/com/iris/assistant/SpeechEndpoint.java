package com.iris.assistant;

/** Conservative endpoint; never trims the returned PCM or drops pre-roll. */
final class SpeechEndpoint {
    private long samples,lastSpeech; private int voiced; private double floor=90;
    boolean add(short[] frame,int n){
        double energy=0;for(int i=0;i<n;i++)energy+=frame[i]*(double)frame[i];double rms=Math.sqrt(energy/Math.max(1,n));
        samples+=n;
        if(samples<=4800)floor=Math.min(400,Math.max(60,Math.min(floor,rms)));
        if(rms>Math.max(120,floor*2.5)){lastSpeech=samples;voiced+=n;}
        // At least 3 seconds overall and 1.2 seconds trailing silence protects pauses between words.
        return samples>=48000&&voiced>=6400&&samples-lastSpeech>=19200;
    }
}
