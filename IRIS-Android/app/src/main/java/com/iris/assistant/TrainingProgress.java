package com.iris.assistant;
import android.content.Context;
import org.json.*;
import java.util.*;
/** Encrypted, revision-bound checkpoints; audio patterns and vectors always stay paired. */
final class TrainingProgress {
    static final class Data {
        String phrase="",sessionRoute="UNCONFIRMED",baseRevision="",modelHash="";
        int takeIndex;
        final List<float[]> ecapaTakes=new ArrayList<>(),voskTakes=new ArrayList<>(),ecapaHeldOut=new ArrayList<>(),voskHeldOut=new ArrayList<>();
        final List<float[][]> phraseTakes=new ArrayList<>(),phraseHeldOut=new ArrayList<>();
    }
    private static String file(boolean headset){return headset?"wake_headset_v3.enc":"wake_training_v3.enc";}
    static boolean exists(Context c){return load(c)!=null;}
    static void clear(Context c){clear(c,false);}
    static void clear(Context c,boolean headset){try{SecureStore.write(c,file(headset),"");}catch(Exception ignored){}}
    static boolean save(Context c,boolean headset,String phrase,int index,String route,String revision,String hash,OwnerEnrollmentController bank){
        try{
            JSONObject j=new JSONObject().put("schema",3).put("phrase",phrase).put("takeIndex",index)
                .put("sessionRoute",route).put("baseRevision",revision).put("modelHash",hash)
                .put("ecapaTakes",OwnerVoiceProfile.ecapaArray(bank.ecapaSamples))
                .put("voskTakes",OwnerVoiceProfile.voskArray(bank.voskSamples))
                .put("ecapaHeldOut",OwnerVoiceProfile.ecapaArray(bank.ecapaValidation))
                .put("voskHeldOut",OwnerVoiceProfile.voskArray(bank.voskValidation))
                .put("phraseTakes",RecordedPhrase.array(bank.phraseSamples))
                .put("phraseHeldOut",RecordedPhrase.array(bank.phraseValidation));
            String text=j.toString();SecureStore.write(c,file(headset),text);
            return text.equals(SecureStore.read(c,file(headset),""));
        }catch(Exception error){return false;}
    }
    static Data load(Context c){return load(c,false);}
    static Data load(Context c,boolean headset){
        return parse(SecureStore.read(c,file(headset),""),new ProfileStore(c).ownerRevision(),headset,new AppSettings(c).ownerThreshold());
    }
    static Data parse(String raw,String revision,boolean headset,double policy){
        try{
            if(raw.length()>2000000)return null;
            JSONObject j=new JSONObject(raw);if(j.getInt("schema")!=3)return null;
            Data d=new Data();d.phrase=j.getString("phrase");d.takeIndex=j.getInt("takeIndex");
            d.sessionRoute=j.getString("sessionRoute");d.baseRevision=j.getString("baseRevision");d.modelHash=j.getString("modelHash");
            if(!d.baseRevision.equals(revision)||!d.modelHash.matches("[a-f0-9]{64}"))return null;
            if(!d.sessionRoute.equals(headset?"HEADSET":"PHONE"))return null;
            read(d.ecapaTakes,j.getJSONArray("ecapaTakes"),true);read(d.voskTakes,j.getJSONArray("voskTakes"),false);
            read(d.ecapaHeldOut,j.getJSONArray("ecapaHeldOut"),true);read(d.voskHeldOut,j.getJSONArray("voskHeldOut"),false);
            d.phraseTakes.addAll(RecordedPhrase.read(j.getJSONArray("phraseTakes"),0,4));
            d.phraseHeldOut.addAll(RecordedPhrase.read(j.getJSONArray("phraseHeldOut"),0,4));
            int n=d.voskTakes.size(),v=d.voskHeldOut.size();
            if(n!=d.ecapaTakes.size()||n!=d.phraseTakes.size()||v!=d.ecapaHeldOut.size()||v!=d.phraseHeldOut.size()
                ||d.takeIndex!=n+v||v>0&&n!=4||d.takeIndex<1||d.takeIndex>8)return null;
            if(n==4){
                double threshold=WakePolicy.phraseLimit(SoundPattern.calibrate(d.phraseTakes),policy);float[] centroid=WakePolicy.enrollment(d.voskTakes);
                if(centroid==null)return null;
                for(int i=0;i<v;i++)if(!RecordedWakeCheck.reject(d.phraseHeldOut.get(i),d.phraseTakes,threshold,d.voskHeldOut.get(i),centroid,policy).isEmpty())return null;
            }
            return d;
        }catch(Exception error){return null;}
    }
    private static void read(List<float[]> target,JSONArray source,boolean ecapa)throws Exception {
        if(source.length()>4)throw new IllegalArgumentException("Too many takes");
        for(int i=0;i<source.length();i++)target.add(ecapa?OwnerVoiceProfile.ecapaVector(source.getJSONArray(i)):OwnerVoiceProfile.voskVector(source.getJSONArray(i)));
    }
}
