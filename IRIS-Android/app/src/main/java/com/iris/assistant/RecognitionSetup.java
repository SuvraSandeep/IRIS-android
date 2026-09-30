package com.iris.assistant;
import android.app.*;
import android.content.*;
import android.os.*;
import android.speech.*;
/** Foreground capability probe; never starts recording. */
final class RecognitionSetup {
 static void show(Activity a){
  if(Build.VERSION.SDK_INT<33){new AlertDialog.Builder(a).setTitle("Offline command languages").setMessage("Android 13 or newer is needed to query installed speech languages. IRIS's bundled Vosk English model remains available. System recognition may use the network depending on its provider.").setPositiveButton("Close",null).show();return;}
  if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(a)){new AlertDialog.Builder(a).setMessage("Android reports no on-device recognition service. Use IRIS offline recognition or install a supported system speech provider.").setPositiveButton("Close",null).show();return;}
  SpeechRecognizer sr=SpeechRecognizer.createOnDeviceSpeechRecognizer(a);
  Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,new AppSettings(a).languageTag()).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
  AlertDialog d=new AlertDialog.Builder(a).setTitle("Offline command languages").setMessage("Checking installed models…").setPositiveButton("Close",null).setNeutralButton("Request language model",null).create();
  Handler h=new Handler(Looper.getMainLooper());final boolean[] closed={false};
  d.setOnDismissListener(x->{closed[0]=true;h.removeCallbacksAndMessages(null);sr.destroy();});d.show();
  d.getButton(-3).setOnClickListener(v->{try{sr.triggerModelDownload(intent);d.setMessage("Model download requested for "+new AppSettings(a).languageTag()+". Your speech provider may ask for approval. Reopen this screen to check installation; a request is not a completed download.");}catch(Exception e){d.setMessage("The speech provider could not start the download.");}});
  h.postDelayed(()->{if(!closed[0])d.setMessage("The provider did not answer within 10 seconds. Offline support is unknown; IRIS's bundled model remains available.");},10000);
  try{sr.checkRecognitionSupport(intent,a.getMainExecutor(),new RecognitionSupportCallback(){
   public void onSupportResult(RecognitionSupport s){if(closed[0])return;h.removeCallbacksAndMessages(null);d.setMessage("Selected: "+new AppSettings(a).languageTag()+"\nInstalled: "+s.getInstalledOnDeviceLanguages()+"\nPending: "+s.getPendingOnDeviceLanguages()+"\nDownloadable: "+s.getSupportedOnDeviceLanguages()+"\n\nChoose en-IN for Indian English when supported. Installed language support does not measure accent accuracy.");}
   public void onError(int error){if(!closed[0]){h.removeCallbacksAndMessages(null);d.setMessage("Provider could not report support ("+error+"). Availability is unknown.");}}
  });}catch(Exception e){d.setMessage("This provider does not support language checks.");}
 }
}
