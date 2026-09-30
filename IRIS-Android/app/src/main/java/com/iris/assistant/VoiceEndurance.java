package com.iris.assistant;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.*;
/** Opt-in metadata measurements; no generated/replayed owner audio or identity bypass. */
final class VoiceEndurance {
 private static final ArrayDeque<JSONObject> done=new ArrayDeque<>();
 private static JSONObject current;private static long started;private static boolean automatic;private static String input="unknown";
 private static final String[] CONDITIONS={"Phone quiet","Phone screen off","Music playing","Headset","Soft / distant","Other speaker / media"};
 static synchronized void start(boolean auto){done.clear();current=null;automatic=auto;}
 static synchronized void begin(String condition){if(current!=null)throw new IllegalStateException("Label the current attempt first");beginInternal(condition);}
 private static void beginInternal(String condition){try{started=SystemClock.elapsedRealtime();current=new JSONObject().put("condition",condition).put("route",input).put("at",System.currentTimeMillis()).put("stage","Awaiting microphone / phrase").put("events",new JSONObject());}catch(Exception ignored){}}
 static synchronized void input(String route){input=route;if(current!=null)try{current.put("route",route);JSONObject events=current.getJSONObject("events");if(!events.has("PCM"))events.put("PCM",SystemClock.elapsedRealtime()-started);}catch(Exception ignored){}}
 static synchronized void event(String kind){
  tick();if(automatic&&"OWNER_ACCEPTED".equals(kind)&&done.size()<500){if(current!=null)finish("Interrupted by a fresh wake");beginInternal("Automatic accepted-cycle monitor");}
  if(current==null)return;
  try{JSONObject events=current.getJSONObject("events");if(!events.has(kind))events.put(kind,SystemClock.elapsedRealtime()-started);current.put("stage",kind);
   if(automatic&&"REARM".equals(kind))finish(events.has("COMMAND_HEARD")||events.has("ACK_STARTED")?"Cycle completed":"Cycle ended without acknowledgement or command");
  }catch(Exception ignored){}
 }
 static synchronized void tick(){if(current!=null&&SystemClock.elapsedRealtime()-started>120000)finish("Attempt exceeded two minutes");}
 static synchronized void finish(String label){if(current==null)return;try{current.put("label",label).put("durationMs",SystemClock.elapsedRealtime()-started);if(done.size()==500)done.removeFirst();done.addLast(current);}catch(Exception ignored){}current=null;if(done.size()>=500)automatic=false;}
 static synchronized void stop(){finish("Stopped by user");automatic=false;}
 static synchronized String export(){try{JSONArray rows=new JSONArray();for(JSONObject r:done)rows.put(r);return new JSONObject().put("version","13.0.0").put("automatic",automatic).put("attempts",rows).put("current",current==null?JSONObject.NULL:current).toString(2);}catch(Exception e){return "No report";}}
 static synchronized String report(){tick();StringBuilder s=new StringBuilder("Completed attempts: "+done.size()+" / 500\nMode: "+(automatic?"Automatic accepted-cycle monitor":"User-labelled first-call study")+"\nCurrent: "+(current==null?"none":current.optString("stage"))+"\nInput: "+input+"\n\n");
  Map<String,int[]> groups=new LinkedHashMap<>();List<Long> ack=new ArrayList<>();int cycles=0,failures=0;
  for(JSONObject r:done){String label=r.optString("label");if(label.equals("Cycle completed"))cycles++;else if(label.startsWith("Cycle")||label.startsWith("Attempt")||label.startsWith("Interrupted"))failures++;
   if(label.equals("First call worked")||label.equals("Missed / failed")){int[] g=groups.computeIfAbsent(r.optString("condition")+" · "+r.optString("route"),k->new int[2]);g[1]++;if(label.equals("First call worked"))g[0]++;}
   JSONObject e=r.optJSONObject("events");if(e!=null&&e.has("ACK_STARTED")&&e.has("OWNER_ACCEPTED"))ack.add(e.optLong("ACK_STARTED")-e.optLong("OWNER_ACCEPTED"));
  }
  for(Map.Entry<String,int[]> g:groups.entrySet())s.append(g.getKey()).append(": ").append(g.getValue()[0]).append('/').append(g.getValue()[1]).append(" first calls worked\n");
  Collections.sort(ack);if(!ack.isEmpty())s.append("Verified wake → local speech start p95: ").append(ack.get(Math.max(0,(int)Math.ceil(.95*ack.size())-1))).append(" ms (n=").append(ack.size()).append(")\n");
  s.append("Automatic completed cycles: ").append(cycles).append("; incomplete/timeouts: ").append(failures).append("\n\nAutomatic cycles begin only after an accepted wake; they do not measure missed calls or acoustic accuracy. Use labelled attempts for that. Nothing changes your saved voice automatically. Reports contain event timings, conditions and routes, not recordings or command text.");return s.toString();
 }
 static void show(Activity a){
  new AlertDialog.Builder(a).setTitle("Voice endurance and labelled trials").setItems(new String[]{"Start / reset labelled study","Start / reset 500-cycle monitor","Begin my next attempt","Label current attempt","View / export results","Stop monitoring"},(d,i)->{
   if(i<2){new AlertDialog.Builder(a).setMessage("Reset this in-memory study? Export the old results first. For automatic monitoring, use IRIS normally for up to 500 accepted wakes; no audio is replayed.").setPositiveButton("Start",(x,w)->{start(i==1);Toast.makeText(a,"Study started",0).show();}).setNegativeButton("Cancel",null).show();}
   else if(i==2)new AlertDialog.Builder(a).setTitle("Test condition — then say your phrase").setItems(CONDITIONS,(x,k)->{try{automatic=false;begin(CONDITIONS[k]);Toast.makeText(a,"Say your wake phrase once, then label the result.",1).show();}catch(Exception e){Toast.makeText(a,e.getMessage(),1).show();}}).show();
   else if(i==3)new AlertDialog.Builder(a).setTitle("What happened on the first call?").setItems(new String[]{"First call worked","Missed / failed","Unwanted wake","Correctly rejected other speaker / media"},(x,k)->finish(new String[]{"First call worked","Missed / failed","Unwanted wake","Correctly rejected other speaker / media"}[k])).show();
   else if(i==4)new AlertDialog.Builder(a).setTitle("Measured results").setMessage(report()).setPositiveButton("Close",null).setNeutralButton("Export metadata",(x,w)->a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,export()),"Share endurance results"))).show();
   else stop();
  }).setNegativeButton("Close",null).show();
 }
}
