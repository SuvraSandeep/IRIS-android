package com.iris.assistant;

import java.util.*;
import org.json.*;

/** Persisted phrase evidence, independent of speaker identity. Both must pass. */
final class RecordedPhrase {
    static final String FORMAT=SoundPattern.VERSION+"-feedback-v1";
    static final String VARIANTS=SoundPattern.VERSION+"-variants-v2";
    final List<float[][]> samples, validation, positives, negatives;
    final double threshold;
    final JSONObject data;
    /** Defensive copy of already validated evidence: no repeated DTW calibration on UI reads. */
    RecordedPhrase(RecordedPhrase source)throws Exception {
        data=new JSONObject(source.data.toString());threshold=source.threshold;
        samples=copy(source.samples);validation=copy(source.validation);positives=copy(source.positives);negatives=copy(source.negatives);
    }
    private static List<float[][]> copy(List<float[][]> bank){
        List<float[][]> out=new ArrayList<>();for(float[][] pattern:bank){float[][] rows=new float[pattern.length][];
            for(int i=0;i<rows.length;i++)rows[i]=pattern[i].clone();out.add(rows);}return out;
    }
    RecordedPhrase(JSONObject object) throws Exception {
        data=new JSONObject(object.toString());
        if(!SoundPattern.VERSION.equals(data.getString("version"))&&!FORMAT.equals(data.getString("version"))&&!VARIANTS.equals(data.getString("version")))throw new IllegalArgumentException("Incompatible phrase evidence; retrain");
        samples=read(data.getJSONArray("samples"),4,4);
        validation=read(data.getJSONArray("validation"),4,4);
        threshold=data.getDouble("threshold");
        double tolerance=data.has("tolerance")?data.getDouble("tolerance"):1;
        if(!Double.isFinite(tolerance)||tolerance<1||tolerance>2)throw new IllegalArgumentException("Invalid phrase tolerance");
        double expected=Math.min(.32,calibration()*tolerance);
        positives=read(data.has("positives")?data.getJSONArray("positives"):new JSONArray(),0,6);
        negatives=read(data.has("negatives")?data.getJSONArray("negatives"):new JSONArray(),0,6);
        for(float[][] example:positives)if(score(example)>.32)throw new IllegalArgumentException("Correction is too different from the wake sound");
        if(!Double.isFinite(threshold)||Math.abs(threshold-expected)>1e-9)throw new IllegalArgumentException("Invalid phrase threshold");
        for(float[][] take:validation)if(!accepts(take))throw new IllegalArgumentException("Held-out phrase did not match");
        for(float[][] take:positives)if(!accepts(take))throw new IllegalArgumentException("Correction conflicts with a learned sound");
    }
    boolean variants(){return VARIANTS.equals(data.optString("version"));}
    double calibration(){return variants()?SoundPattern.variantCalibrate(samples):SoundPattern.calibrate(samples);}
    double score(float[][] pattern){return variants()?SoundPattern.variantScore(pattern,samples):SoundPattern.score(pattern,samples);}
    static RecordedPhrase createVariations(List<float[][]> samples,List<float[][]> validation,double policy)throws Exception {
        double tolerance=WakePolicy.phraseTolerance(policy);
        return new RecordedPhrase(new JSONObject().put("version",VARIANTS).put("tolerance",tolerance)
            .put("samples",array(samples)).put("validation",array(validation))
            .put("threshold",Math.min(.32,SoundPattern.variantCalibrate(samples)*tolerance)));
    }
    static RecordedPhrase create(List<float[][]> samples,List<float[][]> validation)throws Exception {
        return new RecordedPhrase(new JSONObject().put("version",FORMAT)
            .put("samples",array(samples)).put("validation",array(validation))
            .put("threshold",SoundPattern.calibrate(samples)));
    }
    static RecordedPhrase create(List<float[][]> samples,List<float[][]> validation,double policy)throws Exception {
        double tolerance=WakePolicy.phraseTolerance(policy);
        return new RecordedPhrase(new JSONObject().put("version",FORMAT).put("tolerance",tolerance)
            .put("samples",array(samples)).put("validation",array(validation))
            .put("threshold",Math.min(.32,SoundPattern.calibrate(samples)*tolerance)));
    }
    RecordedPhrase withPolicy(double policy)throws Exception {
        JSONObject j=new JSONObject(data.toString()).put("version",variants()?VARIANTS:FORMAT);double tolerance=WakePolicy.phraseTolerance(policy);
        j.put("tolerance",tolerance).put("threshold",Math.min(.32,calibration()*tolerance));
        return new RecordedPhrase(j);
    }
    RecordedPhrase withValidation(List<float[][]> heldOut)throws Exception {
        return new RecordedPhrase(new JSONObject(data.toString()).put("validation",array(heldOut)));
    }
    RecordedPhrase withFeedback(float[][] pattern,boolean missed)throws Exception {
        if(!SoundPattern.valid(pattern))throw new IllegalArgumentException("No complete sound evidence");
        if(missed&&accepts(pattern))throw new IllegalArgumentException("This sound already matches; inspect speaker or microphone diagnostics");
        JSONObject j=new JSONObject(data.toString()).put("version",variants()?VARIANTS:FORMAT);String key=missed?"positives":"negatives";
        List<float[][]> bank=new ArrayList<>(missed?positives:negatives);
        if(bank.size()>=6)throw new IllegalArgumentException("Feedback bank full; undo or retrain");
        for(float[][] prior:bank)if(SoundPattern.distance(prior,pattern)<.000001)throw new IllegalArgumentException("Already learned this event");
        bank.add(pattern);j.put(key,array(bank));
        // Construction protects all four held-out examples from an over-broad correction.
        return new RecordedPhrase(j);
    }
    boolean accepts(float[][] pattern){
        if(!SoundPattern.valid(pattern))return false;
        for(float[][] negative:negatives)if(SoundPattern.distance(pattern,negative)<=Math.min(.04,threshold*.5))return false;
        if(score(pattern)<=threshold)return true;
        for(float[][] positive:positives)if(SoundPattern.distance(pattern,positive)<=threshold*.75)return true;
        return false;
    }
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
