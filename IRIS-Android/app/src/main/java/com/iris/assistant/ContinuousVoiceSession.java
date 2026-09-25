package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.vosk.android.RecognitionListener;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One AudioRecord while armed/handling offline commands. Native decoding stays off capture/UI.
 * A detected sound is only a candidate. Owner identity and the live profile revision gate wake.
 * PCM lives in an eight-second RAM ring, is erased on route changes/stop, and is never persisted. */
final class ContinuousVoiceSession implements AutoCloseable {
    private enum Mode {IDLE,WAKE,VERIFY,READY,COMMAND,CLOSED}
    private final Context context;private final VoskEngine owner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService work=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"IRIS-VoiceSession"));
    private final Object lock=new Object();private final AudioRing ring=new AudioRing(16000*8);
    private final AtomicBoolean draining=new AtomicBoolean();
    private final ManagedSpeechService capture;
    private Mode mode=Mode.IDLE;private long generation,detectorOrigin,commandCursor,commandStart;
    private int routeId=-1;private AudioRouteController.Route route=AudioRouteController.Route.UNCONFIRMED;
    private OwnerVoiceProfile profile;private RecordedPhrase phrase;private StreamingWakeDetector detector;
    private VoskEngine.WakeListener wakeListener;private VoskEngine.SttListener commandListener;
    private VoskEngine.Decoder decoder;private String lastPartial="";private long lastPartialAt;
    private long startedAt=SystemClock.elapsedRealtime();private volatile boolean ready,failed,inputSilenced;private long exposureSamples;
    ContinuousVoiceSession(Context context,VoskEngine owner){
        this.context=context.getApplicationContext();this.owner=owner;
        capture=new ManagedSpeechService(this.context,null,16000,true);
        capture.setReadyListener(()->{ready=true;note("MIC_READY","Input stable; startup "+(SystemClock.elapsedRealtime()-startedAt)+"ms");});
        capture.setFrameListener(new ManagedSpeechService.FrameListener(){
            public void onFrames(short[] pcm,int n,AudioRouteController.Route input,int id){frames(pcm,n,input,id);}
            public void onHealth(String status){inputSilenced="MIC_SILENCED_BY_ANDROID".equals(status);note(status,"Microphone capture state");
                if(inputSilenced){VoskEngine.SttListener c;VoskEngine.WakeListener w;synchronized(lock){c=commandListener;w=wakeListener;mode=Mode.IDLE;generation++;ring.clear();}execute(ContinuousVoiceSession.this::closeDecoder);
                    main.post(()->{if(c!=null)c.onError("Android silenced the microphone");else if(w!=null)w.onError("Android silenced the microphone");});}}
        });
        capture.startListening(new RecognitionListener(){
            public void onPartialResult(String s){}public void onResult(String s){}public void onFinalResult(String s){}public void onTimeout(){}
            public void onError(Exception e){failed=true;note("MIC_UNAVAILABLE",String.valueOf(e.getMessage()));VoskEngine.WakeListener w;VoskEngine.SttListener c;synchronized(lock){w=wakeListener;c=commandListener;}
                if(c!=null)c.onError(e.getMessage());else if(w!=null)w.onError(e.getMessage());}
        });
    }
    boolean usable(){synchronized(lock){return mode!=Mode.CLOSED&&!failed;}}
    boolean readyForCommand(){synchronized(lock){return mode==Mode.READY&&ready&&!failed;}}
    void arm(OwnerVoiceProfile saved,VoskEngine.WakeListener listener){
        synchronized(lock){if(mode==Mode.CLOSED)return;generation++;mode=Mode.WAKE;profile=saved;wakeListener=listener;commandListener=null;ring.clear();detector=null;phrase=null;routeId=-1;}
        execute(this::closeDecoder);note("WAKE_ARMED","Streaming phrase + owner; waiting for real PCM");
    }
    void pause(){synchronized(lock){if(mode==Mode.CLOSED)return;generation++;mode=Mode.IDLE;commandListener=null;detector=null;ring.clear();}execute(this::closeDecoder);}
    private void frames(short[] pcm,int n,AudioRouteController.Route input,int id){
        StreamingWakeDetector.Match candidate=null;long epoch=0;OwnerVoiceProfile saved=null;RecordedPhrase evidence=null;
        synchronized(lock){
            if(mode==Mode.CLOSED||mode==Mode.IDLE||inputSilenced)return;
            if(id!=routeId||input!=route){
                boolean interrupted=mode==Mode.COMMAND||mode==Mode.READY||mode==Mode.VERIFY;
                generation++;routeId=id;route=input;ring.clear();detector=null;phrase=null;
                note("INPUT_CHANGED",input+" device="+id);
                if(interrupted){Mode before=mode;mode=Mode.IDLE;VoskEngine.SttListener c=commandListener;VoskEngine.WakeListener w=wakeListener;
                    main.post(()->{if(before==Mode.COMMAND&&c!=null)c.onError("Microphone changed; repeat on the new input");else if(w!=null)w.onError("Microphone changed during owner verification");});return;}
            }
            if(mode==Mode.WAKE&&detector==null){
                if(input==AudioRouteController.Route.UNCONFIRMED)return;
                phrase=input==AudioRouteController.Route.HEADSET?(profile.headset==null?null:profile.headset.phraseEvidence):profile.phraseEvidence;
                if(phrase==null){mode=Mode.IDLE;VoskEngine.WakeListener w=wakeListener;main.post(()->w.onError("Add a headset profile or select the phone microphone"));return;}
                List<float[][]> examples=new ArrayList<>(phrase.samples);examples.addAll(phrase.positives);
                detector=new StreamingWakeDetector(examples,phrase.threshold);detectorOrigin=ring.end();
            }
            if(mode==Mode.WAKE||mode==Mode.VERIFY){exposureSamples+=n;if(exposureSamples>=320000){long measured=exposureSamples;exposureSamples=0;execute(()->WakeDiagnostics.exposure(context,measured));}}
            ring.append(pcm,n);
            if(mode==Mode.WAKE){candidate=detector.add(pcm,n);if(candidate!=null){
                candidate=new StreamingWakeDetector.Match(candidate.start+detectorOrigin,candidate.end+detectorOrigin,candidate.distance);
                mode=Mode.VERIFY;epoch=generation;saved=profile;evidence=phrase;}}
            else if(mode==Mode.COMMAND&&draining.compareAndSet(false,true))execute(this::drainCommand);
        }
        if(candidate!=null){final StreamingWakeDetector.Match match=candidate;final long token=epoch;final OwnerVoiceProfile p=saved;final RecordedPhrase ph=evidence;
            execute(()->verify(match,token,p,ph,0));}
    }
    private boolean current(long token,Mode expected){synchronized(lock){return generation==token&&mode==expected&&!inputSilenced;}}
    private void verify(StreamingWakeDetector.Match match,long token,OwnerVoiceProfile p,RecordedPhrase ph,int attempt){
        if(!current(token,Mode.VERIFY))return;
        float[][] pattern=null;float[] vosk=null,ecapa=null;String reason="ANALYSIS_ERROR";AudioRouteController.Route input;
        long begin=SystemClock.elapsedRealtime();
        synchronized(lock){input=route;}
        try{
            short[] phrasePcm;
            synchronized(lock){phrasePcm=ring.slice(Math.max(ring.first(),match.start-320),Math.min(ring.end(),match.end+320));}
            short[] phraseContext=SoundPattern.boundedContext(phrasePcm);
            try{pattern=SoundPattern.extract(phraseContext);}finally{Arrays.fill(phrasePcm,(short)0);Arrays.fill(phraseContext,(short)0);}
            if(!ph.accepts(pattern)){reject(token,"PHRASE_MISMATCH",pattern,null,null,input,match.distance);return;}
            short[] speech;
            synchronized(lock){speech=ring.slice(Math.max(ring.first(),match.start-1600),Math.min(ring.end(),match.end+(attempt==0?1600:32000)));}
            short[] speakerContext=SoundPattern.boundedContext(speech);
            try{synchronized(owner){vosk=owner.embedRecorded(speakerContext);if(!WakePolicy.isAbsent(input==AudioRouteController.Route.HEADSET?p.headset.ecapaCentroid():p.ecapaCentroid()))ecapa=owner.embedEcapa(speakerContext);}}
            finally{Arrays.fill(speech,(short)0);Arrays.fill(speakerContext,(short)0);}
            boolean accepted=input==AudioRouteController.Route.HEADSET?p.acceptsHeadset(ecapa,vosk,p.threshold()):p.accepts(ecapa,vosk,p.threshold());
            reason=accepted?"OWNER_ACCEPTED":!WakePolicy.owner(vosk,vosk,.99)?"SPEAKER_EVIDENCE":"OWNER_REJECTED";
            if(!accepted&&attempt<3){final int next=attempt+1;work.schedule(()->verify(match,token,p,ph,next),300,TimeUnit.MILLISECONDS);return;}
            if(!accepted){reject(token,reason,pattern,ecapa,vosk,input,match.distance);return;}
            if(!p.revision().equals(new ProfileStore(context).ownerRevision())){reject(token,"PROFILE_CHANGED",pattern,ecapa,vosk,input,match.distance);return;}
            final float[] v=vosk,e=ecapa;final float[][] sound=pattern;
            synchronized(lock){if(!current(token,Mode.VERIFY))return;mode=Mode.READY;commandStart=match.end;}
            note("OWNER_ACCEPTED",input+"; processing="+(SystemClock.elapsedRealtime()-begin)+"ms; extra-context retries="+attempt);
            main.post(()->{if(!current(token,Mode.READY))return;owner.streamingOutcome(p,sound,e,v,input,true,"OWNER_ACCEPTED",match.distance);wakeListener.onWakeDetected(e,v);});
        }catch(Exception error){reject(token,reason,pattern,ecapa,vosk,input,match.distance);}
    }
    private void reject(long token,String reason,float[][] pattern,float[] ecapa,float[] vosk,AudioRouteController.Route input,double distance){
        synchronized(lock){if(!current(token,Mode.VERIFY))return;mode=Mode.WAKE;detector=null;}
        note(reason,input+"; streaming distance="+distance);
        main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}
            owner.streamingOutcome(profile,pattern,ecapa,vosk,input,false,reason,distance);wakeListener.onRejected(reason);});
    }
    void commands(VoskEngine commandEngine,VoskEngine.SttListener listener){
        final long token;
        synchronized(lock){if(mode==Mode.CLOSED)return;boolean handoff=mode==Mode.READY;generation++;token=generation;mode=Mode.COMMAND;commandListener=listener;commandCursor=handoff?commandStart:ring.end();}
        execute(()->{
            closeDecoder();if(!current(token,Mode.COMMAND))return;
            try{decoder=commandEngine.decoder();lastPartial="";lastPartialAt=0;
                main.post(()->{if(current(token,Mode.COMMAND))listener.onReady();});drainCommand();}
            catch(Exception error){commandError(token,"Cannot start command decoder: "+error.getMessage());}
        });
    }
    private void drainCommand(){
        try{
            final long token;final VoskEngine.SttListener listener;short[] pcm;
            synchronized(lock){token=generation;listener=commandListener;if(mode!=Mode.COMMAND||decoder==null)return;long end=ring.end();if(end==commandCursor)return;
                pcm=ring.slice(commandCursor,end);commandCursor=end;}
            String output;boolean complete;
            try{complete=decoder.recognizer.acceptWaveForm(pcm,pcm.length);output=complete?decoder.recognizer.getResult():decoder.recognizer.getPartialResult();}
            finally{Arrays.fill(pcm,(short)0);}
            org.json.JSONObject json=new org.json.JSONObject(output);String text=json.optString(complete?"text":"partial","").trim();
            if(complete&&!text.isEmpty()){
                boolean clear=CommandEvidence.clear(output);synchronized(lock){if(!current(token,Mode.COMMAND))return;mode=Mode.IDLE;ring.clear();}
                closeDecoder();note(clear?"COMMAND_HEARD":"COMMAND_UNCLEAR","Offline command result (transcript not saved in study)");
                main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}if(clear)listener.onFinal(text);else listener.onUnclear(text);});
            }else if(!complete&&!text.equals(lastPartial)&&SystemClock.elapsedRealtime()-lastPartialAt>=150){lastPartial=text;lastPartialAt=SystemClock.elapsedRealtime();main.post(()->{if(current(token,Mode.COMMAND))listener.onPartial(text);});}
        }catch(Exception error){long token;synchronized(lock){token=generation;}commandError(token,"Command capture failed: "+error.getMessage());}
        finally{draining.set(false);}
    }
    private void commandError(long token,String error){
        VoskEngine.SttListener listener;synchronized(lock){if(!current(token,Mode.COMMAND))return;mode=Mode.IDLE;ring.clear();listener=commandListener;}closeDecoder();note("COMMAND_LOST",error);
        main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}if(listener!=null)listener.onError(error);});
    }
    private void closeDecoder(){if(decoder!=null){decoder.close();decoder=null;}}
    private void execute(Runnable task){try{work.execute(task);}catch(RejectedExecutionException ignored){}}
    private void note(String kind,String detail){execute(()->{LogStore.append(context,kind,detail);WakeDiagnostics.event(context,kind,detail);});}
    public void close(){
        synchronized(lock){if(mode==Mode.CLOSED)return;mode=Mode.CLOSED;generation++;detector=null;ring.clear();}
        capture.stop();execute(()->{closeDecoder();capture.awaitStopped();WakeDiagnostics.exposure(context,exposureSamples);WakeDiagnostics.event(context,"MIC_STOPPED","Session ended; active ms="+(SystemClock.elapsedRealtime()-startedAt));});work.shutdown();
    }
}
