package com.iris.assistant;
public final class IrisVoiceSessionService extends android.service.voice.VoiceInteractionSessionService {
 @Override public android.service.voice.VoiceInteractionSession onNewSession(android.os.Bundle args){
  return new android.service.voice.VoiceInteractionSession(this){
   @Override public android.view.View onCreateContentView(){android.widget.Button b=new android.widget.Button(IrisVoiceSessionService.this);b.setText("Talk to IRIS");b.setOnClickListener(v->{try{startAssistantActivity(new android.content.Intent(IrisVoiceSessionService.this,MainActivity.class).setAction(android.content.Intent.ACTION_ASSIST).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));finish();}catch(Exception e){b.setText("Unlock your phone and open IRIS");}});return b;}
  };
 }
}
