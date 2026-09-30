package com.iris.assistant;

import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real session transitions with deterministic speaker vectors, no physical microphone/native model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,instrumentedPackages={"com.iris.assistant"},shadows={ContinuousSessionRecoveryTest.Speaker.class,WakeFeedbackStateTest.MemoryStore.class})
public class ContinuousSessionRecoveryTest {
 @Implements(value=VoskEngine.class,isInAndroidSdk=false)
 public static class Speaker {
  static boolean owner=true;
  @Implementation protected float[] embedRecorded(short[] pcm){return OwnerVoiceProfileTest.vv(owner?1:0);}
  @Implementation protected float[] embedEcapa(short[] pcm){return OwnerVoiceProfileTest.ev(owner?1:0);}
 }
 private ContinuousVoiceSession session;private OwnerVoiceProfile profile;private int wakes,errors;
 private final VoskEngine.WakeListener listener=new VoskEngine.WakeListener(){
  public void onWakeDetected(float[] e,float[] v){wakes++;}
  public void onError(String message){errors++;}
 };
 @Before public void setup()throws Exception{
  Speaker.owner=true;profile=OwnerVoiceProfileTest.profile();
  ProfileStore store=new ProfileStore(RuntimeEnvironment.getApplication());
  WakeChangeApproval.runApproved(()->assertTrue(store.commitOwnerEvidence(profile,store.ownerRevision())));
  session=new ContinuousVoiceSession(RuntimeEnvironment.getApplication(),new VoskEngine(),false);
 }
 @After public void cleanup()throws Exception{session.close();worker().awaitTermination(5,TimeUnit.SECONDS);}
 private ScheduledExecutorService worker()throws Exception{return (ScheduledExecutorService)get("work");}
 private Object get(String name)throws Exception{Field f=ContinuousVoiceSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(session);}
 private void set(String name,Object value)throws Exception{Field f=ContinuousVoiceSession.class.getDeclaredField(name);f.setAccessible(true);f.set(session,value);}
 @SuppressWarnings({"unchecked","rawtypes"}) private void mode(String field,String state)throws Exception{
  set(field,Enum.valueOf((Class)get("mode").getClass(),state));
 }
 private void verifyFrom(String prior)throws Exception{
  set("route",AudioRouteController.Route.PHONE);set("routeId",1);mode("beforeVerify",prior);mode("mode","VERIFY");
  AudioRing ring=(AudioRing)get("ring");ring.clear();ring.append(new short[16000],16000);
  long token=(Long)get("generation");
  Method m=ContinuousVoiceSession.class.getDeclaredMethod("verify",StreamingWakeDetector.Match.class,long.class,OwnerVoiceProfile.class,RecordedPhrase.class);m.setAccessible(true);
  worker().submit(()->{try{m.invoke(session,new StreamingWakeDetector.Match(0,12000,0,OwnerVoiceProfileTest.pattern(0)),token,profile,profile.phraseEvidence);}catch(Exception e){throw new RuntimeException(e);}}).get(5,TimeUnit.SECONDS);
  Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
 @Test public void fiveHundredWakeReplyRearmCyclesRemainUsable()throws Exception{
  for(int i=0;i<500;i++){
   session.arm(profile,listener);verifyFrom("WAKE");assertEquals(i+1,wakes);assertEquals("READY",get("mode").toString());
   session.pause();assertEquals("IDLE",get("mode").toString());assertTrue(session.usable());
  }
  assertEquals(0,errors);
 }
 @Test public void repeatedWakeDuringCommandRequiresOwnerAndKeepsCommandAfterRejection()throws Exception{
  session.arm(profile,listener);verifyFrom("WAKE");assertEquals(1,wakes);
  Speaker.owner=false;verifyFrom("COMMAND");assertEquals(1,wakes);assertEquals("COMMAND",get("mode").toString());
  Speaker.owner=true;verifyFrom("COMMAND");assertEquals(2,wakes);assertEquals("READY",get("mode").toString());
 }
 @Test public void queuedAcceptedWakeCannotFireAfterPause()throws Exception{
  session.arm(profile,listener);
  set("route",AudioRouteController.Route.PHONE);mode("mode","READY");
  long old=(Long)get("generation");session.pause();
  Method current=ContinuousVoiceSession.class.getDeclaredMethod("current",long.class,get("mode").getClass());current.setAccessible(true);
  assertFalse((Boolean)current.invoke(session,old,Enum.valueOf((Class)get("mode").getClass(),"READY")));
 }
 @Test public void stoppedCaptureWatchdogReportsOnceAndMakesSessionReplaceable()throws Exception{
  session.arm(profile,listener);((Runnable)get("watchdog")).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertFalse(session.usable());assertEquals(1,errors);
  ((Runnable)get("watchdog")).run();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,errors);
 }
}
