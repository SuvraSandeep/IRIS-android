package com.iris.assistant;
import android.content.Context;
import org.json.*;
/** Explicit opt-in, encrypted and bounded metadata only. No recordings or transcripts. */
final class WakeDiagnostics {
    private static final String FILE="wake-diagnostics.json";
    static boolean enabled(Context c){return c.getSharedPreferences("wake_diagnostics",0).getBoolean("enabled",false);}
    static synchronized void enable(Context c,boolean enabled){
        c.getSharedPreferences("wake_diagnostics",0).edit().putBoolean("enabled",enabled).apply();
        if(enabled)event(c,"STUDY_STARTED","Label your own attempts; unlabelled detections are not accuracy measurements.");
    }
    static synchronized void clear(Context c){try{SecureStore.write(c,FILE,"{}");}catch(Exception ignored){}}
    static synchronized void event(Context c,String kind,String detail){
        if(!enabled(c))return;
        try{
            JSONObject root=new JSONObject(SecureStore.read(c,FILE,"{}"));JSONArray events=root.optJSONArray("events");if(events==null)events=new JSONArray();
            JSONObject counts=root.optJSONObject("counts");if(counts==null)counts=new JSONObject();counts.put(kind,counts.optInt(kind)+1);
            events.put(new JSONObject().put("at",System.currentTimeMillis()).put("kind",kind).put("detail",detail));
            while(events.length()>200)events.remove(0);
            root.put("events",events).put("counts",counts);SecureStore.write(c,FILE,root.toString());
        }catch(Exception ignored){}
    }
    static synchronized void exposure(Context c,long samples){
        if(!enabled(c)||samples<=0)return;
        try{JSONObject root=new JSONObject(SecureStore.read(c,FILE,"{}"));root.put("wakeSamples",root.optLong("wakeSamples")+samples);SecureStore.write(c,FILE,root.toString());}catch(Exception ignored){}
    }
    static synchronized String report(Context c){
        try{
            JSONObject root=new JSONObject(SecureStore.read(c,FILE,"{}"));JSONObject counts=root.optJSONObject("counts");
            StringBuilder out=new StringBuilder("Local wake study • metadata only\n\n");
            if(counts!=null){java.util.Iterator<String> keys=counts.keys();while(keys.hasNext()){String k=keys.next();out.append(k).append(": ").append(counts.optInt(k)).append('\n');}}
            if(counts!=null){int successes=counts.optInt("LABEL_FIRST_CALL_SUCCESS"),misses=counts.optInt("LABEL_MISSED_CALL");int attempts=successes+misses;
                if(attempts>0)out.append("\nLabelled first-call success: ").append(Math.round(100.0*successes/attempts)).append("% of ").append(attempts).append(" labelled calls\n");
                double hours=root.optLong("wakeSamples")/16000.0/3600;
                out.append(String.format(java.util.Locale.ROOT,"Measured wake audio: %.2f hours\n",hours));
                if(hours>=.25)out.append(String.format(java.util.Locale.ROOT,"Labelled unwanted wakes per hour: %.2f\n",counts.optInt("LABEL_UNWANTED_WAKE")/hours));
            }
            out.append("\nUse 50–100 labelled calls across phone/headset, quiet/noise and screen off. Mark each deliberate attempt once and mark unintended wakes. Automatic candidate counts are not a false-reject rate. No thresholds change automatically.\n\nRecent events:\n");
            JSONArray events=root.optJSONArray("events");if(events!=null)for(int i=Math.max(0,events.length()-30);i<events.length();i++){JSONObject e=events.getJSONObject(i);out.append(e.optString("kind")).append(" · ").append(e.optString("detail")).append('\n');}
            return out.toString();
        }catch(Exception e){return "No local study yet.";}
    }
}
