package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

/** Application-owned PCM capture makes the Vosk input route observable.
 *
 *  Redesigned per WAKE-TRAINING-REDESIGN.md: the old ClipListener "learned-sound mode" (which
 *  bypassed the ASR decoder/pronunciation gate entirely, feeding raw clips only to the
 *  now-deleted DTW sound-pattern matcher via a SEPARATE, unguarded audio-read path) is removed.
 *  The unified design still needs the raw PCM clip corresponding to a completed ASR result —
 *  to run the dedicated ECAPA-TDNN embedding and Silero VAD trim on it — but that clip is now
 *  exposed via lastResultClip() at the moment a result completes, reusing the SAME single
 *  audio-read loop the ASR path already runs, rather than a second parallel read path with its
 *  own device-route/reset handling that had to be kept in sync by hand (this was the root
 *  cause of a real Bluetooth-audio-quality regression fixed earlier this project). */
final class ManagedSpeechService {
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
                route.request(context,mic,allowBluetooth);mic.startRecording();AudioRouteController.observe(mic);
                short[] frame=new short[320],raw=new short[128000];int rawCount=0,rawOffset=0;int frames=0,lastRoute=-1;
                QuietAudioProcessor gain=new QuietAudioProcessor();
                long lastPcm=SystemClock.elapsedRealtime();
                while(running){
                    int n=mic.read(frame,0,frame.length);
                    if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                    if(n==0){if(SystemClock.elapsedRealtime()-lastPcm>=3000)throw new IllegalStateException("Microphone stopped supplying audio");Thread.sleep(10);continue;}
                    lastPcm=SystemClock.elapsedRealtime();
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
