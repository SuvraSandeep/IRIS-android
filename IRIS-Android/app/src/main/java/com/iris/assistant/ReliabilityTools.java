package com.iris.assistant;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;
final class ReliabilityTools {
 static boolean unlocked(Activity a){android.app.KeyguardManager k=(android.app.KeyguardManager)a.getSystemService(Context.KEYGUARD_SERVICE);if(k==null||k.isKeyguardLocked()){Toast.makeText(a,"Unlock your phone to review this.",Toast.LENGTH_LONG).show();return false;}return true;}
 static void dashboard(Activity a,Runnable feedback){
  TextView t=new TextView(a);t.setPadding(24,16,24,16);t.setTextIsSelectable(true);ScrollView scroll=new ScrollView(a);scroll.addView(t);
  AlertDialog d=new AlertDialog.Builder(a).setTitle("Wake reliability").setView(scroll).setPositiveButton("Close",null).setNegativeButton("Missed me",(x,w)->feedback.run()).setNeutralButton("Share diagnostics",null).create();
  Handler h=new Handler(Looper.getMainLooper());final boolean[] open={true};final String[] study={"Loading local study…"};
  new Thread(()->{String report=WakeDiagnostics.report(a);h.post(()->{if(open[0])study[0]=report;});},"IRIS-DashboardStudy").start();
  Runnable refresh=new Runnable(){public void run(){if(!open[0])return;t.setText("Listening service: "+(IrisListeningService.isRunning?"on":"off")+"\n"+VoiceHealth.snapshot()+"\n\n"+study[0]);h.postDelayed(this,2000);}};
  d.setOnDismissListener(x->{open[0]=false;h.removeCallbacksAndMessages(null);});d.show();refresh.run();
  d.getButton(-3).setOnClickListener(v->{Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"IRIS 13.0.0 diagnostics\n"+VoiceHealth.snapshot()+"\n"+study[0]+"\n"+VoiceEndurance.report());a.startActivity(Intent.createChooser(share,"Share metadata diagnostics"));});
 }
 static void history(Activity a,java.util.function.Consumer<String> run){if(!unlocked(a))return;
  JSONArray rows=CommandHistory.recent(a);String[] items=new String[rows.length()];for(int i=0;i<items.length;i++)items[i]=rows.optJSONObject(rows.length()-1-i).optString("heard");
  new AlertDialog.Builder(a).setTitle("Heard · understood · response").setItems(items,(d,i)->{JSONObject r=rows.optJSONObject(rows.length()-1-i);try(ActionLedger ledger=new ActionLedger(a)){
   new AlertDialog.Builder(a).setTitle(r.optString("heard")).setMessage("Understood: "+r.optString("understood")+"\nResponse: "+r.optString("reply")+"\n\nActual action ledger (separate, latest first):\n"+ledger.timeline(8)+"\nA spoken response is not proof of completion.")
    .setPositiveButton("Close",null).setNeutralButton("Review retry",(x,w)->new AlertDialog.Builder(a).setTitle("Retry through normal checks?").setMessage(r.optString("heard")).setPositiveButton("Retry",(z,b)->run.accept(r.optString("heard"))).setNegativeButton("Cancel",null).show()).show();}})
   .setPositiveButton("Close",null).setNeutralButton(CommandHistory.enabled(a)?"Pause history":"Enable history",(d,w)->{CommandHistory.enable(a,!CommandHistory.enabled(a));Toast.makeText(a,"History is "+(CommandHistory.enabled(a)?"on: encrypted transcripts, 7 days / 100 entries":"off"),Toast.LENGTH_LONG).show();})
   .setNegativeButton("Erase",(d,w)->new AlertDialog.Builder(a).setMessage("Erase command history and action ledger?").setPositiveButton("Erase",(x,y)->{CommandHistory.clear(a);try(ActionLedger l=new ActionLedger(a)){l.clearAll();}}).setNegativeButton("Cancel",null).show()).show();
 }
 static void routines(Activity a,java.util.function.Consumer<String> run){if(!unlocked(a))return;JSONArray rows=RoutineStore.all(a);String[] labels=new String[rows.length()];for(int i=0;i<labels.length;i++)labels[i]=rows.optJSONObject(i).optString("phrase");
  new AlertDialog.Builder(a).setTitle("Routines · review each action").setItems(labels,(d,i)->new AlertDialog.Builder(a).setTitle(labels[i]).setMessage(rows.optJSONObject(i).optString("commands")).setPositiveButton("Review actions",(x,w)->preview(a,rows.optJSONObject(i),run)).setNeutralButton("Edit",(x,w)->edit(a,i,rows.optJSONObject(i))).setNegativeButton("Close",null).show()).setPositiveButton("New routine",(d,w)->edit(a,-1,new JSONObject())).setNegativeButton("Close",null).show();
 }
 static void preview(Activity a,JSONObject r,java.util.function.Consumer<String> run){if(!unlocked(a)||r==null)return;String[] steps=r.optString("commands").split("\n");new AlertDialog.Builder(a).setTitle(r.optString("phrase")+" · choose an action").setItems(steps,(d,i)->new AlertDialog.Builder(a).setTitle("Run this action?").setMessage(steps[i]+"\n\nNormal clarification and sensitive-action confirmation still apply. Reopen the routine for the next step.").setPositiveButton("Run",(x,w)->run.accept(steps[i])).setNegativeButton("Cancel",null).show()).setNegativeButton("Close",null).show();}
 static void edit(Activity a,int index,JSONObject r){LinearLayout box=new LinearLayout(a);box.setOrientation(1);EditText phrase=new EditText(a),commands=new EditText(a);phrase.setHint("When I say…");phrase.setText(r.optString("phrase"));commands.setHint("One command per line (up to six)");commands.setMinLines(3);commands.setText(r.optString("commands"));box.addView(phrase);box.addView(commands);
  AlertDialog d=new AlertDialog.Builder(a).setTitle("Edit routine").setView(box).setPositiveButton("Save",null).setNegativeButton("Cancel",null).setNeutralButton("Delete",(x,w)->{if(index>=0)try{RoutineStore.remove(a,index);}catch(Exception e){Toast.makeText(a,"Could not delete routine",1).show();}}).create();d.show();d.getButton(-1).setOnClickListener(v->{try{RoutineStore.save(a,index,phrase.getText().toString(),commands.getText().toString());d.dismiss();}catch(Exception e){phrase.setError(e.getMessage());}});
 }
}
