package com.iris.assistant;
import android.content.Context;
import android.os.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.concurrent.*;
/** Experimental same-phone Zepp relay. Loopback only, bounded authenticated requests. */
final class WatchBridge implements AutoCloseable {
 static final int PORT=18473;
 interface Actions {String run(String action);}
 private final Context context;private final Actions actions;private final Handler main=new Handler(Looper.getMainLooper());
 private volatile boolean closed;private volatile ServerSocket server;private volatile Socket client;private long lastAction;
 static volatile String state="Disabled";
 WatchBridge(Context c,Actions a){context=c;actions=a;}
 static boolean enabled(Context c){return c.getSharedPreferences("watch_bridge",0).getBoolean("enabled",false);}
 static void enabled(Context c,boolean on){c.getSharedPreferences("watch_bridge",0).edit().putBoolean("enabled",on).apply();}
 static String token(Context c)throws Exception{return SecureStore.read(c,"watch-token.txt","");}
 static String rotate(Context c)throws Exception{byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder t=new StringBuilder();for(byte b:bytes)t.append(String.format(java.util.Locale.ROOT,"%02x",b&255));SecureStore.write(c,"watch-token.txt",t.toString());return t.toString();}
 static boolean authorized(String actual,String expected){return expected!=null&&expected.length()==64&&actual!=null&&MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),("Bearer "+expected).getBytes(StandardCharsets.UTF_8));}
 static boolean allowed(String method,String path){return (method.equals("GET")&&path.equals("/status"))||(method.equals("POST")&&(path.equals("/find")||path.equals("/stop")||path.equals("/talk")));}
 void start(){if(!enabled(context))return;new Thread(()->{
  try{ServerSocket s=new ServerSocket();s.setReuseAddress(true);s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),4);server=s;if(closed){s.close();return;}state="Listening on this phone only";
   while(!closed){try(Socket socket=s.accept()){client=socket;socket.setSoTimeout(1500);serve(socket);}catch(Exception e){if(!closed)VoiceHealth.event("WATCH_REQUEST","Rejected or disconnected");}finally{client=null;}}
  }catch(Exception e){if(!closed)state="Unavailable: local port could not open";}
 },"IRIS-WatchBridge").start();}
 private static String line(InputStream in)throws IOException{StringBuilder s=new StringBuilder();for(int i=0;i<2048;i++){int b=in.read();if(b<0)throw new EOFException();if(b==10)return s.toString().replace("\r","");s.append((char)b);}throw new IOException("Header too long");}
 private void serve(Socket socket)throws Exception{
  InputStream in=socket.getInputStream();String[] request=line(in).split(" ");if(request.length!=3){respond(socket,400,"Bad request");return;}
  String auth=null;boolean origin=false;int length=0,total=0;boolean end=false;
  for(int i=0;i<24;i++){String l=line(in);total+=l.length();if(total>4096)throw new IOException("Too many headers");if(l.isEmpty()){end=true;break;}int at=l.indexOf(':');if(at<1)throw new IOException("Bad header");String k=l.substring(0,at).toLowerCase(java.util.Locale.ROOT),v=l.substring(at+1).trim();if(k.equals("authorization")){if(auth!=null)throw new IOException("Duplicate authorization");auth=v;}if(k.equals("origin")||k.equals("transfer-encoding"))origin=true;if(k.equals("content-length"))length=Integer.parseInt(v);}
  if(!end||length!=0||origin||!allowed(request[0],request[1])){respond(socket,400,"Unsupported request");return;}
  if(closed||!enabled(context)||!authorized(auth,token(context))){respond(socket,403,"Pairing required");return;}
  long now=SystemClock.elapsedRealtime();if(!request[1].equals("/status")&&now-lastAction<1500){respond(socket,429,"Wait before retrying");return;}if(!request[1].equals("/status"))lastAction=now;
  final String path=request[1],credential=auth;CompletableFuture<String> result=new CompletableFuture<>();long deadline=SystemClock.elapsedRealtime()+2000;
  Runnable job=()->{if(closed||SystemClock.elapsedRealtime()>deadline||!enabled(context)){result.complete("Bridge disabled or request expired");return;}try{if(!authorized(credential,token(context))){result.complete("Pairing expired");return;}result.complete(actions.run(path));}catch(Exception e){result.complete("Action unavailable");}};
  main.post(job);try{respond(socket,200,result.get(2500,TimeUnit.MILLISECONDS));}catch(TimeoutException e){main.removeCallbacks(job);respond(socket,503,"Phone busy; request expired");}
 }
 private void respond(Socket socket,int code,String message)throws Exception{byte[] body=new org.json.JSONObject().put("message",message).toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" IRIS\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
 public void close(){closed=true;state="Disabled";main.removeCallbacksAndMessages(null);try{if(server!=null)server.close();}catch(Exception ignored){}try{if(client!=null)client.close();}catch(Exception ignored){}}
}
