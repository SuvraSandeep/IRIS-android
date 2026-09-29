package com.iris.assistant;
public final class ReplyCompletionTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] ignored){
  ReplyCompletionGate gate=new ReplyCompletionGate();
  for(int cycle=0;cycle<100;cycle++){
   long reply=gate.begin();check(gate.complete(reply),"Every reply must permit re-arm");
   check(!gate.complete(reply),"TTS done plus timeout must not re-arm twice");
  }
  long old=gate.begin(),current=gate.begin();
  check(!gate.complete(old),"Late prior callback must not finish current reply");
  check(gate.complete(current),"Current reply finishes");
  long cancelled=gate.begin();gate.cancel();long resumed=gate.begin();
  check(!gate.complete(cancelled),"Stopped service callback must remain cancelled after restart");
  check(gate.complete(resumed),"Restarted service can complete");
  CommandLoadGate load=new CommandLoadGate();load.request(1);
  check(load.consume(1),"Startup timeout releases a pending load");
  check(!load.consume(1),"Late ready callback must not reopen timed-out command");
  load.request(2);check(!load.consume(1),"Old timeout cannot consume next wake");check(load.consume(2),"Next command can start");
  System.out.println("Passed repeated reply, stale TTS, duplicate completion and command-load timeout checks");
 }
}
