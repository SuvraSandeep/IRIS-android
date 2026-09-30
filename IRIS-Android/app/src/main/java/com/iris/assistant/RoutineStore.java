package com.iris.assistant;
import android.content.Context;
import org.json.*;
/** User-authored command lists; never an alternate executor or confirmation bypass. */
final class RoutineStore {
 static final String FILE="routines.json";
 static JSONArray defaults()throws Exception{return new JSONArray()
  .put(new JSONObject().put("phrase","I'm leaving").put("commands","what is the weather\nwhat is my battery level"))
  .put(new JSONObject().put("phrase","good night").put("commands","am I charging\nwhat alarms are set"))
  .put(new JSONObject().put("phrase","what did I miss").put("commands","read my notifications"));}
 static synchronized JSONArray all(Context c){try{return new JSONArray(SecureStore.read(c,FILE,defaults().toString()));}catch(Exception e){return new JSONArray();}}
 static String key(String s){return WakePolicy.normalize(s);}
 static JSONObject match(Context c,String phrase){JSONArray a=all(c);for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&key(r.optString("phrase")).equals(key(phrase)))return r;}return null;}
 static synchronized void save(Context c,int index,String phrase,String commands)throws Exception{
  phrase=phrase.trim();commands=commands.trim();String[] steps=commands.split("\r?\n");
  if(key(phrase).isEmpty()||phrase.length()>80||commands.length()>1500||steps.length<1||steps.length>6)throw new IllegalArgumentException("Use a phrase up to 80 characters and 1–6 commands.");
  for(String step:steps)if(step.trim().isEmpty()||key(step).equals(key(phrase)))throw new IllegalArgumentException("Each step needs a command different from the routine phrase.");
  JSONArray a=all(c);if(a.length()>=20&&index<0)throw new IllegalArgumentException("Keep at most 20 routines.");
  for(int i=0;i<a.length();i++)if(i!=index&&key(a.getJSONObject(i).getString("phrase")).equals(key(phrase)))throw new IllegalArgumentException("That phrase already exists.");
  JSONObject r=new JSONObject().put("phrase",phrase).put("commands",commands);if(index<0)a.put(r);else a.put(index,r);SecureStore.write(c,FILE,a.toString());
 }
 static synchronized void remove(Context c,int index)throws Exception{JSONArray a=all(c);a.remove(index);SecureStore.write(c,FILE,a.toString());}
}
