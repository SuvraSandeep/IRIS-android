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
}
