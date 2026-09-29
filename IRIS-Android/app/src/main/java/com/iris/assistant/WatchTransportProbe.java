package com.iris.assistant;
import android.app.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Checks the actual authenticated loopback server without ringing or starting the microphone. */
final class WatchTransportProbe {
 static void run(Activity a){AlertDialog d=new AlertDialog.Builder(a).setTitle("Testing phone bridge").setMessage("Checking local connection…").setPositiveButton("Close",null).show();new Thread(()->{
  String result;try(Socket socket=new Socket()){
   socket.connect(new InetSocketAddress("127.0.0.1",WatchBridge.PORT),1500);socket.setSoTimeout(4000);
   socket.getOutputStream().write(("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+WatchBridge.token(a)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
   BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));String status=reader.readLine();
   result=status!=null&&status.startsWith("HTTP/1.1 200 ")?"Phone bridge responded successfully. Next press Phone status on the watch. If that fails, check Zepp pairing/Bluetooth and local HTTP support. This check does not prove the watch transport works.":"Bridge rejected this pairing. Generate a new code and update Zepp settings.";
  }catch(Exception e){result="Phone bridge unavailable. Enable it, keep IRIS running, and retry. The watch cannot connect until this phone test passes.";}
  final String report=result;a.runOnUiThread(()->{if(d.isShowing()&&!a.isFinishing())d.setMessage(report);});
 },"IRIS-BridgeProbe").start();}
}
