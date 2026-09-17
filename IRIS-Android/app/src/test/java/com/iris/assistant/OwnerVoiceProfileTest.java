package com.iris.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

/** Redesigned per WAKE-TRAINING-REDESIGN.md: schema 7, dual-embedding (ECAPA-TDNN 192-dim +
 *  Vosk 128-dim) ensemble, 4-enrollment + 2-verification plan, no sound-pattern evidence. */
public class OwnerVoiceProfileTest {
    static float[] ev(double cosine){float[] v=new float[WakePolicy.ECAPA_EMBED_DIM];v[0]=(float)cosine;v[1]=(float)Math.sqrt(1-cosine*cosine);return v;}
    static float[] vv(double cosine){float[] v=new float[WakePolicy.EMBED_DIM];v[0]=(float)cosine;v[1]=(float)Math.sqrt(1-cosine*cosine);return v;}
    static OwnerVoiceProfile profile()throws Exception {
        List<float[]> ecapaTakes=Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997));
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        return OwnerVoiceProfile.create("Hello Iris","a".repeat(64),ecapaTakes,voskTakes,
            Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)),.65);
    }
    @Test public void roundTripPreservesDecisions()throws Exception {
        OwnerVoiceProfile p=profile(),q=new OwnerVoiceProfile(new JSONObject(p.data.toString()));
        assertEquals(p.revision(),q.revision());assertTrue(q.accepts(ev(1),vv(1),.65));assertFalse(q.accepts(ev(0),vv(0),.65));
        assertFalse(q.accepts(new float[WakePolicy.ECAPA_EMBED_DIM],new float[WakePolicy.EMBED_DIM],.65));assertFalse(q.accepts(new float[3],new float[3],.65));
    }
    @Test public void negativeCorrectionMustProtectValidation()throws Exception {
        OwnerVoiceProfile p=profile();assertThrows(IllegalArgumentException.class,()->p.withNegative(ev(1),vv(1)));
        OwnerVoiceProfile revised=p.withNegative(ev(.70),vv(.70));assertTrue(revised.accepts(ev(1),vv(1),.65));assertFalse(revised.accepts(ev(.70),vv(.70),.65));
        assertTrue(p.accepts(ev(.70),vv(.70),.65));assertNotEquals(p.revision(),revised.revision());
    }
    @Test public void malformedPackagesCannotBeActivated()throws Exception {
        JSONObject wrong=new JSONObject(profile().data.toString()).put("modelHash","unknown");assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(wrong));
        JSONObject dim=new JSONObject(profile().data.toString()).put("ecapaCentroid",new JSONArray("[1,2]"));assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(dim));
        JSONObject threshold=new JSONObject(profile().data.toString()).put("ownerThreshold",1.05);assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(threshold));
        JSONObject missing=new JSONObject(profile().data.toString()).put("ecapaValidation",new JSONArray());assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(missing));
    }
    @Test public void oldPooledSchemaIsRejectedNotSilentlyAccepted()throws Exception {
        // A pre-redesign schema (4-6, with voiceprint/quietVoiceprint/sound) must be correctly
        // rejected, not silently downgraded -- per this project's standing no-silent-fallback
        // contract (AGENTS.md).
        JSONObject legacy=new JSONObject(profile().data.toString()).put("schema",6);
        assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(legacy));
    }
    @Test public void cryptoAuthenticatesPasswordAndBytes()throws Exception {
        byte[] plain=profile().data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);char[] pass="a long test passphrase".toCharArray();
        byte[] encrypted=VoiceProfileCrypto.seal(plain,pass);assertArrayEquals(plain,VoiceProfileCrypto.open(encrypted,pass));
        assertThrows(Exception.class,()->VoiceProfileCrypto.open(encrypted,"the wrong passphrase".toCharArray()));
        byte[] changed=encrypted.clone();changed[changed.length-1]^=1;assertThrows(Exception.class,()->VoiceProfileCrypto.open(changed,pass));
        byte[] header=encrypted.clone();header[5]^=1;assertThrows(Exception.class,()->VoiceProfileCrypto.open(header,pass));
        assertFalse(Arrays.equals(encrypted,VoiceProfileCrypto.seal(plain,pass)));
        assertThrows(Exception.class,()->VoiceProfileCrypto.open(new byte[VoiceProfileCrypto.LIMIT+49],pass));
    }
    @Test public void endpointDoesNotStopOnSilenceOrInterwordPause(){
        SpeechEndpoint e=new SpeechEndpoint();short[] silence=new short[320],speech=new short[320];Arrays.fill(speech,(short)1200);
        for(int i=0;i<150;i++)assertFalse(e.add(silence,320));
        for(int i=0;i<30;i++)assertFalse(e.add(speech,320));
        for(int i=0;i<40;i++)assertFalse(e.add(silence,320)); // 800ms pause must retain the next word
        for(int i=0;i<30;i++)assertFalse(e.add(speech,320));
        for(int i=0;i<59;i++)assertFalse(e.add(silence,320));assertTrue(e.add(silence,320));
    }
    @Test public void leasePreventsCompetingRecorders(){Object one=AudioCaptureCoordinator.acquire();assertNotNull(one);assertNull(AudioCaptureCoordinator.acquire());AudioCaptureCoordinator.release(new Object());assertTrue(AudioCaptureCoordinator.busy());AudioCaptureCoordinator.release(one);assertFalse(AudioCaptureCoordinator.busy());}
    @Test public void incompletePhraseCannotPass(){assertFalse(PhraseEvidence.complete("Hello Iris","hello"));assertFalse(PhraseEvidence.complete("Hello Iris","hello iris please"));assertTrue(PhraseEvidence.complete("Hello Iris","Hello, Iris!"));assertTrue(PhraseEvidence.mismatch("Hello Iris","hello").startsWith("PHRASE_INCOMPLETE"));}
    @Test public void maximumStrictnessThresholdIsNotRejectedByFloatingPointRounding()throws Exception {
        // .65 + .20*1.0 == 0.8500000000000001 in IEEE 754 double arithmetic, not exactly 0.85.
        // A strict ">.85" check on this value rejected every save made at maximum owner
        // strictness even though every take passed validation — this pins that boundary.
        double maxStrictness=.65+.20*Math.max(0,Math.min(1,1.0f));
        assertTrue("test setup: expected the real double-rounding artifact above .85",maxStrictness>.85);
        List<float[]> ecapaTakes=Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997));
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        OwnerVoiceProfile.create("Hello Iris","a".repeat(64),ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)),maxStrictness);
        // A threshold genuinely outside the intended [.65,.85] range must still be rejected.
        assertThrows(IllegalArgumentException.class,()->OwnerVoiceProfile.create("Hello Iris","a".repeat(64),ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)),.86));
        assertThrows(IllegalArgumentException.class,()->OwnerVoiceProfile.create("Hello Iris","a".repeat(64),ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)),.64));
    }
    @Test public void headsetRouteIsAbsentUntilExplicitlyAdded()throws Exception {
        OwnerVoiceProfile phone=profile();
        assertNull(phone.headset);
        assertFalse(phone.acceptsHeadset(ev(1),vv(1),.65));
    }    @Test public void addingHeadsetRoutePreservesPhoneRouteAndKeepsSchema()throws Exception {
        OwnerVoiceProfile phone=profile();
        List<float[]> ecapaTakes=Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997));
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        OwnerVoiceProfile withHeadset=phone.withHeadset(ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)));
        assertNotNull(withHeadset.headset);
        assertEquals(OwnerVoiceProfile.SCHEMA,withHeadset.data.getInt("schema"));
        // Phone-route identity and matching survive adding a headset route untouched.
        assertNotNull(withHeadset.ecapaCentroid());assertNotNull(withHeadset.voskCentroid());
        assertTrue(withHeadset.accepts(ev(1),vv(1),.65));
        // Headset-route matching works on its own vectors.
        assertTrue(withHeadset.acceptsHeadset(ev(1),vv(1),.65));
        assertNotEquals(phone.revision(),withHeadset.revision());
    }
    @Test public void headsetRouteSurvivesJsonRoundTrip()throws Exception {
        OwnerVoiceProfile phone=profile();
        List<float[]> ecapaTakes=Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997));
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        OwnerVoiceProfile withHeadset=phone.withHeadset(ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)));
        OwnerVoiceProfile roundTrip=new OwnerVoiceProfile(new JSONObject(withHeadset.data.toString()));
        assertNotNull(roundTrip.headset);assertTrue(roundTrip.acceptsHeadset(ev(1),vv(1),.65));
    }
    @Test public void removingHeadsetRouteLeavesPhoneRouteIntact()throws Exception {
        OwnerVoiceProfile phone=profile();
        List<float[]> ecapaTakes=Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997));
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        OwnerVoiceProfile withHeadset=phone.withHeadset(ecapaTakes,voskTakes,Arrays.asList(ev(1),ev(.999)),Arrays.asList(vv(1),vv(.999)));
        OwnerVoiceProfile removed=withHeadset.withoutHeadset();
        assertNull(removed.headset);assertNotNull(removed.ecapaCentroid());assertTrue(removed.accepts(ev(1),vv(1),.65));
        assertNotEquals(withHeadset.revision(),removed.revision());
    }
    // --- ECAPA-TDNN absent (Vosk-only) coverage -----------------------------------------
    // Real on-device bug this pins: EcapaEmbedding.MODEL_URL is still a placeholder (no
    // hosted ONNX file yet — see EcapaEmbedding.java's doc), so ecapaEngine.extract() always
    // returns null during training. WakePolicy.finalScore()/ownerEnsemble() were always
    // designed to gracefully degrade to Vosk-only in this case, but OwnerEnrollmentController
    // and OwnerVoiceProfile originally hard-required a valid ECAPA vector at every layer,
    // making training completely unusable (every take showed "ECAPA components: 0" and was
    // rejected outright) until WakePolicy.isAbsent()'s sentinel was threaded through storage.
    static OwnerVoiceProfile profileVoskOnly()throws Exception {
        List<float[]> ecapaTakes=Arrays.asList(new float[0],new float[0],new float[0],new float[0]);
        List<float[]> voskTakes=Arrays.asList(vv(1),vv(.999),vv(.998),vv(.997));
        return OwnerVoiceProfile.create("Hello Iris","a".repeat(64),ecapaTakes,voskTakes,
            Arrays.asList(new float[0],new float[0]),Arrays.asList(vv(1),vv(.999)),.65);
    }
    @Test public void profileCanBeBuiltAndSavedWithEcapaEntirelyAbsent()throws Exception {
        OwnerVoiceProfile p=profileVoskOnly();
        assertTrue(WakePolicy.isAbsent(p.ecapaCentroid()));
        assertNotNull(p.voskCentroid());
        // Vosk-only match still works via finalScore()'s single-signal degrade.
        assertTrue(p.accepts(new float[0],vv(1),.65));
        assertTrue(p.accepts(null,vv(1),.65));
        assertFalse(p.accepts(new float[0],vv(0),.65));
    }
    @Test public void voskOnlyProfileSurvivesJsonRoundTrip()throws Exception {
        OwnerVoiceProfile p=profileVoskOnly();
        OwnerVoiceProfile q=new OwnerVoiceProfile(new JSONObject(p.data.toString()));
        assertTrue(WakePolicy.isAbsent(q.ecapaCentroid()));assertNotNull(q.voskCentroid());
        assertTrue(q.accepts(new float[0],vv(1),.65));
    }
    @Test public void bothSignalsAbsentIsStillRejected()throws Exception {
        // Vosk must always be present in this test project's build (bundled model) — this
        // pins that a profile can never accept when BOTH signals are missing/invalid,
        // consistent with AGENTS.md's "missing identity/model... must reject wake" contract.
        OwnerVoiceProfile p=profileVoskOnly();
        assertFalse(p.accepts(new float[0],new float[0],.65));
        assertFalse(p.accepts(null,null,.65));
    }
    @Test public void enrollmentRejectsMissingVoskEvenWithEcapaPresent()throws Exception {
        // Vosk is always bundled/available in this app; a missing Vosk vector on a real take
        // means something genuinely went wrong with that take and must still be rejected
        // outright -- absence-tolerance is ONE-DIRECTIONAL (ECAPA may be absent, Vosk may not).
        assertThrows(IllegalArgumentException.class,()->OwnerVoiceProfile.create("Hello Iris","a".repeat(64),
            Arrays.asList(ev(1),ev(.999),ev(.998),ev(.997)),Arrays.asList(new float[0],new float[0],new float[0],new float[0]),
            Arrays.asList(ev(1),ev(.999)),Arrays.asList(new float[0],new float[0]),.65));
    }
    @Test public void withNegativeRejectsAbsentEcapaSample()throws Exception {
        // An absent-ECAPA "negative" would always score cosine==-1 against any real sample and
        // could never actually correct anything -- must be rejected explicitly, not silently
        // accepted and wasted via ecapaArray()/ecapaVector()'s now-tolerant round-trip.
        OwnerVoiceProfile p=profile();
        assertThrows(IllegalArgumentException.class,()->p.withNegative(new float[0],vv(.70)));
        assertThrows(IllegalArgumentException.class,()->p.withNegative(null,vv(.70)));
    }
    @Test public void enrollmentControllerAcceptsAbsentEcapaButRequiresVosk(){
        OwnerEnrollmentController controller=new OwnerEnrollmentController();
        // ECAPA absent (null), Vosk valid — must be accepted and stored as the absent sentinel.
        controller.add(0,null,vv(1),false);
        assertEquals(1,controller.ecapaSamples.size());
        assertEquals(0,controller.ecapaSamples.get(0).length);
        assertEquals(1,controller.voskSamples.size());
        // Vosk absent — must still throw regardless of ECAPA.
        assertThrows(IllegalArgumentException.class,()->controller.add(1,ev(1),null,false));
        assertThrows(IllegalArgumentException.class,()->controller.add(1,null,null,false));
        // A genuinely corrupt (wrong-length) ECAPA vector is still rejected, not silently
        // treated as absent.
        assertThrows(IllegalArgumentException.class,()->controller.add(1,new float[5],vv(.999),false));
    }
}