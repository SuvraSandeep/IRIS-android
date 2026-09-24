package com.iris.assistant;

import org.json.*;
import java.util.*;

/** Schema 8 stores recorded-phrase evidence AND independently validated owner identity.
 * Earlier profiles lack phrase evidence and require authenticated retraining. */
final class OwnerVoiceProfile {
    static final int SCHEMA = 8;
    static final String PREPROCESSING = "recorded-trim-gain-v1-pcm16-16000";
    /** Kept as the SECONDARY/ensemble model identifier (Vosk's x-vector) — the dedicated
     *  ECAPA-TDNN model has no single fixed "model name" string the same way (it's identified
     *  by WakePolicy.ECAPA_EMBED_DIM's fixed 192-dim output instead, checked structurally). */
    static final String MODEL = "vosk-model-spk-0.4";
    final JSONObject data;
    /** Optional second identity for a headset/earphone route (schema 7). Phone-route evidence
     *  above (ecapaCentroid/voskCentroid) always exists once any profile is saved; this is
     *  trained separately, afterwards, only if the user opts in via "Add earphone profile" — a
     *  phone mic and a headset mic pick up a noticeably different signal (distance, angle, own
     *  noise-cancelling/sidetone), so one enrollment does not reliably match the other route's
     *  audio. Absent (null) means: no headset route has been trained yet. */
    final HeadsetProfile headset;
    final RecordedPhrase phraseEvidence;
    static final class HeadsetProfile {
        final JSONObject data;
        final RecordedPhrase phraseEvidence;
        HeadsetProfile(HeadsetProfile source)throws Exception {data=new JSONObject(source.data.toString());phraseEvidence=new RecordedPhrase(source.phraseEvidence);}
        HeadsetProfile(JSONObject object) throws Exception {
            data = new JSONObject(object.toString());
            phraseEvidence=new RecordedPhrase(data.getJSONObject("phraseEvidence"));
            ecapaVector(data.getJSONArray("ecapaCentroid"));
            voskVector(data.getJSONArray("voskCentroid"));
        }
        float[] ecapaCentroid() { try { return ecapaVector(data.getJSONArray("ecapaCentroid")); } catch (Exception e) { return null; } }
        float[] voskCentroid() { try { return voskVector(data.getJSONArray("voskCentroid")); } catch (Exception e) { return null; } }
    }
    OwnerVoiceProfile(OwnerVoiceProfile source)throws Exception {
        data=new JSONObject(source.data.toString());phraseEvidence=new RecordedPhrase(source.phraseEvidence);
        headset=source.headset==null?null:new HeadsetProfile(source.headset);
    }
    OwnerVoiceProfile(JSONObject object) throws Exception {
        data = new JSONObject(object.toString());
        int schema = data.getInt("schema");
        if (schema != SCHEMA || !MODEL.equals(data.getString("speakerModel")) || !PREPROCESSING.equals(data.getString("preprocessing")))
            throw new IllegalArgumentException("Incompatible owner model/profile");
        phraseEvidence=new RecordedPhrase(data.getJSONObject("phraseEvidence"));
        headset = data.has("headset") ? new HeadsetProfile(data.getJSONObject("headset")) : null;
        String phrase = WakePolicy.normalize(data.getString("phrase"));
        if (phrase.isEmpty() || phrase.length() > 120) throw new IllegalArgumentException("Invalid phrase");
        if (!data.getString("modelHash").matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Missing model fingerprint; retrain to export");
        ecapaVector(data.getJSONArray("ecapaCentroid"));
        voskVector(data.getJSONArray("voskCentroid"));
        ecapaList("ecapaSamples", OwnerTrainingPlan.ENROLLMENT, OwnerTrainingPlan.ENROLLMENT);
        voskList("voskSamples", OwnerTrainingPlan.ENROLLMENT, OwnerTrainingPlan.ENROLLMENT);
        ecapaList("ecapaValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
        voskList("voskValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
        ecapaList("negatives", 0, 12);
        voskList("voskNegatives",0,12);
        // .65 + .20*strictness must accept the maximum-strictness case (strictness==1 -> 0.85
        // exactly in real numbers). IEEE 754 double arithmetic makes that sum 0.8500000000000001,
        // which a strict ">.85" check rejects — silently failing the final save at max strictness
        // even though every take passed validation. Add a small epsilon so the intended boundary
        // (0.85) is inclusive despite floating-point rounding. (Carried over unchanged from the
        // pre-redesign schema — this was a real, previously-fixed bug, not something the
        // redesign touches.)
        if (!Double.isFinite(threshold()) || threshold() < .65 - 1e-9 || threshold() > .85 + 1e-9)
            throw new IllegalArgumentException("Invalid owner policy");
        if (!validates()) throw new IllegalArgumentException("Saved validation samples do not pass this profile");
        if (headset != null) {
            List<float[]> ev = ecapaList("headsetEcapaValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
            List<float[]> vv = voskList("headsetVoskValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
            for (int i = 0; i < ev.size(); i++)
                if (!acceptsHeadset(ev.get(i), vv.get(i), threshold()))
                    throw new IllegalArgumentException("Saved headset validation samples do not pass this profile");
        }
    }
    /** Builds a fresh profile from enrollment (centroid-building) and held-out verification
     *  takes for BOTH embedding models. ecapaTakes/voskTakes/ecapaHeldOut/voskHeldOut must each
     *  align by take index (i.e. ecapaTakes.get(i) and voskTakes.get(i) came from the same
     *  recording) — the caller (MainActivity's enrollment pipeline) guarantees this by
     *  construction, since both embeddings are always extracted from the same trimmed clip in
     *  the same per-take pipeline step. */
    static OwnerVoiceProfile create(String phrase, String hash,
                                     List<float[]> ecapaTakes, List<float[]> voskTakes,
                                     List<float[]> ecapaHeldOut, List<float[]> voskHeldOut,
                                     double threshold, RecordedPhrase phraseEvidence) throws Exception {
        JSONObject j = new JSONObject().put("phraseEvidence",phraseEvidence.data).put("schema", SCHEMA).put("speakerModel", MODEL).put("preprocessing", PREPROCESSING).put("modelHash", hash)
            .put("recognizerModel", "vosk-model-small-en-us-0.15").put("phrase", phrase).put("revision", UUID.randomUUID().toString()).put("trainedAt", System.currentTimeMillis()).put("ownerThreshold", threshold)
            .put("ecapaSamples", ecapaArray(ecapaTakes)).put("voskSamples", voskArray(voskTakes))
            .put("ecapaValidation", ecapaArray(ecapaHeldOut)).put("voskValidation", voskArray(voskHeldOut))
            .put("negatives", new JSONArray()).put("voskNegatives",new JSONArray())
            .put("ecapaCentroid", ecapaArray(WakePolicy.enrollment(ecapaTakes, WakePolicy.ECAPA_EMBED_DIM)))
            .put("voskCentroid", voskArray(WakePolicy.enrollment(voskTakes, WakePolicy.EMBED_DIM)));
        return new OwnerVoiceProfile(j);
    }
    String phrase() { return data.optString("phrase"); }
    String revision() { return data.optString("revision"); }
    String hash() { return data.optString("modelHash"); }
    double threshold() { return data.optDouble("ownerThreshold", Double.NaN); }
    float[] ecapaCentroid() { try { return ecapaVector(data.getJSONArray("ecapaCentroid")); } catch (Exception e) { return null; } }
    float[] voskCentroid() { try { return voskVector(data.getJSONArray("voskCentroid")); } catch (Exception e) { return null; } }
    /** Primary accept/reject check — ensemble score (dedicated ECAPA-TDNN weighted 0.8, Vosk's
     *  x-vector weighted 0.2, see WakePolicy.finalScore()) against this profile's threshold,
     *  with the existing negative-example correction bank still applied on top. */
    boolean accepts(float[] ecapaSample, float[] voskSample, double policy) {
        if (!WakePolicy.owner(voskSample,voskSample,.99))return false;
        if (!WakePolicy.isAbsent(ecapaCentroid()) && !WakePolicy.ownerDim(ecapaSample,ecapaSample,.99,WakePolicy.ECAPA_EMBED_DIM))return false;
        if (!WakePolicy.ownerEnsemble(ecapaSample, ecapaCentroid(), voskSample, voskCentroid(), Math.max(policy, threshold()))) return false;
        try { for(float[] negative:voskList("voskNegatives",0,12))if(WakePolicy.cosine(voskSample,negative)>=.80)return false; for (float[] negative : ecapaList("negatives", 0, 12)) if (WakePolicy.cosine(ecapaSample, negative) >= .80) return false; return true; }
        catch (Exception e) { return false; }
    }
    boolean validates() {
        try {
            List<float[]> ev = ecapaList("ecapaValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
            List<float[]> vv = voskList("voskValidation", OwnerTrainingPlan.VERIFY, OwnerTrainingPlan.VERIFY);
            for (int i = 0; i < ev.size(); i++) if (!accepts(ev.get(i), vv.get(i), threshold())) return false;
            return true;
        } catch (Exception e) { return false; }
    }
    boolean acceptsHeadset(float[] ecapaSample, float[] voskSample, double policy) {
        if (headset == null || !WakePolicy.owner(voskSample,voskSample,.99)) return false;
        if (!WakePolicy.isAbsent(headset.ecapaCentroid()) && !WakePolicy.ownerDim(ecapaSample,ecapaSample,.99,WakePolicy.ECAPA_EMBED_DIM))return false;
        if (!WakePolicy.ownerEnsemble(ecapaSample, headset.ecapaCentroid(), voskSample, headset.voskCentroid(), Math.max(policy, threshold()))) return false;
        try { for(float[] negative:voskList("voskNegatives",0,12))if(WakePolicy.cosine(voskSample,negative)>=.80)return false; for (float[] negative : ecapaList("negatives", 0, 12)) if (WakePolicy.cosine(ecapaSample, negative) >= .80) return false; return true; }
        catch (Exception e) { return false; }
    }
    /** Adds (or replaces) the optional headset-route identity alongside the existing phone-route
     *  evidence above. Held-out headset validation takes must pass before this returns — same
     *  held-out-verification contract as the phone route uses, just scoped to this route's own
     *  vectors so a poor headset take can never be masked by the phone route's data. */
    OwnerVoiceProfile withHeadset(List<float[]> ecapaTakes, List<float[]> voskTakes,
                                  List<float[]> ecapaHeldOut, List<float[]> voskHeldOut, RecordedPhrase phraseEvidence) throws Exception {
        JSONObject h = new JSONObject().put("phraseEvidence",phraseEvidence.data)
            .put("ecapaCentroid", ecapaArray(WakePolicy.enrollment(ecapaTakes, WakePolicy.ECAPA_EMBED_DIM)))
            .put("voskCentroid", voskArray(WakePolicy.enrollment(voskTakes, WakePolicy.EMBED_DIM)));
        JSONObject j = new JSONObject(data.toString());
        j.put("headset", h).put("headsetEcapaValidation", ecapaArray(ecapaHeldOut)).put("headsetVoskValidation", voskArray(voskHeldOut))
            .put("revision", UUID.randomUUID().toString()).put("trainedAt", System.currentTimeMillis());
        return new OwnerVoiceProfile(j);
    }
    /** Drops a previously trained headset route while keeping the phone route untouched. */
    OwnerVoiceProfile withoutHeadset() throws Exception {
        JSONObject j = new JSONObject(data.toString());
        j.remove("headset"); j.remove("headsetEcapaValidation"); j.remove("headsetVoskValidation");
        j.put("revision", UUID.randomUUID().toString()).put("trainedAt", System.currentTimeMillis());
        return new OwnerVoiceProfile(j);
    }
    OwnerVoiceProfile withPolicy(double policy)throws Exception {
        JSONObject j=new JSONObject(data.toString()).put("ownerThreshold",policy)
            .put("phraseEvidence",phraseEvidence.withPolicy(policy).data);
        if(headset!=null)j.getJSONObject("headset").put("phraseEvidence",headset.phraseEvidence.withPolicy(policy).data);
        j.put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis());
        return new OwnerVoiceProfile(j);
    }
    /** Explicitly authenticated correction for a sound match with borderline owner evidence.
     * Runtime thresholds never change; all held-out checks and the corrected sample must pass. */
    OwnerVoiceProfile withOwnerFeedback(float[][] pattern,float[] speaker,boolean useHeadset)throws Exception {
        if(!WakePolicy.owner(speaker,speaker,.99))throw new IllegalArgumentException("Missing speaker evidence");
        if(useHeadset&&headset==null)throw new IllegalArgumentException("No headset profile");
        RecordedPhrase phrase=useHeadset?headset.phraseEvidence:phraseEvidence;
        if(!phrase.accepts(pattern))throw new IllegalArgumentException("The recorded sound must match first");
        float[] old=useHeadset?headset.voskCentroid():voskCentroid();
        float[] ecapa=useHeadset?headset.ecapaCentroid():ecapaCentroid();
        if(!WakePolicy.isAbsent(ecapa))throw new IllegalArgumentException("This profile needs fresh multi-model enrollment");
        double similarity=WakePolicy.cosine(old,speaker);
        if(similarity<.55||similarity>=threshold())throw new IllegalArgumentException("Use fresh enrollment for this voice; no borderline correction available");
        JSONObject j=new JSONObject(data.toString());JSONObject route=useHeadset?j.getJSONObject("headset"):j;
        int corrections=route.optInt("ownerCorrections",0);if(corrections>=6)throw new IllegalArgumentException("Correction limit reached; refine with fresh recordings");
        float[] adjusted=blendOwner(old,speaker);
        route.put("voskCentroid",voskArray(adjusted)).put("ownerCorrections",corrections+1);
        j.put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis());
        OwnerVoiceProfile candidate=new OwnerVoiceProfile(j);
        if(!(useHeadset?candidate.acceptsHeadset(null,speaker,threshold()):candidate.accepts(null,speaker,threshold())))
            throw new IllegalArgumentException("A small correction is insufficient; record fresh owner samples");
        return candidate;
    }
    private static float[] blendOwner(float[] old,float[] sample){
        double a=0,b=0;for(int i=0;i<old.length;i++){a+=old[i]*(double)old[i];b+=sample[i]*(double)sample[i];}
        float[] out=new float[old.length];double norm=0;
        for(int i=0;i<out.length;i++){out[i]=(float)(.9*old[i]/Math.sqrt(a)+.1*sample[i]/Math.sqrt(b));norm+=out[i]*(double)out[i];}
        for(int i=0;i<out.length;i++)out[i]/=Math.sqrt(norm);return out;
    }
    OwnerVoiceProfile withSoundFeedback(float[][] pattern,float[] speaker,boolean useHeadset,boolean missed)throws Exception {
        // Authentication alone cannot turn an unverified stranger into the enrolled owner.
        if(!(useHeadset?acceptsHeadset(null,speaker,threshold()):accepts(null,speaker,threshold())))
            throw new IllegalArgumentException("Speaker was not verified. Adjust strictness or record fresh owner examples");
        RecordedPhrase phrase=useHeadset?headset.phraseEvidence:phraseEvidence;
        RecordedPhrase corrected=phrase.withFeedback(pattern,missed);
        JSONObject j=new JSONObject(data.toString());
        if(useHeadset)j.getJSONObject("headset").put("phraseEvidence",corrected.data);else j.put("phraseEvidence",corrected.data);
        j.put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis());
        return new OwnerVoiceProfile(j);
    }
    OwnerVoiceProfile withNegative(float[] ecapaSample, float[] voskSample) throws Exception {
        if(!WakePolicy.owner(voskSample,voskSample,.99))throw new IllegalArgumentException("No valid speaker evidence for correction");
        if(!accepts(ecapaSample,voskSample,threshold())&&!acceptsHeadset(ecapaSample,voskSample,threshold()))
            throw new IllegalArgumentException("This profile already rejects the voice");
        JSONObject j=new JSONObject(data.toString());JSONArray negatives=j.getJSONArray("voskNegatives");
        if(negatives.length()>=12)throw new IllegalArgumentException("Correction limit reached; review or retrain");
        for(float[] previous:voskList("voskNegatives",0,12))if(WakePolicy.cosine(previous,voskSample)>.995)throw new IllegalArgumentException("Correction already recorded");
        negatives.put(voskArray(voskSample));j.put("revision",UUID.randomUUID().toString()).put("trainedAt",System.currentTimeMillis());
        // Constructor revalidates EVERY held-out owner take for both routes before activation.
        return new OwnerVoiceProfile(j);
    }
    List<float[]> ecapaList(String name, int min, int max) throws Exception {
        JSONArray a = data.getJSONArray(name);
        if (a.length() < min || a.length() > max) throw new IllegalArgumentException("Invalid sample count: " + name);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(ecapaVector(a.getJSONArray(i)));
        return out;
    }
    List<float[]> voskList(String name, int min, int max) throws Exception {
        JSONArray a = data.getJSONArray(name);
        if (a.length() < min || a.length() > max) throw new IllegalArgumentException("Invalid sample count: " + name);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(voskVector(a.getJSONArray(i)));
        return out;
    }
    static float[] ecapaVector(JSONArray a) throws Exception {
        // A zero-length array is the documented "ECAPA-TDNN absent" sentinel (see
        // WakePolicy.isAbsent()'s doc) — e.g. the dedicated model wasn't hosted/loaded when
        // this take/centroid was recorded. Any OTHER length is still a real corruption error.
        if (a.length() == 0) return new float[0];
        if (a.length() != WakePolicy.ECAPA_EMBED_DIM) throw new IllegalArgumentException("Wrong ECAPA-TDNN embedding dimension");
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.getDouble(i);
        if (!WakePolicy.ownerDim(v, v, .99, WakePolicy.ECAPA_EMBED_DIM)) throw new IllegalArgumentException("Invalid ECAPA-TDNN vector");
        return v;
    }
    static float[] voskVector(JSONArray a) throws Exception {
        if (a.length() != WakePolicy.EMBED_DIM) throw new IllegalArgumentException("Wrong Vosk embedding dimension");
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.getDouble(i);
        if (!WakePolicy.owner(v, v, .99)) throw new IllegalArgumentException("Invalid Vosk vector");
        return v;
    }
    static JSONArray ecapaArray(float[] v) throws Exception {
        // null or zero-length both serialize to an empty JSON array — the "ECAPA-TDNN absent"
        // sentinel round-trips through storage exactly like any other stored vector, rather
        // than needing special-case null-handling at every call site (see WakePolicy.isAbsent()).
        JSONArray a = new JSONArray();
        if (WakePolicy.isAbsent(v)) return a;
        if (v.length != WakePolicy.ECAPA_EMBED_DIM) throw new IllegalArgumentException("Inconsistent ECAPA-TDNN samples");
        for (float f : v) { if (!Float.isFinite(f)) throw new IllegalArgumentException("Invalid ECAPA-TDNN vector"); a.put((double) f); }
        return a;
    }
    static JSONArray voskArray(float[] v) throws Exception {
        if (v == null || v.length != WakePolicy.EMBED_DIM) throw new IllegalArgumentException("Inconsistent Vosk samples");
        JSONArray a = new JSONArray(); for (float f : v) { if (!Float.isFinite(f)) throw new IllegalArgumentException("Invalid Vosk vector"); a.put((double) f); } return a;
    }
    static JSONArray ecapaArray(List<float[]> vs) throws Exception { JSONArray a = new JSONArray(); for (float[] v : vs) a.put(ecapaArray(v)); return a; }
    static JSONArray voskArray(List<float[]> vs) throws Exception { JSONArray a = new JSONArray(); for (float[] v : vs) a.put(voskArray(v)); return a; }
}
