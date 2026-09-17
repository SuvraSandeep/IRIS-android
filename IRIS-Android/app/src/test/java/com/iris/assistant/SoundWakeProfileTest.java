package com.iris.assistant;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SoundWakeProfileTest {
    static float[][] sound(double angle){float[][] a=new float[30][24];for(int i=0;i<a.length;i++){a[i][0]=(float)Math.cos(angle);a[i][1]=(float)Math.sin(angle);}return a;}
    // Normal and quiet groups use distinct base angles (0.5 apart) so each group is internally
    // consistent (small per-take jitter) while the two groups are clearly different from each
    // other — mirroring how a real quiet-voice take genuinely differs from a normal-volume take
    // of the same sound, which is the entire reason calibration is now split per group instead
    // of pooled into one batch.
    static SoundWakeProfile profile()throws Exception{
        List<float[][]> normal=new ArrayList<>(),quiet=new ArrayList<>(),held=new ArrayList<>();
        for(int i=0;i<5;i++)normal.add(sound(i*.01));
        for(int i=0;i<5;i++)quiet.add(sound(.5+i*.01));
        for(int i=0;i<4;i++)held.add(sound(.015+i*.01));
        return SoundWakeProfile.create(normal,quiet,held);
    }
    @Test public void storedSoundRoundTripHasNoWordRequirement()throws Exception{
        SoundWakeProfile p=new SoundWakeProfile(new JSONObject(profile().data.toString()));assertTrue(p.accepts(sound(.03)));assertFalse(p.accepts(sound(2.5)));assertFalse(p.accepts(null));
        OwnerVoiceProfile voice=OwnerVoiceProfileTest.profile();JSONObject data=new JSONObject(voice.data.toString()).put("schema",5).put("soundWake",p.data).put("phrase","My own sound label");
        OwnerVoiceProfile owner=new OwnerVoiceProfile(data);assertNotNull(owner.sound);assertTrue(owner.acceptsWake(sound(.03),OwnerVoiceProfileTest.v(1),.65));assertFalse(owner.acceptsWake(sound(.03),OwnerVoiceProfileTest.v(0),.65));assertFalse(owner.acceptsWake(sound(2.5),OwnerVoiceProfileTest.v(1),.65));assertFalse(owner.acceptsWake(null,OwnerVoiceProfileTest.v(1),.65));
        data.remove("soundWake");assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(data));
    }
    @Test public void acceptsEitherGroupIndependently()throws Exception{
        // A sample matching only the QUIET group (not the normal group) must still be accepted —
        // this is the entire point of splitting calibration: either group matching is sufficient,
        // just like the voice-embedding side's WakePolicy.ownerEither.
        SoundWakeProfile p=profile();
        assertTrue("must accept a normal-group match",p.accepts(sound(.03)));
        assertTrue("must accept a quiet-group match even though it's far from the normal group",p.accepts(sound(.53)));
    }
    @Test public void correctionCannotInvalidateOwnerSoundOrExpandThreshold()throws Exception{
        SoundWakeProfile p=profile();assertThrows(IllegalArgumentException.class,()->p.withNegative(sound(.03)));
        SoundWakeProfile corrected=p.withNegative(sound(.53));assertFalse(corrected.accepts(sound(.53)));assertTrue(corrected.accepts(sound(.03)));
        assertEquals(p.normalThreshold,corrected.normalThreshold,0);assertEquals(p.quietThreshold,corrected.quietThreshold,0);
        JSONObject relaxedNormal=new JSONObject(p.data.toString()).put("normalThreshold",.30);assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(relaxedNormal));
        JSONObject overCeilingNormal=new JSONObject(p.data.toString()).put("normalThreshold",.33);assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(overCeilingNormal));
        JSONObject overCeilingQuiet=new JSONObject(p.data.toString()).put("quietThreshold",.33);assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(overCeilingQuiet));
        JSONObject unknown=new JSONObject(p.data.toString()).put("version","unrecognized");assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(unknown));
    }
    @Test public void schemaCannotSilentlyDowngradeSoundWake()throws Exception{
        JSONObject data=new JSONObject(OwnerVoiceProfileTest.profile().data.toString()).put("soundWake",profile().data);
        assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(data));
    }
    @Test public void oldPooledSchemaIsRejectedNotSilentlyAccepted()throws Exception{
        // A profile saved under the OLD pooled "examples"/"threshold" schema must fail closed
        // (throw) rather than partially load or fall back to phrase-only wake — per the standing
        // owner-identity contract, missing/incompatible sound evidence cannot silently downgrade
        // security. This also documents, for anyone debugging an upgrade, that old sound-wake
        // profiles genuinely need a fresh retrain under the split-group schema.
        List<float[][]> examples=new ArrayList<>(),held=new ArrayList<>();
        for(int i=0;i<10;i++)examples.add(sound(i*.01));for(int i=0;i<4;i++)held.add(sound(.015+i*.01));
        JSONObject oldFormat=new JSONObject().put("version",SoundPattern.VERSION).put("examples",SoundWakeProfile.array(examples))
            .put("validation",SoundWakeProfile.array(held)).put("negatives",new JSONArray()).put("threshold",SoundPattern.calibrate(examples));
        assertThrows(Exception.class,()->new SoundWakeProfile(oldFormat));
    }
}
