package com.iris.assistant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,shadows=WakeFeedbackStateTest.MemoryStore.class)
public class WakeFeedbackStateTest {
 // Storage fake: validates profile transactions and policy/undo wiring, not Android Keystore.
 @org.robolectric.annotation.Implements(SecureStore.class)
 public static class MemoryStore {
  static final java.util.Map<String,String> files=new java.util.HashMap<>();
  @org.robolectric.annotation.Implementation public static void write(android.content.Context c,String file,String text){files.put(file,text);}
  @org.robolectric.annotation.Implementation public static String read(android.content.Context c,String file,String fallback){return files.getOrDefault(file,fallback);}
 }

 @Test public void featuresAreCopiedRouteIsExplicitAndExpiryErasesEvidence(){
  float[][] pattern=WakeLearningTest.sound(1);float[] speaker=OwnerVoiceProfileTest.vv(1);
  WakeEventStore.Event e=WakeEventStore.add("PHRASE_MISMATCH","revision",null,speaker,false,pattern,AudioRouteController.Route.HEADSET);
  pattern[0][0]=0;speaker[0]=0;
  assertEquals(1,e.pattern[0][0],0);assertEquals(1,e.voskEmbedding[0],0);
  assertEquals(AudioRouteController.Route.HEADSET,e.input);assertFalse(e.accepted);
  WakeEventStore.outcome(e,"OWNER_ACCEPTED",true);assertTrue(e.accepted);
  shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2));
  assertFalse(WakeEventStore.recent().contains(e));assertEquals(0,e.pattern[0][0],0);assertEquals(0,e.voskEmbedding[0],0);
 }
 @Test public void undoRestoresTheEffectiveStrictnessToo()throws Exception{
  android.content.Context context=RuntimeEnvironment.getApplication();ProfileStore store=new ProfileStore(context);
  OwnerVoiceProfile strict=OwnerVoiceProfileTest.profileVoskOnly().withPolicy(.85);
  WakeChangeApproval.runApproved(()->assertTrue(store.commitOwnerEvidence(strict,store.ownerRevision())));
  OwnerVoiceProfile easy=strict.withPolicy(.65);
  WakeChangeApproval.runApproved(()->{assertTrue(store.commitOwnerEvidence(easy,strict.revision()));assertTrue(new AppSettings(context).setOwnerStrictness(0));});
  assertEquals(.65,new AppSettings(context).ownerThreshold(),1e-9);
  WakeChangeApproval.runApproved(()->assertTrue(store.rollbackOwner()));
  assertEquals(.85,new AppSettings(context).ownerThreshold(),1e-9);assertEquals(1,new AppSettings(context).ownerStrictness(),1e-6);
 }
}
