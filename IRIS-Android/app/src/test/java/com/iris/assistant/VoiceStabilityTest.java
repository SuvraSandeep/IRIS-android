package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class VoiceStabilityTest {
 @Test public void readinessDoesNotWaitForModelLoadOrInferenceLock()throws Exception{
  EcapaEmbedding model=new EcapaEmbedding();Field field=EcapaEmbedding.class.getDeclaredField("lock");field.setAccessible(true);Object lock=field.get(model);
  CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
  Thread worker=new Thread(()->{synchronized(lock){acquired.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});worker.start();
  ExecutorService ui=Executors.newSingleThreadExecutor();
  try{assertTrue(acquired.await(1,TimeUnit.SECONDS));assertFalse(ui.submit(model::isReady).get(1,TimeUnit.SECONDS));}
  finally{release.countDown();worker.join(1000);ui.shutdownNow();}
 }
 @Test public void taskFailureNotifiesRecoveryOnceInsteadOfDisappearingInFuture(){
  AtomicInteger recovered=new AtomicInteger();RuntimeException failure=new IllegalStateException("fault");
  VoiceTaskGuard.run(()->{throw failure;},error->{assertSame(failure,error);recovered.incrementAndGet();});
  assertEquals(1,recovered.get());VoiceTaskGuard.run(()->{},error->fail("Successful task must not restart"));
 }
 @Test public void fatalErrorsStillReachCrashHandling(){
  AssertionError error=new AssertionError("fatal");
  assertSame(error,assertThrows(AssertionError.class,()->VoiceTaskGuard.run(()->{throw error;},ignored->fail("Fatal error swallowed"))));
 }
 @Test public void reportOmitsExceptionMessagesAndRetainsStackLocations(){
  RuntimeException error=new RuntimeException("secret dictated command",new IllegalStateException("private contact"));
  String report=CrashSummary.describe(error);assertFalse(report.contains("secret dictated command"));assertFalse(report.contains("private contact"));
  assertTrue(report.contains("RuntimeException"));assertTrue(report.contains("VoiceStabilityTest"));
 }
 @Test public void healthHistoryIsBounded(){
  for(int i=0;i<100;i++)VoiceHealth.event("CHECK","sequence="+i);
  String report=VoiceHealth.snapshot();assertFalse(report.contains("sequence=0\n"));assertTrue(report.contains("sequence=99"));assertTrue(report.split("\n").length<=49);
 }
}
