package com.iris.assistant;

import org.json.*;
import java.util.*;

/** Versioned personal evidence, never a trained acoustic model. */
final class OwnerVoiceProfile {
    static final String PREPROCESSING="quiet-v1-pcm16-16000", MODEL="vosk-model-spk-0.4";
    final JSONObject data;
    OwnerVoiceProfile(JSONObject object)throws Exception {
        data=new JSONObject(object.toString());
        if(data.getInt("schema")!=4||!MODEL.equals(data.getString("speakerModel"))||!PREPROCESSING.equals(data.getString("preprocessing")))throw new IllegalArgumentException("Incompatible owner model/profile");
        String phrase=WakePolicy.normalize(data.getString("phrase"));
        if(phrase.isEmpty()||phrase.length()>120)throw new IllegalArgumentException("Invalid phrase");
        if(!data.getString("modelHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Missing model fingerprint; retrain to export");
        vector(data.getJSONArray("voiceprint"));vector(data.getJSONArray("quietVoiceprint"));
        list("normalSamples",3,12);list("quietSamples",3,12);list("validation",4,12);list("negatives",0,12);
        if(!Double.isFinite(threshold())||threshold()<.65||threshold()>.85)throw new IllegalArgumentException("Invalid owner policy");
        if(!validates())throw new IllegalArgumentException("Saved validation samples do not pass this profile");
    }
    static OwnerVoiceProfile create(String phrase,String hash,List<float[]> normal,List<float[]> quiet,List<float[]> validation,double threshold)throws Exception {
        JSONObject j=new JSONObject().put("schema",4).put("speakerModel",MODEL).put("preprocessing",PREPROCESSING).put("modelHash",hash)
            .put("phrase",phrase).put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis()).put("ownerThreshold",threshold)
            .put("normalSamples",array(normal)).put("quietSamples",array(quiet)).put("validation",array(validation)).put("negatives",new JSONArray())
            .put("voiceprint",array(WakePolicy.enrollment(normal))).put("quietVoiceprint",array(WakePolicy.enrollment(quiet)));
        return new OwnerVoiceProfile(j);
    }
    String phrase(){return data.optString("phrase");}
    String revision(){return data.optString("revision");}
    String hash(){return data.optString("modelHash");}
    double threshold(){return data.optDouble("ownerThreshold",Double.NaN);}
    float[] normal(){try{return vector(data.getJSONArray("voiceprint"));}catch(Exception e){return null;}}
    float[] quiet(){try{return vector(data.getJSONArray("quietVoiceprint"));}catch(Exception e){return null;}}
    boolean accepts(float[] sample,double policy){
        if(!WakePolicy.ownerEither(sample,normal(),quiet(),Math.max(policy,threshold())))return false;
        try{for(float[] negative:list("negatives",0,12))if(WakePolicy.cosine(sample,negative)>=.80)return false;return true;}catch(Exception e){return false;}
    }
    boolean validates(){try{for(float[] v:list("validation",4,12))if(!accepts(v,threshold()))return false;return true;}catch(Exception e){return false;}}
    OwnerVoiceProfile withNegative(float[] sample)throws Exception {
        vector(array(sample));
        if(!accepts(sample,threshold()))throw new IllegalArgumentException("This profile already rejects this event; no identity change needed");
        JSONObject j=new JSONObject(data.toString());JSONArray n=j.getJSONArray("negatives");if(n.length()>=12)throw new IllegalArgumentException("Negative example limit reached; review or retrain");
        for(float[] v:list("negatives",0,12))if(WakePolicy.cosine(v,sample)>.995)throw new IllegalArgumentException("Event already represented");
        n.put(array(sample));j.put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis());
        // Constructor rejects the proposal if ANY held-out owner take would now fail.
        return new OwnerVoiceProfile(j);
    }
    List<float[]> list(String name,int min,int max)throws Exception {
        JSONArray a=data.getJSONArray(name);if(a.length()<min||a.length()>max)throw new IllegalArgumentException("Invalid sample count: "+name);
        List<float[]> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(vector(a.getJSONArray(i)));return out;
    }
    static float[] vector(JSONArray a)throws Exception {
        if(a.length()!=WakePolicy.EMBED_DIM)throw new IllegalArgumentException("Wrong voice dimension");float[] v=new float[a.length()];
        for(int i=0;i<v.length;i++)v[i]=(float)a.getDouble(i);
        if(!WakePolicy.owner(v,v,.99))throw new IllegalArgumentException("Invalid voice vector");return v;
    }
    static JSONArray array(float[] v)throws Exception {if(v==null)throw new IllegalArgumentException("Inconsistent samples");JSONArray a=new JSONArray();for(float f:v){if(!Float.isFinite(f))throw new IllegalArgumentException("Invalid voice vector");a.put((double)f);}return a;}
    static JSONArray array(List<float[]> vs)throws Exception{JSONArray a=new JSONArray();for(float[] v:vs)a.put(array(v));return a;}
}
