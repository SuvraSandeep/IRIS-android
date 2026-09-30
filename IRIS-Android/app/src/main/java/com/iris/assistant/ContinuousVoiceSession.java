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
    private final VoiceWorkerClient modelWorker;private volatile boolean preparing;private volatile String remoteDecoder="";private String preparedRevision="";
    private Mode mode=Mode.IDLE, beforeVerify=Mode.WAKE;private long generation,detectorOrigin,commandCursor,commandStart,wakeCursor;
    private int routeId=-1;private AudioRouteController.Route route=AudioRouteController.Route.UNCONFIRMED;
    private OwnerVoiceProfile profile;private RecordedPhrase phrase;private StreamingWakeDetector detector;
    private VoskEngine.WakeListener wakeListener;private VoskEngine.SttListener commandListener;
    private volatile VoskEngine.Decoder decoder;private String lastPartial="";private long lastPartialAt;
    private long lastScanAt;
    private QuietAudioProcessor commandGain=new QuietAudioProcessor();
    private long startedAt=SystemClock.elapsedRealtime();private volatile boolean ready,failed,inputSilenced;private long exposureSamples;
    private volatile long lastPcmAt=SystemClock.elapsedRealtime(),lastAnalysisAt=SystemClock.elapsedRealtime();
    private final Runnable watchdog=new Runnable(){public void run(){
        synchronized(lock){if(mode==Mode.CLOSED||failed)return;}
        long now=SystemClock.elapsedRealtime();VoiceEndurance.tick();
        if(capture.stopped()||now-lastPcmAt>8000){failSession("Microphone stalled; restarting capture");return;}
        synchronized(lock){
            if(!inputSilenced&&!preparing&&(mode==Mode.WAKE||mode==Mode.COMMAND||mode==Mode.VERIFY)
                    && now-lastAnalysisAt>15000){failSession("Voice analysis stalled; restarting capture");return;}
        }
        main.postDelayed(this,2000);
    }};
    ContinuousVoiceSession(Context context,VoskEngine owner){this(context,owner,true);}
    // Allows lifecycle tests to supply PCM without opening physical hardware.
    ContinuousVoiceSession(Context context,VoskEngine owner,boolean startCapture){
        this.context=context.getApplicationContext();this.owner=owner;
        modelWorker=startCapture?new VoiceWorkerClient(this.context):null;
        capture=new ManagedSpeechService(this.context,null,16000,true);
        capture.setReadyListener(()->{ready=true;note("MIC_READY","Input stable; startup "+(SystemClock.elapsedRealtime()-startedAt)+"ms");});
        capture.setFrameListener(new ManagedSpeechService.FrameListener(){
            public void onFrames(short[] pcm,int n,AudioRouteController.Route input,int id){lastPcmAt=SystemClock.elapsedRealtime();frames(pcm,n,input,id);}
            public void onHealth(String status){lastAnalysisAt=SystemClock.elapsedRealtime();inputSilenced="MIC_SILENCED_BY_ANDROID".equals(status);note(status,"Microphone capture state");
                if(inputSilenced){VoskEngine.SttListener c;VoskEngine.WakeListener w;synchronized(lock){if(mode==Mode.CLOSED)return;c=commandListener;w=wakeListener;mode=Mode.IDLE;generation++;ring.clear();wakeCursor=0;}execute(ContinuousVoiceSession.this::closeDecoder);
                    main.post(()->{if(c!=null)c.onError("Android silenced the microphone");else if(w!=null)w.onError("Android silenced the microphone");});}}
        });
        if(startCapture)capture.startListening(new RecognitionListener(){
            public void onPartialResult(String s){}public void onResult(String s){}public void onFinalResult(String s){}public void onTimeout(){}
            public void onError(Exception e){failSession("Microphone unavailable: "+e.getMessage());}
        });
        if(startCapture)main.postDelayed(watchdog,2000);
    }
    boolean usable(){synchronized(lock){return mode!=Mode.CLOSED&&!failed;}}
    boolean readyForCommand(){synchronized(lock){return mode==Mode.READY&&ready&&!failed;}}
    void arm(OwnerVoiceProfile saved,VoskEngine.WakeListener listener){
        synchronized(lock){if(mode==Mode.CLOSED)return;generation++;mode=Mode.WAKE;lastAnalysisAt=SystemClock.elapsedRealtime();profile=saved;wakeListener=listener;commandListener=null;ring.clear();wakeCursor=0;detector=null;phrase=null;routeId=-1;}
        execute(()->{closeDecoder();if(modelWorker!=null&&!saved.revision().equals(preparedRevision)){
            preparing=true;try{modelWorker.prepare(saved);preparedRevision=saved.revision();}catch(Exception e){failSession("Model worker unavailable; restarting securely");}finally{preparing=false;lastAnalysisAt=SystemClock.elapsedRealtime();}
        }});note("WAKE_ARMED","Streaming phrase + owner; waiting for real PCM");
    }
    void pause(){synchronized(lock){if(mode==Mode.CLOSED)return;generation++;mode=Mode.IDLE;commandListener=null;detector=null;ring.clear();wakeCursor=0;}execute(this::closeDecoder);}
    private void frames(short[] pcm,int n,AudioRouteController.Route input,int id){
        synchronized(lock){
            if(mode==Mode.CLOSED||mode==Mode.IDLE||inputSilenced)return;
            VoiceHealth.pcm(input.name(),pcm,n);VoiceEndurance.input(input.name());
            if(id!=routeId||input!=route){
                boolean interrupted=mode==Mode.COMMAND||mode==Mode.READY||mode==Mode.VERIFY;
                generation++;routeId=id;route=input;ring.clear();wakeCursor=0;detector=null;phrase=null;
                note("INPUT_CHANGED",input+" device="+id);
                if(interrupted){Mode before=mode;mode=Mode.IDLE;long token=generation;VoskEngine.SttListener c=commandListener;VoskEngine.WakeListener w=wakeListener;
                    execute(this::closeDecoder);
                    main.post(()->{if(!current(token,Mode.IDLE))return;if(before==Mode.COMMAND&&c!=null)c.onError("Microphone changed; repeat on the new input");else if(w!=null)w.onError("Microphone changed during owner verification");});return;}
            }
            if(mode==Mode.WAKE||mode==Mode.VERIFY){exposureSamples+=n;if(exposureSamples>=320000){long measured=exposureSamples;exposureSamples=0;execute(()->WakeDiagnostics.exposure(context,measured));}}
            ring.append(pcm,n);
        }
        requestDrain();
    }
    /** The audio-priority thread only copies PCM. FFT, matching and native models run here.
     * At most one drain is queued; producer speed cannot create an unbounded task backlog. */
    private void requestDrain(){
        synchronized(lock){
            boolean pending=!inputSilenced&&!failed&&((mode==Mode.WAKE||mode==Mode.COMMAND&&profile!=null)&&wakeCursor<ring.end()||mode==Mode.COMMAND&&(decoder!=null||!remoteDecoder.isEmpty())&&commandCursor<ring.end());
            if(!pending||!draining.compareAndSet(false,true))return;
        }
        execute(()->{try{VoiceTaskGuard.run(()->{Mode state;synchronized(lock){state=mode;}if(state==Mode.WAKE||state==Mode.COMMAND&&profile!=null)drainWake();if(state==Mode.COMMAND)drainCommand();lastAnalysisAt=SystemClock.elapsedRealtime();},this::analysisFailed);}
            finally{draining.set(false);requestDrain();}});
    }
    private void drainWake(){
        short[] pcm;StreamingWakeDetector active;long token,origin;OwnerVoiceProfile saved;RecordedPhrase evidence;
        synchronized(lock){
            if((mode!=Mode.WAKE&&mode!=Mode.COMMAND)||inputSilenced)return;
            if(route==AudioRouteController.Route.UNCONFIRMED){wakeCursor=ring.end();return;}
            if(wakeCursor<ring.first()){
                note("WAKE_OVERRUN","Analysis fell behind capture; discarded stale audio");wakeCursor=ring.first();detector=null;
            }
            if(detector==null){
                phrase=route==AudioRouteController.Route.HEADSET?(profile.headset==null?null:profile.headset.phraseEvidence):profile.phraseEvidence;
                if(phrase==null){mode=Mode.IDLE;VoskEngine.WakeListener w=wakeListener;long t=generation;main.post(()->{if(current(t,Mode.IDLE))w.onError("Add a headset profile or select the phone microphone");});return;}
                List<float[][]> examples=new ArrayList<>(phrase.samples);examples.addAll(phrase.positives);
                detector=new StreamingWakeDetector(examples,phrase.threshold,phrase::accepts);detectorOrigin=wakeCursor;
            }
            token=generation;active=detector;origin=detectorOrigin;saved=profile;evidence=phrase;
            long end=Math.min(ring.end(),wakeCursor+640);pcm=ring.slice(wakeCursor,end);wakeCursor=end;
        }
        StreamingWakeDetector.Match candidate;
        try{candidate=active.add(pcm,pcm.length);}finally{Arrays.fill(pcm,(short)0);}
        long now=SystemClock.elapsedRealtime();
        if(now-lastScanAt>=5000){lastScanAt=now;VoiceHealth.event("WAKE_SCAN","Input="+route+"; closest candidate="+active.bestDistance()+"; sample position="+wakeCursor);}
        if(candidate==null)return;
        note("PHRASE_MATCHED","Candidate sent for independent speaker verification");
        synchronized(lock){if(token!=generation||(mode!=Mode.WAKE&&mode!=Mode.COMMAND)||inputSilenced)return;beforeVerify=mode;mode=Mode.VERIFY;}
        verify(new StreamingWakeDetector.Match(candidate.start+origin,candidate.end+origin,candidate.distance,candidate.pattern),token,saved,evidence);
    }
    private boolean current(long token,Mode expected){synchronized(lock){return generation==token&&mode==expected&&!inputSilenced;}}
    private void verify(StreamingWakeDetector.Match match,long token,OwnerVoiceProfile p,RecordedPhrase ph){
        if(!current(token,Mode.VERIFY))return;
        float[][] pattern=null;float[] vosk=null,ecapa=null;String reason="ANALYSIS_ERROR";AudioRouteController.Route input;
        long begin=SystemClock.elapsedRealtime();
        synchronized(lock){input=route;}
        try{
            pattern=match.pattern;
            if(!ph.accepts(pattern)){reject(token,"PHRASE_MISMATCH",pattern,null,null,input,match.distance);return;}
            short[] speech;
            synchronized(lock){speech=ring.slice(Math.max(ring.first(),match.start-1600),Math.min(ring.end(),match.end+1600));}
            short[] speakerContext=SoundPattern.boundedContext(speech);
            try{if(modelWorker!=null){android.os.Bundle result=modelWorker.embed(speakerContext);vosk=result.getFloatArray("vosk");ecapa=result.getFloatArray("ecapa");}else synchronized(owner){vosk=owner.embedRecorded(speakerContext);if(!WakePolicy.isAbsent(input==AudioRouteController.Route.HEADSET?p.headset.ecapaCentroid():p.ecapaCentroid()))ecapa=owner.embedEcapa(speakerContext);}}
            finally{Arrays.fill(speech,(short)0);Arrays.fill(speakerContext,(short)0);}
            boolean accepted=input==AudioRouteController.Route.HEADSET?p.acceptsHeadset(ecapa,vosk,p.threshold()):p.accepts(ecapa,vosk,p.threshold());
            reason=accepted?"OWNER_ACCEPTED":!WakePolicy.owner(vosk,vosk,.99)?"SPEAKER_EVIDENCE":"OWNER_REJECTED";
            // Do not run four native inferences against a rejected candidate while later calls
            // accumulate in the ring. A new utterance gets its own phrase and owner decision.
            if(!accepted){reject(token,reason,pattern,ecapa,vosk,input,match.distance);return;}
            if(!p.revision().equals(new ProfileStore(context).ownerRevision())){reject(token,"PROFILE_CHANGED",pattern,ecapa,vosk,input,match.distance);return;}
            final float[] v=vosk,e=ecapa;final float[][] sound=pattern;
            final long acceptedToken;
            synchronized(lock){if(!current(token,Mode.VERIFY))return;acceptedToken=++generation;mode=Mode.READY;commandListener=null;commandStart=match.end;}
            closeDecoder();
            note("OWNER_ACCEPTED",input+"; processing="+(SystemClock.elapsedRealtime()-begin)+"ms; one owner decision per candidate");
            main.post(()->{if(!current(acceptedToken,Mode.READY))return;
                try{owner.streamingOutcome(p,sound,e,v,input,true,"OWNER_ACCEPTED",match.distance);wakeListener.onWakeDetected(e,v);}
                catch(RuntimeException error){failSession("Wake handoff failed: "+error.getClass().getSimpleName());}});
        }catch(Exception error){if(modelWorker!=null&&modelWorker.failed()){failSession("Speaker worker failed; restarting securely");return;}reject(token,reason,pattern,ecapa,vosk,input,match.distance);}
    }
    private void reject(long token,String reason,float[][] pattern,float[] ecapa,float[] vosk,AudioRouteController.Route input,double distance){
        synchronized(lock){if(!current(token,Mode.VERIFY))return;mode=beforeVerify;}
        note(reason,input+"; streaming distance="+distance);
        main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}
            try{owner.streamingOutcome(profile,pattern,ecapa,vosk,input,false,reason,distance);wakeListener.onRejected(reason);}
            catch(RuntimeException error){VoiceHealth.event("WAKE_DIAGNOSTIC_ERROR",error.getClass().getSimpleName());}});
        requestDrain();
    }
    boolean hasBufferedCommand(){
        synchronized(lock){
            if(mode!=Mode.READY)return false;
            short[] tail=ring.slice(Math.min(ring.end(),Math.max(ring.first(),commandStart+1600)),ring.end());
            int voiced=0;try{for(short sample:tail)if(Math.abs((int)sample)>350)voiced++;return voiced>480;}
            finally{Arrays.fill(tail,(short)0);}
        }
    }
    void commands(VoskEngine commandEngine,VoskEngine.SttListener listener){
        final long token;
        synchronized(lock){if(mode==Mode.CLOSED)return;boolean handoff=mode==Mode.READY;generation++;token=generation;mode=Mode.COMMAND;lastAnalysisAt=SystemClock.elapsedRealtime();commandListener=listener;
            // Real bug this clamp fixes: on a slow spoken greeting/reply, the ring can advance
            // past commandStart (recorded at OWNER_ACCEPTED time, match.end) before this
            // handoff runs. AudioRing.slice() THROWS IllegalStateException when asked to read
            // before ring.first() -- so drainCommand()'s very next slice(commandCursor,...)
            // call would crash the analysis loop instead of just reading stale/wrong data.
            // hasBufferedCommand() already clamps the same way (Math.max(ring.first(),...))
            // when just checking for voiced audio; the handoff path must clamp identically
            // before actually slicing.
            commandCursor=handoff?Math.max(ring.first(),commandStart):ring.end();wakeCursor=commandCursor;detector=null;phrase=null;}
        execute(()->{
            closeDecoder();if(!current(token,Mode.COMMAND))return;
            try{preparing=true;if(modelWorker!=null)remoteDecoder=modelWorker.openCommand();else decoder=commandEngine.decoder();preparing=false;lastAnalysisAt=SystemClock.elapsedRealtime();commandGain=new QuietAudioProcessor();lastPartial="";lastPartialAt=0;
                main.post(()->{if(current(token,Mode.COMMAND))listener.onReady();});requestDrain();}
            catch(Exception error){preparing=false;VoiceHealth.event("COMMAND_START_ERROR",CrashSummary.describe(error));if(modelWorker!=null&&modelWorker.failed()){failSession("Command worker failed; restarting");return;}commandError(token,"Cannot start command decoder: "+error.getClass().getSimpleName());}
        });
    }
    private void drainCommand(){
        final long token;synchronized(lock){token=generation;}
        try{
            final VoskEngine.SttListener listener;short[] pcm;
            synchronized(lock){if(token!=generation)return;listener=commandListener;if(mode!=Mode.COMMAND||(decoder==null&&remoteDecoder.isEmpty()))return;long end=Math.min(ring.end(),commandCursor+3200);if(end==commandCursor)return;
                pcm=ring.slice(commandCursor,end);commandCursor=end;}
            String output;boolean complete;
            try{commandGain.processFrames(pcm);if(modelWorker!=null){android.os.Bundle r=modelWorker.feed(remoteDecoder,pcm);complete=r.getBoolean("complete");output=r.getString("text","{}");}else{complete=decoder.recognizer.acceptWaveForm(pcm,pcm.length);output=complete?decoder.recognizer.getResult():decoder.recognizer.getPartialResult();}}
            finally{Arrays.fill(pcm,(short)0);}
            org.json.JSONObject json=new org.json.JSONObject(output);String text=json.optString(complete?"text":"partial","").trim();
            if(complete&&!text.isEmpty()){
                boolean clear=CommandEvidence.clear(output);synchronized(lock){if(!current(token,Mode.COMMAND))return;mode=Mode.IDLE;ring.clear();wakeCursor=0;}
                closeDecoder();note(clear?"COMMAND_HEARD":"COMMAND_UNCLEAR","Offline command result (transcript not saved in study)");
                main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}if(clear)listener.onFinal(text);else listener.onUnclear(text);});
            }else if(!complete&&!text.equals(lastPartial)&&SystemClock.elapsedRealtime()-lastPartialAt>=150){lastPartial=text;lastPartialAt=SystemClock.elapsedRealtime();main.post(()->{if(current(token,Mode.COMMAND))listener.onPartial(text);});}
        }catch(Exception error){VoiceHealth.event("COMMAND_DECODE_ERROR",CrashSummary.describe(error));if(modelWorker!=null&&modelWorker.failed()){failSession("Command worker failed; restarting");return;}commandError(token,"Command capture failed: "+error.getClass().getSimpleName());}
    }
    private void commandError(long token,String error){
        VoskEngine.SttListener listener;synchronized(lock){if(!current(token,Mode.COMMAND))return;mode=Mode.IDLE;ring.clear();wakeCursor=0;listener=commandListener;}closeDecoder();note("COMMAND_LOST",error);
        main.post(()->{synchronized(lock){if(token!=generation||mode==Mode.CLOSED)return;}if(listener!=null)listener.onError(error);});
    }
    private void analysisFailed(RuntimeException error){failSession("Voice worker failed: "+error.getClass().getSimpleName());}
    private void failSession(String message){
        final VoskEngine.WakeListener w;final VoskEngine.SttListener c;final long token;
        synchronized(lock){if(mode==Mode.CLOSED||failed)return;failed=true;mode=Mode.IDLE;generation++;token=generation;ring.clear();w=wakeListener;c=commandListener;}
        capture.stop();if(modelWorker!=null)modelWorker.abort();VoiceHealth.event("VOICE_RECOVERY",message);VoiceEndurance.event("VOICE_RECOVERY");
        main.post(()->{synchronized(lock){if(generation!=token||mode==Mode.CLOSED)return;}
            if(c!=null)c.onError(message);else if(w!=null)w.onError(message);});
    }
    private void closeDecoder(){String old=remoteDecoder;remoteDecoder="";if(modelWorker!=null&&!old.isEmpty())modelWorker.closeCommand(old);if(decoder!=null){decoder.close();decoder=null;}}
    private void execute(Runnable task){try{work.execute(task);}catch(RejectedExecutionException ignored){}}
    private void note(String kind,String detail){VoiceEndurance.event(kind);VoiceHealth.event(kind,detail);execute(()->{LogStore.append(context,kind,detail);WakeDiagnostics.event(context,kind,detail);});}
    void awaitCaptureStopped(){capture.awaitStopped();}
    public void close(){
        synchronized(lock){if(mode==Mode.CLOSED)return;mode=Mode.CLOSED;generation++;detector=null;ring.clear();wakeCursor=0;}
        main.removeCallbacks(watchdog);capture.stop();if(modelWorker!=null)modelWorker.close();execute(()->{closeDecoder();capture.awaitStopped();WakeDiagnostics.exposure(context,exposureSamples);WakeDiagnostics.event(context,"MIC_STOPPED","Session ended; active ms="+(SystemClock.elapsedRealtime()-startedAt));});work.shutdown();
    }
}
