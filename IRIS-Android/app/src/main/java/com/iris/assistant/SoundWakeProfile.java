package com.iris.assistant;
import org.json.*;
import java.util.*;

/** Separate sound and identity representations; no raw recording or accepted word aliases. */
final class SoundWakeProfile {
    final JSONObject data;final List<float[][]> examples,validation,negatives;final double threshold;
    SoundWakeProfile(JSONObject json)throws Exception{
        data=new JSONObject(json.toString());if(!SoundPattern.VERSION.equals(data.getString("version")))throw new IllegalArgumentException("Incompatible sound profile");
        examples=read(data.getJSONArray("examples"),10,12);validation=read(data.getJSONArray("validation"),4,12);negatives=read(data.getJSONArray("negatives"),0,12);
        threshold=data.getDouble("threshold");if(!Double.isFinite(threshold)||threshold<.025||threshold>.32)throw new IllegalArgumentException("Invalid sound policy");
        // Stored thresholds cannot exceed the policy derived only from enrollment examples.
        if(threshold>SoundPattern.calibrate(examples)+1e-8)throw new IllegalArgumentException("Unvalidated sound threshold");
        for(float[][] held:validation)if(!accepts(held))throw new IllegalArgumentException("Fresh sound verification did not match; no profile saved");
    }
    static SoundWakeProfile create(List<float[][]> examples,List<float[][]> validation)throws Exception{
        return new SoundWakeProfile(new JSONObject().put("version",SoundPattern.VERSION).put("examples",array(examples)).put("validation",array(validation)).put("negatives",new JSONArray()).put("threshold",SoundPattern.calibrate(examples)));
    }
    boolean accepts(float[][] sample){
        double positive=SoundPattern.score(sample,examples);if(!Double.isFinite(positive)||positive>threshold)return false;
        for(float[][] negative:negatives)if(SoundPattern.distance(sample,negative)<=positive+.015)return false;return true;
    }
    SoundWakeProfile withNegative(float[][] sample)throws Exception{
        if(!SoundPattern.valid(sample)||!accepts(sample))throw new IllegalArgumentException("No accepted sound evidence to correct");
        if(negatives.size()>=12)throw new IllegalArgumentException("Correction bank full; retrain or undo an update");
        JSONObject j=new JSONObject(data.toString());j.getJSONArray("negatives").put(matrix(sample));return new SoundWakeProfile(j);
    }
    static JSONArray array(List<float[][]> bank)throws Exception{JSONArray out=new JSONArray();for(float[][] a:bank)out.put(matrix(a));return out;}
    static JSONArray matrix(float[][] a)throws Exception{if(!SoundPattern.valid(a))throw new IllegalArgumentException("Invalid sound features");JSONArray out=new JSONArray();for(float[] row:a){JSONArray r=new JSONArray();for(float v:row)r.put((double)v);out.put(r);}return out;}
    static List<float[][]> read(JSONArray bank,int min,int max)throws Exception{
        if(bank.length()<min||bank.length()>max)throw new IllegalArgumentException("Invalid sound sample count");List<float[][]> out=new ArrayList<>();
        for(int i=0;i<bank.length();i++){JSONArray m=bank.getJSONArray(i);if(m.length()<12||m.length()>SoundPattern.MAX_FRAMES)throw new IllegalArgumentException("Invalid sound duration");float[][] a=new float[m.length()][SoundPattern.BANDS];
            for(int j=0;j<a.length;j++){JSONArray row=m.getJSONArray(j);if(row.length()!=SoundPattern.BANDS)throw new IllegalArgumentException("Invalid sound dimensions");for(int k=0;k<SoundPattern.BANDS;k++)a[j][k]=(float)row.getDouble(k);}if(!SoundPattern.valid(a))throw new IllegalArgumentException("Invalid sound features");out.add(a);
        }return out;
    }
}
