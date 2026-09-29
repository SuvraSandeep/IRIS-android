package com.iris.assistant;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
/** One bounded request in flight. Disconnects and stale replies cannot produce an owner pass. */
final class VoiceWorkerClient implements AutoCloseable {
 private final Context context;private final String client=java.util.UUID.randomUUID().toString();
 private final CountDownLatch connected=new CountDownLatch(1);private volatile Messenger remote;
 private volatile boolean closed,bound,broken;private final ModelReplyGate<Bundle> gate=new ModelReplyGate<>();
 private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper(),m->{gate.complete(m.arg1,m.getData());return true;}));
 private final ServiceConnection connection=new ServiceConnection(){
  public void onServiceConnected(ComponentName n,IBinder binder){if(closed)return;remote=new Messenger(binder);connected.countDown();}
  public void onServiceDisconnected(ComponentName n){disconnected();}
  public void onBindingDied(ComponentName n){disconnected();}
  public void onNullBinding(ComponentName n){disconnected();}
 };
 VoiceWorkerClient(Context c){context=c.getApplicationContext();bound=context.bindService(new Intent(context,VoiceWorkerService.class),connection,Context.BIND_AUTO_CREATE);if(!bound)disconnected();}
 private void disconnected(){broken=true;remote=null;connected.countDown();gate.fail(new IllegalStateException("Voice worker disconnected"));}
 synchronized Bundle request(int op,Bundle data)throws Exception {
  if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Model IPC cannot block the UI");
  if(closed||broken||!connected.await(5000,TimeUnit.MILLISECONDS)||remote==null)throw new IllegalStateException("Voice worker unavailable");
  data.putString("client",client);data.putInt("parentPid",android.os.Process.myPid());
  ModelReplyGate.Ticket<Bundle> ticket=gate.begin();
  Message message=Message.obtain(null,op,ticket.id,0);message.replyTo=replies;message.setData(data);
  try{remote.send(message);Bundle out=ticket.result.get(op==VoiceWorkerService.PREPARE||op==VoiceWorkerService.OPEN?65000:10000,TimeUnit.MILLISECONDS);
   if(!out.getString("error","").isEmpty())throw new IllegalStateException(out.getString("error"));return out;
  }catch(Exception e){broken=true;abort();throw e;}finally{gate.expire(ticket.id);}
 }
 void prepare(OwnerVoiceProfile profile)throws Exception{Bundle b=new Bundle();b.putBoolean("enhanced",profile.usesEcapa());Bundle r=request(VoiceWorkerService.PREPARE,b);
  if(!profile.hash().equals(r.getString("speakerHash"))||(profile.usesEcapa()&&!profile.data.optString("ecapaModelHash").equals(r.getString("ecapaHash"))))throw new IllegalStateException("Worker model fingerprint changed");}
 Bundle embed(short[] pcm)throws Exception{Bundle b=new Bundle();b.putShortArray("pcm",pcm);return request(VoiceWorkerService.EMBED,b);}
 String openCommand()throws Exception{String id=java.util.UUID.randomUUID().toString();Bundle b=new Bundle();b.putString("decoder",id);request(VoiceWorkerService.OPEN,b);return id;}
 Bundle feed(String id,short[] pcm)throws Exception{Bundle b=new Bundle();b.putString("decoder",id);b.putShortArray("pcm",pcm);return request(VoiceWorkerService.FEED,b);}
 void closeCommand(String id){try{Bundle b=new Bundle();b.putString("decoder",id);request(VoiceWorkerService.CLOSE,b);}catch(Exception ignored){}}
 boolean failed(){return broken||closed;}
 void abort(){Messenger r=remote;if(r==null)return;try{Message m=Message.obtain(null,VoiceWorkerService.ABORT);Bundle b=new Bundle();b.putString("client",client);b.putInt("parentPid",android.os.Process.myPid());m.setData(b);r.send(m);}catch(RemoteException ignored){}}
 public void close(){abort();closed=true;gate.fail(new IllegalStateException("Voice session closed"));if(bound){bound=false;context.unbindService(connection);}remote=null;}
}
