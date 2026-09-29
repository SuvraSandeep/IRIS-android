package com.iris.assistant;
import android.content.Context;
import org.json.*;
/** Opt-in encrypted transcript history. Replies are not proof of completed actions. */
final class CommandHistory {
 static final String FILE="command-history.json";
 static boolean enabled(Context c){return c.getSharedPreferences("command_history",0).getBoolean("enabled",false);}
 static void enable(Context c,boolean v){c.getSharedPreferences("command_history",0).edit().putBoolean("enabled",v).apply();}
 static synchronized JSONArray recent(Context c){try{
  JSONArray raw=new JSONArray(SecureStore.read(c,FILE,"[]")),out=new JSONArray();
  long cutoff=System.currentTimeMillis()-7*86400000L;
  for(int i=0;i<raw.length();i++)if(raw.getJSONObject(i).optLong("at")>=cutoff)out.put(raw.getJSONObject(i));
  if(out.length()!=raw.length())SecureStore.write(c,FILE,out.toString());return out;
 }catch(Exception e){return new JSONArray();}}
 static synchronized String begin(Context c,String heard){
  if(!enabled(c)||heard==null||heard.trim().isEmpty())return "";
  try{JSONArray a=recent(c);String id=java.util.UUID.randomUUID().toString();Plan plan=IntentParser.parse(heard);
   a.put(new JSONObject().put("id",id).put("at",System.currentTimeMillis()).put("heard",heard.substring(0,Math.min(500,heard.length())))
    .put("understood",plan.isUnknown()?"Legacy command router / clarification":plan.intent().toString()).put("reply","Awaiting response"));
   while(a.length()>100)a.remove(0);SecureStore.write(c,FILE,a.toString());return id;
  }catch(Exception e){return "";}
 }
 static synchronized void reply(Context c,String id,String text){if(!enabled(c)||id==null||id.isEmpty()||text==null)return;
  try{JSONArray a=recent(c);for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).optString("id")))a.getJSONObject(i).put("reply",text.substring(0,Math.min(text.length(),1000)));SecureStore.write(c,FILE,a.toString());}catch(Exception ignored){}
 }
 static synchronized void clear(Context c){try{SecureStore.write(c,FILE,"[]");}catch(Exception ignored){}}
}
