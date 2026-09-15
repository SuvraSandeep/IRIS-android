package com.iris.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded PCM capture. Each attempt delivers at most one terminal callback. */
public final class TimedRecorder {
    private static final int SAMPLE_RATE=16000,FRAME_SIZE=512;
    private static final Handler main=new Handler(Looper.getMainLooper());
    public interface Listener {
        void onLevel(float normalizedLevel);
        void onComplete(short[] audio);
        void onError(String message);
    }
    private final android.content.Context context;
    public TimedRecorder(){context=null;}
    public TimedRecorder(android.content.Context context){this.context=context.getApplicationContext();}
    private volatile Attempt active;
    private static final class Attempt {
        final AtomicBoolean terminal=new AtomicBoolean();
        volatile boolean cancelled;
        Thread worker;
        Runnable watchdog;
    }
    public synchronized void record(int durationMs,Listener listener){
        if(active!=null){main.post(()->listener.onError("Previous microphone capture is still stopping. Please try again."));return;}
        if(durationMs<100||durationMs>120000){main.post(()->listener.onError("Invalid recording duration."));return;}
        final Attempt attempt=new Attempt();active=attempt;
        attempt.watchdog=()->{
            if(attempt.terminal.compareAndSet(false,true)){
                attempt.cancelled=true;attempt.worker.interrupt();
                listener.onError("Microphone timed out. Check microphone access and the selected headset, then retry.");
            }
        };
        attempt.worker=new Thread(()->capture(attempt,durationMs,listener),"IRIS-TimedRecorder");
        main.postDelayed(attempt.watchdog,durationMs+8000L);
        attempt.worker.start();
    }
    private void capture(Attempt attempt,int durationMs,Listener listener){
        AudioRecord mic=null;AudioRouteController route=null;
        short[] result=null;String error=null;
        try{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            route=new AudioRouteController(context);
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("Unsupported microphone format");
            mic=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min*2,FRAME_SIZE*8)).build();
            if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
            if(attempt.cancelled)return;
            if(context!=null)route.request(context,mic);
            if(attempt.cancelled)return;
            mic.startRecording();
            if(mic.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Android did not start microphone recording");
            AudioRouteController.observe(mic);
            short[] audio=new short[SAMPLE_RATE*durationMs/1000],frame=new short[FRAME_SIZE];int offset=0;
            RecordingDeadline deadline=new RecordingDeadline(SystemClock.elapsedRealtime(),durationMs);
            long lastLevel=0;
            while(!attempt.cancelled && offset<audio.length){
                long now=SystemClock.elapsedRealtime();
                if(deadline.expired(now))throw new IllegalStateException("Microphone stopped supplying audio. Reconnect the headset or select Phone microphone and retry");
                int n=mic.read(frame,0,Math.min(frame.length,audio.length-offset),AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IllegalStateException("Microphone read failed ("+n+")");
                if(n==0){Thread.sleep(10);continue;}
                deadline.received(now);System.arraycopy(frame,0,audio,offset,n);offset+=n;
                if(now-lastLevel>=100){
                    lastLevel=now;final float level=rms(frame,n);
                    main.post(()->{if(active==attempt&&!attempt.terminal.get()&&!attempt.cancelled)listener.onLevel(level);});
                }
            }
            if(!attempt.cancelled)result=audio;
        }catch(Exception failure){error="Recording failed: "+failure.getMessage();}
        finally{
            if(mic!=null){try{mic.stop();}catch(Exception ignored){}try{mic.release();}catch(Exception ignored){}}
            if(route!=null)route.close();
            synchronized(this){if(active==attempt)active=null;}
        }
        final short[] captured=result;final String message=error;
        main.post(()->{
            if(!attempt.cancelled&&attempt.terminal.compareAndSet(false,true)){
                main.removeCallbacks(attempt.watchdog);
                if(captured!=null)listener.onComplete(captured);
                else listener.onError(message==null?"No complete recording was captured.":message);
            }
        });
    }
    public synchronized void stop(){
        Attempt attempt=active;if(attempt==null)return;
        attempt.cancelled=true;attempt.terminal.set(true);main.removeCallbacks(attempt.watchdog);attempt.worker.interrupt();
    }
    public boolean isRecording(){Attempt attempt=active;return attempt!=null&&!attempt.cancelled&&!attempt.terminal.get();}
    private static float rms(short[] samples,int length){double sum=0;for(int i=0;i<length;i++)sum+=samples[i]*(double)samples[i];return(float)Math.min(1,Math.sqrt(sum/Math.max(1,length))/8000);}
}
