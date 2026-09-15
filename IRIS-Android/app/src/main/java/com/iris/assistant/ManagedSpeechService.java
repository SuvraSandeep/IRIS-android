package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

/** Application-owned PCM capture makes the Vosk input route observable. */
final class ManagedSpeechService {
    private final Context context;
    private final Recognizer recognizer;
    private volatile boolean running;
    private volatile AudioRecord mic;
    private Thread worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    ManagedSpeechService(Context context,Recognizer recognizer,float rate){this.context=context;this.recognizer=recognizer;}
    void startListening(RecognitionListener listener){
        running=true;
        worker=new Thread(()->{
            AudioRouteController route=new AudioRouteController(context);
            try{
                int buffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(4096,buffer*2));
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
                route.request(context,mic);mic.startRecording();AudioRouteController.observe(mic);
                short[] frame=new short[320];
                while(running){
                    int n=mic.read(frame,0,frame.length);
                    if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                    if(n==0)continue;
                    AudioRouteController.observe(mic);
                    boolean complete=recognizer.acceptWaveForm(frame,n);
                    String result=complete?recognizer.getResult():recognizer.getPartialResult();
                    main.post(()->{if(running){if(complete)listener.onResult(result);else listener.onPartialResult(result);}});
                }
            }catch(Exception error){main.post(()->{if(running)listener.onError(error);});}
            finally{
                AudioRecord old=mic;mic=null;if(old!=null){try{old.stop();}catch(Exception ignored){}old.release();}
                route.close();recognizer.close();
            }
        },"IRIS-PCM");worker.start();
    }
    void stop(){
        running=false;
        AudioRecord old=mic;if(old!=null)try{old.stop();}catch(Exception ignored){}
        if(worker!=null&&worker!=Thread.currentThread())try{worker.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(worker!=null&&worker.isAlive())throw new IllegalStateException("Recorder is still stopping");
    }
    void shutdown(){stop();}
}
