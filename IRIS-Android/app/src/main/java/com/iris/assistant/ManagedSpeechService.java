package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

/** One microphone loop for commands and recorded wake. Wake clips carry their captured route;
 * command recognition continues to use ASR results. No transcript gate is applied to wake. */
final class ManagedSpeechService {
    interface ClipListener { void onClip(short[] pcm,AudioRouteController.Route route); }
    private ClipListener clipListener;
    void setClipListener(ClipListener listener){clipListener=listener;}
    private volatile short[] lastResultClip;
    /** The raw PCM clip that produced the most recently completed ASR result, or null if no
     *  result has completed yet this session. Cleared to zeros by the caller once consumed —
     *  callers must clone before use in fields consumed cross-thread. */
    short[] lastResultClip(){return lastResultClip;}
    private final Context context;
    private final Recognizer recognizer;
    private final boolean allowBluetooth;
    private volatile boolean running;
    private volatile AudioRecord mic;
    private Thread worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    ManagedSpeechService(Context context,Recognizer recognizer,float rate){this(context,recognizer,rate,true);}
    /** @param allowBluetooth false for always-on wake listening — forcing MODE_IN_COMMUNICATION/
     *  Bluetooth SCO the instant this starts drops a connected headset's music from full A2DP
     *  quality to call-quality narrowband, which is exactly what "music sounds bad while IRIS is
     *  awake" reports were. See AudioRouteController.request()'s matching parameter for the full
     *  explanation — this constructor just threads the same decision through to it. */
    ManagedSpeechService(Context context,Recognizer recognizer,float rate,boolean allowBluetooth){this.context=context;this.recognizer=recognizer;this.allowBluetooth=allowBluetooth;}
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
                route.request(context,mic,allowBluetooth);mic.startRecording();AudioRouteController.awaitInput(mic,AudioRouteController.Route.UNCONFIRMED);AudioRouteController.observe(mic);
                short[] frame=new short[320],raw=new short[128000];int rawCount=0,rawOffset=0;int frames=0,lastRoute=-1;
                PhraseCapture endpoint=new PhraseCapture();
                QuietAudioProcessor gain=new QuietAudioProcessor();
                long lastPcm=SystemClock.elapsedRealtime();
                while(running){
                    int n=mic.read(frame,0,frame.length);
                    if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                    if(n==0){if(SystemClock.elapsedRealtime()-lastPcm>=3000)throw new IllegalStateException("Microphone stopped supplying audio");Thread.sleep(10);continue;}
                    lastPcm=SystemClock.elapsedRealtime();
                    AudioDeviceInfo actual=mic.getRoutedDevice();int id=actual==null?-1:actual.getId();
                    if(id!=lastRoute){
                        recognizer.reset();rawCount=rawOffset=0;gain=new QuietAudioProcessor();endpoint.clear();
                        lastRoute=id;AudioRouteController.observe(mic);
                    }else if(++frames%25==0)AudioRouteController.observe(mic);
                    if(clipListener==null)for(int i=0;i<n;i++){raw[rawOffset]=frame[i];rawOffset=(rawOffset+1)%raw.length;rawCount=Math.min(raw.length,rawCount+1);}
                    if(clipListener!=null){
                        short[] clip=endpoint.add(frame,n);
                        if(clip!=null){
                            AudioRouteController.observe(mic);
                            final AudioRouteController.Route capturedRoute=AudioRouteController.observedRoute;
                            main.post(()->{if(running)clipListener.onClip(clip,capturedRoute);else java.util.Arrays.fill(clip,(short)0);});
                        }
                        continue;
                    }
                    gain.process(frame,n);
                    boolean complete=recognizer.acceptWaveForm(frame,n);
                    String output=complete?recognizer.getResult():recognizer.getPartialResult();
                    if(complete){
                        short[] clip=new short[rawCount];int start=(rawOffset-rawCount+raw.length)%raw.length;
                        for(int i=0;i<rawCount;i++)clip[i]=raw[(start+i)%raw.length];
                        output=new org.json.JSONObject(output).put("iris_audio_usable",WakePolicy.usableAudio(clip)).toString();
                        lastResultClip=clip;
                        rawCount=rawOffset=0;gain=new QuietAudioProcessor();
                    }
                    final String result=output;
                    main.post(()->{if(running){if(complete)listener.onResult(result);else listener.onPartialResult(result);}});
                }
            }catch(Exception error){main.post(()->{if(running)listener.onError(error);});}
            finally{
                AudioRecord old=mic;mic=null;if(old!=null){try{old.stop();}catch(Exception ignored){}old.release();}
                try{route.close();recognizer.close();}finally{AudioCaptureCoordinator.release(lease);}
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
