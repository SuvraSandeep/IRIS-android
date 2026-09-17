package com.iris.assistant;
import org.json.*;
import java.util.*;

/** Separate sound and identity representations; no raw recording or accepted word aliases. */
final class SoundWakeProfile {
    final JSONObject data;final List<float[][]> normalExamples,quietExamples,validation,negatives;final double normalThreshold,quietThreshold;
    /** Combined view of both groups, kept for any caller that only needs "all enrollment
     *  examples" without caring which group each belongs to (e.g. counting samples). */
    final List<float[][]> examples;
    SoundWakeProfile(JSONObject json)throws Exception{
        data=new JSONObject(json.toString());if(!SoundPattern.VERSION.equals(data.getString("version")))throw new IllegalArgumentException("Incompatible sound profile");
        // Split normal-volume and quiet-voice sound-pattern examples into two independently
        // calibrated groups instead of one pooled batch (explicit user request, after real
        // enrollment kept failing calibration at the enrollment boundary). A soft/quiet-spoken
        // take of the SAME sound genuinely has a different acoustic envelope than a normal-
        // volume take — that's the entire reason the training plan asks for both — so pooling
        // them into one leave-one-out consistency check punished exactly the variation the plan
        // intentionally asks the user to produce. This mirrors how the voice embedding side of
        // enrollment already works (candidateNormal/candidateQuiet, matched via
        // WakePolicy.ownerEither): two groups, each calibrated only against itself, accepted if
        // EITHER group matches.
        normalExamples=read(data.getJSONArray("normalExamples"),5,7);quietExamples=read(data.getJSONArray("quietExamples"),5,7);
        validation=read(data.getJSONArray("validation"),4,12);negatives=read(data.getJSONArray("negatives"),0,12);
        examples=new ArrayList<>(normalExamples);examples.addAll(quietExamples);
        normalThreshold=data.getDouble("normalThreshold");quietThreshold=data.getDouble("quietThreshold");
        if(!Double.isFinite(normalThreshold)||normalThreshold<.025||normalThreshold>.32)throw new IllegalArgumentException("Invalid sound policy");
        if(!Double.isFinite(quietThreshold)||quietThreshold<.025||quietThreshold>.32)throw new IllegalArgumentException("Invalid sound policy");
        // Stored thresholds cannot exceed the policy derived only from each group's own examples.
        if(normalThreshold>SoundPattern.calibrate(normalExamples)+1e-8)throw new IllegalArgumentException("Unvalidated sound threshold");
        if(quietThreshold>SoundPattern.calibrate(quietExamples)+1e-8)throw new IllegalArgumentException("Unvalidated sound threshold");
        for(float[][] held:validation)if(!accepts(held))throw new IllegalArgumentException("Fresh sound verification did not match; no profile saved");
    }
    static SoundWakeProfile create(List<float[][]> normalExamples,List<float[][]> quietExamples,List<float[][]> validation)throws Exception{
        return new SoundWakeProfile(new JSONObject().put("version",SoundPattern.VERSION)
            .put("normalExamples",array(normalExamples)).put("quietExamples",array(quietExamples))
            .put("validation",array(validation)).put("negatives",new JSONArray())
            .put("normalThreshold",SoundPattern.calibrate(normalExamples)).put("quietThreshold",SoundPattern.calibrate(quietExamples)));
    }
    boolean accepts(float[][] sample){
        double positiveNormal=SoundPattern.score(sample,normalExamples),positiveQuiet=SoundPattern.score(sample,quietExamples);
        boolean matchesNormal=Double.isFinite(positiveNormal)&&positiveNormal<=normalThreshold;
        boolean matchesQuiet=Double.isFinite(positiveQuiet)&&positiveQuiet<=quietThreshold;
        if(!matchesNormal&&!matchesQuiet)return false;
        double positive=matchesNormal?positiveNormal:positiveQuiet;
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
