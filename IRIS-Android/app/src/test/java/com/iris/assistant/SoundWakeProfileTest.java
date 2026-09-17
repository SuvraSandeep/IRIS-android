package com.iris.assistant;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SoundWakeProfileTest {
    static float[][] sound(double angle){float[][] a=new float[30][24];for(int i=0;i<a.length;i++){a[i][0]=(float)Math.cos(angle);a[i][1]=(float)Math.sin(angle);}return a;}
    static SoundWakeProfile profile()throws Exception{
        List<float[][]> examples=new ArrayList<>(),held=new ArrayList<>();for(int i=0;i<10;i++)examples.add(sound(i*.01));for(int i=0;i<4;i++)held.add(sound(.015+i*.01));return SoundWakeProfile.create(examples,held);
    }
    @Test public void storedSoundRoundTripHasNoWordRequirement()throws Exception{
        SoundWakeProfile p=new SoundWakeProfile(new JSONObject(profile().data.toString()));assertTrue(p.accepts(sound(.03)));assertFalse(p.accepts(sound(1.2)));assertFalse(p.accepts(null));
        OwnerVoiceProfile voice=OwnerVoiceProfileTest.profile();JSONObject data=new JSONObject(voice.data.toString()).put("schema",5).put("soundWake",p.data).put("phrase","My own sound label");
        OwnerVoiceProfile owner=new OwnerVoiceProfile(data);assertNotNull(owner.sound);assertTrue(owner.acceptsWake(sound(.03),OwnerVoiceProfileTest.v(1),.65));assertFalse(owner.acceptsWake(sound(.03),OwnerVoiceProfileTest.v(0),.65));assertFalse(owner.acceptsWake(sound(1.2),OwnerVoiceProfileTest.v(1),.65));assertFalse(owner.acceptsWake(null,OwnerVoiceProfileTest.v(1),.65));
        data.remove("soundWake");assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(data));
    }
    @Test public void correctionCannotInvalidateOwnerSoundOrExpandThreshold()throws Exception{
        SoundWakeProfile p=profile();assertThrows(IllegalArgumentException.class,()->p.withNegative(sound(.03)));
        SoundWakeProfile corrected=p.withNegative(sound(.23));assertFalse(corrected.accepts(sound(.23)));assertTrue(corrected.accepts(sound(.03)));assertEquals(p.threshold,corrected.threshold,0);
        JSONObject relaxed=new JSONObject(p.data.toString()).put("threshold",.22);assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(relaxed));
        JSONObject unknown=new JSONObject(p.data.toString()).put("version","unrecognized");assertThrows(IllegalArgumentException.class,()->new SoundWakeProfile(unknown));
    }
    @Test public void schemaCannotSilentlyDowngradeSoundWake()throws Exception{
        JSONObject data=new JSONObject(OwnerVoiceProfileTest.profile().data.toString()).put("soundWake",profile().data);
        assertThrows(IllegalArgumentException.class,()->new OwnerVoiceProfile(data));
    }
}
