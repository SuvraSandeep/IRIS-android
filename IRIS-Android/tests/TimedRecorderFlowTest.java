package com.iris.assistant;
import android.media.AudioRecord;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Runs the actual TimedRecorder against deterministic platform fakes; not a hardware test. */
public final class TimedRecorderFlowTest {
 static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static final class Result implements TimedRecorder.Listener {
  final CountDownLatch done=new CountDownLatch(1);final AtomicInteger success=new AtomicInteger(),errors=new AtomicInteger();volatile int length;
  public void onLevel(float value){}
  public void onComplete(short[] audio){length=audio.length;success.incrementAndGet();done.countDown();}
  public void onError(String message){errors.incrementAndGet();done.countDown();}
 }
 static void reset(String scenario){AudioRecord.scenario=scenario;AudioRecord.started=new CountDownLatch(1);AudioRecord.released.set(0);}
 public static void main(String[] args)throws Exception{
  for(String scenario:new String[]{"ok","no-data","read-error","bad-init","blocked-start"}){
   reset(scenario);Result result=new Result();TimedRecorder recorder=new TimedRecorder();recorder.record(500,result);
   check(result.done.await(3,TimeUnit.SECONDS),scenario+" failed to terminate");Thread.sleep(120);
   check(result.success.get()+result.errors.get()==1,scenario+" duplicate terminal callback");
   check(AudioRecord.released.get()==1,scenario+" recorder leaked");
   if(scenario.equals("ok")){check(result.success.get()==1,"missing PCM completion");check(result.length==8000,"wrong sample length");check(recorder.capturedRouteType()==AudioRouteController.Route.HEADSET,"captured route lost after cleanup");check(AudioRouteController.observedRoute==AudioRouteController.Route.UNCONFIRMED,"test must exercise cleanup before callback");}
   else check(result.errors.get()==1,scenario+" should fail");
  }
  reset("blocked-start");Result cancelled=new Result();TimedRecorder recorder=new TimedRecorder();recorder.record(500,cancelled);
  check(AudioRecord.started.await(1,TimeUnit.SECONDS),"cancel setup did not start");recorder.stop();Thread.sleep(150);
  check(cancelled.success.get()+cancelled.errors.get()==0,"cancelled attempt delivered terminal callback");check(AudioRecord.released.get()==1,"cancel leaked recorder");
  System.out.println("Passed "+checks+" real recorder flow checks with platform fakes");
 }
}
