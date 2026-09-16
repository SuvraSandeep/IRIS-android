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
            Object lease=AudioCaptureCoordinator.acquire();
            AudioRouteController route=new AudioRouteController(context);
            try{
                if(lease==null)throw new IllegalStateException("Microphone is in use by training");
                int buffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(4096,buffer*2));
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
                route.request(context,mic);mic.startRecording();AudioRouteController.observe(mic);
                short[] frame=new short[320],raw=new short[80000];int rawCount=0,rawOffset=0;int frames=0,lastRoute=-1;
                QuietAudioProcessor gain=new QuietAudioProcessor();
                while(running){
                    int n=mic.read(frame,0,frame.length);
                    if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                    if(n==0)continue;
                    if(++frames%25==0){
                        AudioDeviceInfo actual=mic.getRoutedDevice();int id=actual==null?-1:actual.getId();
                        if(lastRoute!=-1&&id!=lastRoute){recognizer.reset();rawCount=rawOffset=0;gain=new QuietAudioProcessor();}lastRoute=id;
                        AudioRouteController.observe(mic);
                    }
                    for(int i=0;i<n;i++){raw[rawOffset]=frame[i];rawOffset=(rawOffset+1)%raw.length;rawCount=Math.min(raw.length,rawCount+1);}
                    gain.process(frame,n);
                    boolean complete=recognizer.acceptWaveForm(frame,n);
                    String output=complete?recognizer.getResult():recognizer.getPartialResult();
                    if(complete){
                        short[] clip=new short[rawCount];int start=(rawOffset-rawCount+raw.length)%raw.length;
                        for(int i=0;i<rawCount;i++)clip[i]=raw[(start+i)%raw.length];
                        output=new org.json.JSONObject(output).put("iris_audio_usable",WakePolicy.usableAudio(clip)).toString();
                        rawCount=rawOffset=0;gain=new QuietAudioProcessor();
                    }
                    final String result=output;
                    main.post(()->{if(running){if(complete)listener.onResult(result);else listener.onPartialResult(result);}});
                }
            }catch(Exception error){main.post(()->{if(running)listener.onError(error);});}
            finally{
                AudioRecord old=mic;mic=null;if(old!=null){try{old.stop();}catch(Exception ignored){}old.release();}
                route.close();recognizer.close();AudioCaptureCoordinator.release(lease);
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
