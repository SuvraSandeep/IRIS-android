package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

/** One microphone loop for commands and recorded wake. Wake clips carry their captured route;
 * command recognition continues to use ASR results. No transcript gate is applied to wake. */
final class ManagedSpeechService {
    interface FrameListener {void onFrames(short[] pcm,int count,AudioRouteController.Route route,int routeId);default void onHealth(String status){} }
    private FrameListener frameListener;
    void setFrameListener(FrameListener listener){frameListener=listener;}
    interface ClipListener { void onClip(short[] pcm,AudioRouteController.Route route); }
    private ClipListener clipListener;
    private Runnable readyListener;
    void setReadyListener(Runnable listener){readyListener=listener;}
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
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            Object lease=null;long acquireUntil=SystemClock.elapsedRealtime()+2500;
            while(running&&lease==null&&SystemClock.elapsedRealtime()<acquireUntil){
                lease=AudioCaptureCoordinator.acquire();if(lease==null)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
            AudioRouteController route=new AudioRouteController(context);
            try{
                if(!running)return;
                if(lease==null)throw new IllegalStateException("Microphone is in use by training");
                int buffer=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(4096,buffer*2));
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
                route.request(context,mic,allowBluetooth);mic.startRecording();AudioRouteController.awaitInput(mic,AudioRouteController.Route.UNCONFIRMED);AudioRouteController.observe(mic);
                main.post(()->{if(running&&readyListener!=null)readyListener.run();});
                short[] frame=new short[320],raw=new short[128000];int rawCount=0,rawOffset=0;int frames=0,lastRoute=-1;
                PhraseCapture endpoint=new PhraseCapture();
                QuietAudioProcessor gain=new QuietAudioProcessor();
                long lastPcm=SystemClock.elapsedRealtime(),lastPartialAt=0,lastHealth=0;String lastPartial="",health="";
                while(running){
                    int n=mic.read(frame,0,frame.length,AudioRecord.READ_NON_BLOCKING);
                    if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                    if(n==0){if(SystemClock.elapsedRealtime()-lastPcm>=3000)throw new IllegalStateException("Microphone stopped supplying audio");Thread.sleep(10);continue;}
                    lastPcm=SystemClock.elapsedRealtime();
                    AudioDeviceInfo actual=mic.getRoutedDevice();int id=actual==null?-1:actual.getId();
                    if(id!=lastRoute){
                        if(recognizer!=null)recognizer.reset();rawCount=rawOffset=0;gain=new QuietAudioProcessor();endpoint.clear();
                        lastRoute=id;AudioRouteController.observe(mic);
                    }else if(++frames%25==0)AudioRouteController.observe(mic);
                    if(frameListener!=null){
                        long now=SystemClock.elapsedRealtime();
                        if(now-lastHealth>=2000){
                            lastHealth=now;String state="PCM_ACTIVE";
                            if(Build.VERSION.SDK_INT>=29)try{
                                AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                                for(AudioRecordingConfiguration config:manager.getActiveRecordingConfigurations())
                                    if(config.getClientAudioSessionId()==mic.getAudioSessionId()&&config.isClientSilenced())state="MIC_SILENCED_BY_ANDROID";
                            }catch(Exception ignored){}
                            if(!state.equals(health)){health=state;frameListener.onHealth(state);}
                        }
                        frameListener.onFrames(frame,n,AudioRouteController.observedRoute,id);continue;
                    }
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
                    if(!complete){long now=SystemClock.elapsedRealtime();if(output.equals(lastPartial)||now-lastPartialAt<150)continue;lastPartial=output;lastPartialAt=now;}
                    else lastPartial="";
                    final String result=output;
                    main.post(()->{if(running){if(complete)listener.onResult(result);else listener.onPartialResult(result);}});
                }
            }catch(Exception error){main.post(()->{if(running)listener.onError(error);});}
            finally{
                AudioRecord old=mic;mic=null;if(old!=null){try{old.stop();}catch(Exception ignored){}old.release();}
                try{route.close();if(recognizer!=null)recognizer.close();}finally{AudioCaptureCoordinator.release(lease);}
            }
        },"IRIS-PCM");worker.start();
    }
    void stop(){
        // Non-blocking reads let the capture worker release its own recorder. Never join it
        // on the UI thread or close a recorder concurrently with native decoding.
        running=false;
    }
    boolean stopped(){return worker==null||!worker.isAlive();}
    void awaitStopped(){
        if(worker!=null&&worker!=Thread.currentThread())try{worker.join();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Capture cleanup interrupted",e);}
    }
    void shutdown(){stop();}
}
