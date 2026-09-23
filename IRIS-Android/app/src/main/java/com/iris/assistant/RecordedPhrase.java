package com.iris.assistant;

import java.util.*;
import org.json.*;

/** Persisted phrase evidence, independent of speaker identity. Both must pass. */
final class RecordedPhrase {
    final List<float[][]> samples, validation;
    final double threshold;
    final JSONObject data;
    RecordedPhrase(JSONObject object) throws Exception {
        data=new JSONObject(object.toString());
        if(!SoundPattern.VERSION.equals(data.getString("version")))throw new IllegalArgumentException("Incompatible phrase evidence; retrain");
        samples=read(data.getJSONArray("samples"),4,4);
        validation=read(data.getJSONArray("validation"),4,4);
        threshold=data.getDouble("threshold");
        double expected=SoundPattern.calibrate(samples);
        if(!Double.isFinite(threshold)||Math.abs(threshold-expected)>1e-9)throw new IllegalArgumentException("Invalid phrase threshold");
        for(float[][] take:validation)if(!accepts(take))throw new IllegalArgumentException("Held-out phrase did not match");
    }
    static RecordedPhrase create(List<float[][]> samples,List<float[][]> validation)throws Exception {
        return new RecordedPhrase(new JSONObject().put("version",SoundPattern.VERSION)
            .put("samples",array(samples)).put("validation",array(validation))
            .put("threshold",SoundPattern.calibrate(samples)));
    }
    boolean accepts(float[][] pattern){return SoundPattern.valid(pattern)&&SoundPattern.score(pattern,samples)<=threshold;}
    static JSONArray array(List<float[][]> patterns)throws Exception {
        JSONArray out=new JSONArray();
        for(float[][] pattern:patterns){
            if(!SoundPattern.valid(pattern))throw new IllegalArgumentException("Invalid phrase pattern");
            JSONArray frames=new JSONArray();
            for(float[] row:pattern){JSONArray frame=new JSONArray();for(float v:row)frame.put((double)v);frames.put(frame);}
            out.put(frames);
        }
        return out;
    }
    static List<float[][]> read(JSONArray source,int min,int max)throws Exception {
        if(source.length()<min||source.length()>max)throw new IllegalArgumentException("Invalid phrase sample count");
        List<float[][]> out=new ArrayList<>();
        for(int i=0;i<source.length();i++){
            JSONArray frames=source.getJSONArray(i);
            if(frames.length()<12||frames.length()>SoundPattern.MAX_FRAMES)throw new IllegalArgumentException("Invalid phrase duration");
            float[][] pattern=new float[frames.length()][SoundPattern.BANDS];
            for(int f=0;f<frames.length();f++){
                JSONArray row=frames.getJSONArray(f);if(row.length()!=SoundPattern.BANDS)throw new IllegalArgumentException("Invalid phrase features");
                for(int k=0;k<row.length();k++)pattern[f][k]=(float)row.getDouble(k);
            }
            if(!SoundPattern.valid(pattern))throw new IllegalArgumentException("Invalid phrase pattern");out.add(pattern);
        }
        return out;
    }
}
