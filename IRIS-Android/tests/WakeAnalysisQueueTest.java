package com.iris.assistant;
import java.util.concurrent.*;
public class WakeAnalysisQueueTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  WakeAnalysisQueue q=new WakeAnalysisQueue();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),last=new CountDownLatch(1);
  short[] a={1},b={2},c={3};
  q.offer(a,()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  check(started.await(1,TimeUnit.SECONDS),"first did not start");
  q.offer(b,()->{throw new AssertionError("obsolete queued audio processed");});q.offer(c,last::countDown);
  check(b[0]==0,"replaced PCM retained");release.countDown();check(last.await(1,TimeUnit.SECONDS),"latest retry was dropped while busy");q.close();
  short[] d={4};q.offer(d,()->{throw new AssertionError("closed queue processed");});check(d[0]==0,"closed PCM retained");
  WakeAnalysisQueue stop=new WakeAnalysisQueue();CountDownLatch running=new CountDownLatch(1);short[] pending={5};
  stop.offer(new short[]{1},()->{running.countDown();try{Thread.sleep(10000);}catch(InterruptedException expected){}});
  check(running.await(1,TimeUnit.SECONDS),"stop setup");stop.offer(pending,()->{throw new AssertionError("stopped pending processed");});stop.close();check(pending[0]==0,"stop retained pending PCM");
  System.out.println("Passed bounded wake queue retry, replacement, shutdown and PCM erasure checks");
 }
}
