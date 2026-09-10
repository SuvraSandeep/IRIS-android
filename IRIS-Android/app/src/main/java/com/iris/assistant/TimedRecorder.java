package com.iris.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Bounded 16 kHz capture. Cancellation suppresses all stale UI callbacks. */
public final class TimedRecorder {
    private static final Handler main=new Handler(Looper.getMainLooper());
    public interface Listener {
        void onLevel(float level);
        void onComplete(short[] audio);
        void onError(String message);
    }
    private volatile boolean recording;
    private volatile int generation;
    private volatile AudioRecord microphone;
    public void record(int durationMs,Listener listener){
        if(recording)return;
        if(durationMs<500 || durationMs>30000){listener.onError("Recording duration out of range");return;}
        final int epoch=++generation;recording=true;
        new Thread(()->{
            AudioRecord mic=null;short[] result=null;String failure=null;
            try{
                int count=16000*durationMs/1000;
                int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)throw new IllegalStateException("16 kHz microphone unavailable");
                mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,6400));
                microphone=mic;
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
                if(epoch!=generation)return;
                mic.startRecording();
                if(mic.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone recording blocked");
                short[] audio=new short[count];int offset=0;
                long deadline=SystemClock.elapsedRealtime()+durationMs+3000;
                while(epoch==generation && offset<count){
                    if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("Microphone timed out");
                    int n=mic.read(audio,offset,Math.min(320,count-offset));
                    if(n<0)throw new IllegalStateException("Microphone read failed ("+n+")");
                    if(n==0)continue;
                    double energy=0;for(int i=offset;i<offset+n;i++)energy+=audio[i]*(double)audio[i];
                    float level=(float)Math.min(1,Math.sqrt(energy/n)/4000);offset+=n;
                    main.post(()->{if(epoch==generation)listener.onLevel(level);});
                }
                if(epoch==generation && offset==count)result=audio;
            }catch(Exception e){failure=e.getMessage();}
            finally{
                if(mic!=null){try{mic.stop();}catch(Exception ignored){}mic.release();}
                microphone=null;recording=false;
            }
            final short[] pcm=result;final String error=failure;
            main.post(()->{if(epoch!=generation)return;if(pcm!=null)listener.onComplete(pcm);else listener.onError(error==null?"No audio captured":error);});
        },"IRIS-TimedRecorder").start();
    }
    public void stop(){
        generation++; AudioRecord mic=microphone;
        if(mic!=null)try{mic.stop();}catch(Exception ignored){}
    }
    public boolean isRecording(){return recording;}
}
