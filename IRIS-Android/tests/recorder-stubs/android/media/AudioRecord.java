package android.media;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1;
 public static volatile String scenario="ok";
 public static volatile CountDownLatch started=new CountDownLatch(1);
 public static final AtomicInteger released=new AtomicInteger();
 public static int getMinBufferSize(int a,int b,int c){return 1024;}
 public int getState(){return scenario.equals("bad-init")?0:STATE_INITIALIZED;}
 public void startRecording(){started.countDown();if(scenario.equals("blocked-start"))try{new CountDownLatch(1).await();}catch(InterruptedException e){throw new IllegalStateException("start interrupted");}}
 public int getRecordingState(){return RECORDSTATE_RECORDING;}
 public int read(short[] b,int o,int n,int mode){if(mode!=READ_NON_BLOCKING)throw new AssertionError("blocking read");if(scenario.equals("no-data"))return 0;if(scenario.equals("read-error"))return -6;java.util.Arrays.fill(b,o,o+n,(short)500);return n;}
 public void stop(){} public void release(){released.incrementAndGet();}
 public static class Builder {public Builder setAudioSource(int v){return this;}public Builder setAudioFormat(AudioFormat v){return this;}public Builder setBufferSizeInBytes(int v){return this;}public AudioRecord build(){return new AudioRecord();}}
}
