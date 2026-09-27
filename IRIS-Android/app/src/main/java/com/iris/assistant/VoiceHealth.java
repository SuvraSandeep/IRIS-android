package com.iris.assistant;
import java.util.ArrayDeque;
/** Bounded, in-memory metadata only. No audio, phrases, commands or embeddings. */
final class VoiceHealth {
    private static final ArrayDeque<String> events=new ArrayDeque<>();
    private static long lastPcmNanos,samples;private static String input="none";private static double peak;
    static synchronized void pcm(String route,short[] pcm,int n){
        lastPcmNanos=System.nanoTime();samples+=n;input=route;
        double energy=0;for(int i=0;i<n;i++)energy+=pcm[i]*(double)pcm[i];peak=Math.max(peak,Math.sqrt(energy/Math.max(1,n)));
    }
    static synchronized void event(String type,String detail){
        if(events.size()==48)events.removeFirst();
        String text=detail==null?"":detail.replace('\n',' ').replace('\r',' ');
        if(text.length()>1200)text=text.substring(0,1200);
        events.addLast(System.currentTimeMillis()+" "+type+" "+text);
    }
    static synchronized String snapshot(){
        long age=lastPcmNanos==0?-1:(System.nanoTime()-lastPcmNanos)/1_000_000;
        return "Input="+input+"; last PCM age ms="+age+"; captured samples="+samples+"; peak RMS="+Math.round(peak)+"\n"+String.join("\n",events);
    }
}
