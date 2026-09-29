package com.iris.assistant;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
/** Explicit foreground-only recording of a variation that background detection missed.
 * Does not save or alter a profile. PCM is erased after evidence extraction. */
final class OwnerFeedbackCapture implements AutoCloseable {
 interface Listener {
  void state(String text,boolean recording);
  void sample(float[][] pattern,float[] ecapa,float[] vosk,AudioRouteController.Route route);
  void error(String message);
 }
 private final Context context;private final OwnerVoiceProfile profile;private final Listener listener;
 private final Handler main=new Handler(Looper.getMainLooper());private final VoskEngine engine=new VoskEngine();
 private TimedRecorder recorder;private volatile boolean closed;private boolean recording,started;
 OwnerFeedbackCapture(Context c,OwnerVoiceProfile p,Listener l){context=c.getApplicationContext();profile=p;listener=l;}
 void start(){
  listener.state("Preparing your saved voice models…",false);
  final long deadline=SystemClock.elapsedRealtime()+60000;
  main.postDelayed(()->{if(!started)fail("Voice models did not become ready. No profile change made.");},60000);
  engine.initOwner(context,new VoskEngine.InitListener(){
   public void onReady(){if(closed)return;engine.initSpeaker(context);awaitModels(deadline);}
   public void onError(String message){fail(message);}
  });
 }
 private void awaitModels(long deadline){
  if(closed)return;
  if(SystemClock.elapsedRealtime()>=deadline){fail("Voice models or microphone are unavailable. Try again shortly.");return;}
  if(!engine.isSpeakerReady()||(profile.usesEcapa()&&!engine.ecapaReady())||AudioCaptureCoordinator.busy()){
   main.postDelayed(()->awaitModels(deadline),250);return;
  }
  if(!engine.profileModelMatches(profile)){fail("The speaker model changed. Refine your saved voice first.");return;}
  AudioRouteController.Route route=AudioRouteController.currentlyConnected(context);
  if(route==AudioRouteController.Route.UNCONFIRMED||(route==AudioRouteController.Route.HEADSET&&profile.headset==null)){
   fail("Add a headset voice profile first, or disconnect the headset to teach the phone microphone.");return;
  }
  started=true;recorder=new TimedRecorder(context,route);
  listener.state("Connecting microphone…",false);
  recorder.recordPhrase(new TimedRecorder.Listener(){
   public void onLevel(float level){if(!closed&&!recording){recording=true;listener.state("Listening. Say your wake phrase in the way that was missed, then pause or tap Done.",true);}}
   public void onError(String message){fail(message);}
   public void onComplete(short[] pcm){
    if(closed){Arrays.fill(pcm,(short)0);return;}
    recording=false;listener.state("Checking the phrase and your voice separately…",false);
    main.postDelayed(()->fail("Voice analysis timed out. No profile change made."),30000);
    AudioRouteController.Route captured=recorder.capturedRouteType();
    new Thread(()->{
     try{
      float[][] pattern;float[] e=null,v;
      synchronized(engine){
       if(closed)return;
       if(captured!=route||!TrainingAudioQuality.measure(pcm).enrollmentUsable())throw new IllegalArgumentException("Recording was unclear or the microphone changed. Try one clean, natural phrase.");
       pattern=SoundPattern.extract(pcm);
       if(!SoundPattern.valid(pattern))throw new IllegalArgumentException("A complete phrase was not captured. Please record it again.");
       v=engine.embedRecorded(pcm);if(profile.usesEcapa())e=engine.embedEcapa(pcm);
      }
      final float[] ecapa=e,vosk=v;
      main.post(()->{if(!closed)listener.sample(pattern,ecapa,vosk,captured);});
     }catch(Exception error){fail(error.getMessage());}
     finally{Arrays.fill(pcm,(short)0);}
    },"IRIS-FeedbackVoice").start();
   }
  });
 }
 void finish(){if(recorder!=null&&recording)recorder.finish();}
 private void fail(String message){main.post(()->{if(!closed)listener.error(message);});}
 public void close(){closed=true;main.removeCallbacksAndMessages(null);if(recorder!=null)recorder.stop();engine.close();}
}
