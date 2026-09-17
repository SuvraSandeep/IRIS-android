package com.iris.assistant;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerVoiceProfileTest {
    static float[] v(double cosine){float[] v=new float[128];v[0]=(float)cosine;v[1]=(float)Math.sqrt(1-cosine*cosine);return v;}
    static OwnerVoiceProfile profile()throws Exception {
        List<float[]> takes=Arrays.asList(v(1),v(.999),v(.998),v(.997),v(.996));
        return OwnerVoiceProfile.create("Hello Iris","a".repeat(64),takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),.65);
    }
    @Test public void roundTripPreservesDecisions()throws Exception {
        OwnerVoiceProfile p=profile(),q=new OwnerVoiceProfile(new JSONObject(p.data.toString()));
        assertEquals(p.revision(),q.revision());assertTrue(q.accepts(v(1),.65));assertFalse(q.accepts(v(0),.65));
        assertFalse(q.accepts(new float[128],.65));assertFalse(q.accepts(new float[3],.65));
    }
    @Test public void negativeCorrectionMustProtectValidation()throws Exception {
        OwnerVoiceProfile p=profile();assertThrows(IllegalArgumentException.class,()->p.withNegative(v(1)));
        OwnerVoiceProfile revised=p.withNegative(v(.70));assertTrue(revised.accepts(v(1),.65));assertFalse(revised.accepts(v(.70),.65));
        assertTrue(p.accepts(v(.70),.65));assertNotEquals(p.revision(),revised.revision());
    }
    @Test public void malformedPackagesCannotBeActivated()throws Exception {
        JSONObject wrong=new JSONObject(profile().data.toString()).put("modelHash","unknown");assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(wrong));
        JSONObject dim=new JSONObject(profile().data.toString()).put("voiceprint",new JSONArray("[1,2]"));assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(dim));
        JSONObject threshold=new JSONObject(profile().data.toString()).put("ownerThreshold",1.05);assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(threshold));
        JSONObject missing=new JSONObject(profile().data.toString()).put("validation",new JSONArray());assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(missing));
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
        List<float[]> takes=Arrays.asList(v(1),v(.999),v(.998),v(.997),v(.996));
        OwnerVoiceProfile.create("Hello Iris","a".repeat(64),takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),maxStrictness);
        // A threshold genuinely outside the intended [.65,.85] range must still be rejected.
        assertThrows(IllegalArgumentException.class,()->OwnerVoiceProfile.create("Hello Iris","a".repeat(64),takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),.86));
        assertThrows(IllegalArgumentException.class,()->OwnerVoiceProfile.create("Hello Iris","a".repeat(64),takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),.64));
    }
    static float[][] soundExample(){float[][] t=new float[12][SoundPattern.BANDS];for(int f=0;f<12;f++)t[f][0]=1f;return t;}
    static OwnerVoiceProfile profileWithSound()throws Exception {
        List<float[][]> normal=new ArrayList<>(),quiet=new ArrayList<>(),val=new ArrayList<>();
        for(int i=0;i<5;i++)normal.add(soundExample());for(int i=0;i<5;i++)quiet.add(soundExample());for(int i=0;i<4;i++)val.add(soundExample());
        JSONObject withSound=new JSONObject(profile().data.toString());
        withSound.put("schema",5).put("soundWake",SoundWakeProfile.create(normal,quiet,val).data);
        return new OwnerVoiceProfile(withSound);
    }
    @Test public void headsetRouteIsAbsentUntilExplicitlyAdded()throws Exception {
        OwnerVoiceProfile phone=profileWithSound();
        assertNull(phone.headset);
        assertFalse(phone.acceptsHeadset(v(1),.65));
        assertFalse(phone.acceptsWakeHeadset(soundExample(),v(1),.65));
    }
    @Test public void addingHeadsetRoutePreservesPhoneRouteAndBumpsSchema()throws Exception {
        OwnerVoiceProfile phone=profileWithSound();
        List<float[]> takes=Arrays.asList(v(1),v(.999),v(.998),v(.997),v(.996));
        List<float[][]> normalEx=new ArrayList<>(),quietEx=new ArrayList<>(),val=new ArrayList<>();
        for(int i=0;i<5;i++)normalEx.add(soundExample());for(int i=0;i<5;i++)quietEx.add(soundExample());for(int i=0;i<4;i++)val.add(soundExample());
        OwnerVoiceProfile withHeadset=phone.withHeadset(takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),normalEx,quietEx,val);
        assertNotNull(withHeadset.headset);
        assertTrue(withHeadset.data.getInt("schema")>=6);
        // Phone-route identity and matching survive adding a headset route untouched.
        assertNotNull(withHeadset.normal());assertNotNull(withHeadset.quiet());
        assertTrue(withHeadset.accepts(v(1),.65));assertTrue(withHeadset.acceptsWake(soundExample(),v(1),.65));
        // Headset-route matching works on its own vectors.
        assertTrue(withHeadset.acceptsHeadset(v(1),.65));assertTrue(withHeadset.acceptsWakeHeadset(soundExample(),v(1),.65));
        assertNotEquals(phone.revision(),withHeadset.revision());
    }
    @Test public void headsetRouteSurvivesJsonRoundTrip()throws Exception {
        OwnerVoiceProfile phone=profileWithSound();
        List<float[]> takes=Arrays.asList(v(1),v(.999),v(.998),v(.997),v(.996));
        List<float[][]> normalEx=new ArrayList<>(),quietEx=new ArrayList<>(),val=new ArrayList<>();
        for(int i=0;i<5;i++)normalEx.add(soundExample());for(int i=0;i<5;i++)quietEx.add(soundExample());for(int i=0;i<4;i++)val.add(soundExample());
        OwnerVoiceProfile withHeadset=phone.withHeadset(takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),normalEx,quietEx,val);
        OwnerVoiceProfile roundTrip=new OwnerVoiceProfile(new JSONObject(withHeadset.data.toString()));
        assertNotNull(roundTrip.headset);assertTrue(roundTrip.acceptsHeadset(v(1),.65));
    }
    @Test public void removingHeadsetRouteLeavesPhoneRouteIntact()throws Exception {
        OwnerVoiceProfile phone=profileWithSound();
        List<float[]> takes=Arrays.asList(v(1),v(.999),v(.998),v(.997),v(.996));
        List<float[][]> normalEx=new ArrayList<>(),quietEx=new ArrayList<>(),val=new ArrayList<>();
        for(int i=0;i<5;i++)normalEx.add(soundExample());for(int i=0;i<5;i++)quietEx.add(soundExample());for(int i=0;i<4;i++)val.add(soundExample());
        OwnerVoiceProfile withHeadset=phone.withHeadset(takes,takes,Arrays.asList(v(1),v(.999),v(.998),v(.997)),normalEx,quietEx,val);
        OwnerVoiceProfile removed=withHeadset.withoutHeadset();
        assertNull(removed.headset);assertNotNull(removed.normal());assertTrue(removed.accepts(v(1),.65));
        assertNotEquals(withHeadset.revision(),removed.revision());
    }
}
