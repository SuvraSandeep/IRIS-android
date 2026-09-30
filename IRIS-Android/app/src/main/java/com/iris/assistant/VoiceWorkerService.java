package com.iris.assistant;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.Arrays;
import java.util.concurrent.*;
/** Native model operations live in :voice_models. A deadline can terminate this process. */
public final class VoiceWorkerService extends Service {
 static final int PREPARE=1,EMBED=2,OPEN=3,FEED=4,CLOSE=5,ABORT=6;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final VoskEngine owner=new VoskEngine(),commands=new VoskEngine();
 private VoskEngine.Decoder decoder;private String decoderId="",clientId="";private boolean busy,enhanced;
 private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper(),this::receive));
 @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
 private boolean receive(Message m){
  Bundle in=m.getData();String client=in.getString("client","");
  if(m.sendingUid!=android.os.Process.myUid()||in.getInt("parentPid")==android.os.Process.myPid())return true;
  if(m.what==ABORT){if(client.equals(clientId))android.os.Process.killProcess(android.os.Process.myPid());return true;}
  if(m.replyTo==null)return true;
  if(busy){reply(m,new Bundle(),"Voice worker busy");return true;}
  busy=true;clientId=client;
  final int operation=m.what,request=m.arg1;final Messenger target=m.replyTo;
  // Main-thread watchdog remains responsive while native inference blocks the worker thread.
  Runnable timeout=()->android.os.Process.killProcess(android.os.Process.myPid());
  // Embed/feed deadline raised 8s -> 12s. STRICTLY ADDITIVE: a successful embedding returns in
  // well under a second, so this never changes a wake that works today. It only affects the
  // case where a cold, throttled or busy device exceeds the old 8s budget - which currently
  // kills this process and fails the wake attempt outright. The watchdog still exists, so a
  // genuinely hung native model is still terminated; it just gets a little longer first.
  // Kept strictly below the client's own timeout (see VoiceWorkerClient) so the worker is
  // always the side that gives up first and the client never reports a false success.
  main.postDelayed(timeout,operation==PREPARE||operation==OPEN?60000:12000);
  worker.execute(()->{
   Bundle out=new Bundle();String error="";short[] pcm=in.getShortArray("pcm");
   try{
    switch(operation){
     case PREPARE:
      enhanced=in.getBoolean("enhanced");owner.loadInstalledBlocking(this,true,enhanced);
      out.putString("speakerHash",owner.speakerFingerprint());out.putString("ecapaHash",owner.ecapaFingerprint());break;
     case EMBED:
      if(pcm==null||pcm.length<3200||pcm.length>128000)throw new IllegalArgumentException("Invalid owner clip");
      out.putFloatArray("vosk",owner.embedRecorded(pcm));if(enhanced)out.putFloatArray("ecapa",owner.embedEcapa(pcm));break;
     case OPEN:
      if(decoder!=null)decoder.close();decoder=null;
      commands.loadInstalledBlocking(this,false,false);decoder=commands.decoder();decoderId=in.getString("decoder","");break;
     case FEED:
      if(decoder==null||!decoderId.equals(in.getString("decoder"))||pcm==null||pcm.length>3200)throw new IllegalStateException("Command session changed");
      boolean complete=decoder.recognizer.acceptWaveForm(pcm,pcm.length);out.putBoolean("complete",complete);out.putString("text",complete?decoder.recognizer.getResult():decoder.recognizer.getPartialResult());break;
     case CLOSE:
      if(decoder!=null&&decoderId.equals(in.getString("decoder"))){decoder.close();decoder=null;}break;
     default:throw new IllegalArgumentException("Unknown operation");
    }
   }catch(Exception e){error=e.getClass().getSimpleName();}
   finally{if(pcm!=null)Arrays.fill(pcm,(short)0);}
   final String resultError=error;
   main.post(()->{main.removeCallbacks(timeout);busy=false;Message result=Message.obtain(null,operation,request,0);out.putString("error",resultError);result.setData(out);try{target.send(result);}catch(RemoteException ignored){}});
  });return true;
 }
 private void reply(Message request,Bundle result,String error){result.putString("error",error);Message m=Message.obtain(null,request.what,request.arg1,0);m.setData(result);try{request.replyTo.send(m);}catch(RemoteException ignored){}}
 @Override public void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
}
