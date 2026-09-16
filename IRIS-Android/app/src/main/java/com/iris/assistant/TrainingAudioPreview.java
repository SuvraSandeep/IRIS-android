package com.iris.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import java.util.function.Consumer;

/** Offline preview with a shared microphone lease, bounded lifetime and stale-callback guards. */
final class TrainingAudioPreview {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech tts;private AudioTrack track;private Object lease;
    private int generation;private boolean busy;private Consumer<String> completion;
    TrainingAudioPreview(Context context){this.context=context.getApplicationContext();}
    boolean busy(){return busy;}
    void speak(String text,Consumer<String> done){
        int id=begin(done);if(id<0)return;
        if(text==null||text.trim().isEmpty()||text.length()>200){finish(id,"Enter a phrase of 1–200 characters first.");return;}
        try{tts=new TextToSpeech(context,status->main.post(()->{
            if(id!=generation||!busy)return;
            if(status!=TextToSpeech.SUCCESS){finish(id,"Offline speech playback is unavailable.");return;}
            try{
                Voice chosen=null;
                if(tts.getVoices()!=null)for(Voice voice:tts.getVoices()){
                    if(voice.isNetworkConnectionRequired()||!"en".equals(voice.getLocale().getLanguage()))continue;
                    if(voice.getFeatures()!=null&&voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))continue;
                    if(chosen==null||"IN".equals(voice.getLocale().getCountry()))chosen=voice;
                }
                if(chosen==null||tts.setVoice(chosen)==TextToSpeech.ERROR){finish(id,"Install an offline English voice in Android Text-to-speech settings, then retry. No text was sent to a cloud voice.");return;}
                tts.setSpeechRate(.9f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    public void onStart(String utterance){}
                    public void onDone(String utterance){main.post(()->finish(id,"Example finished. Speak in your own natural voice."));}
                    public void onError(String utterance){main.post(()->finish(id,"Could not play the offline example. Check the installed voice."));}
                });
                if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"training-"+id)==TextToSpeech.ERROR)finish(id,"Offline playback could not start.");
            }catch(Exception error){finish(id,"Offline playback is unavailable on this device.");}
        }));}catch(Exception error){finish(id,"Offline playback could not initialize.");}
    }
    void play(short[] pcm,Consumer<String> done){
        int id=begin(done);if(id<0)return;
        if(pcm==null||pcm.length==0||pcm.length>16000*12){finish(id,"No diagnostic recording is available.");return;}
        try{
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length*2).build();
            if(track.write(pcm,0,pcm.length)!=pcm.length)throw new IllegalStateException("Incomplete playback buffer");
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener(){
                public void onMarkerReached(AudioTrack audio){finish(id,"Recording playback finished.");}
                public void onPeriodicNotification(AudioTrack audio){}
            },main);
            track.setNotificationMarkerPosition(pcm.length);track.play();
        }catch(Exception error){finish(id,"Could not play the diagnostic recording.");}
    }
    private int begin(Consumer<String> done){
        if(busy){done.accept("Playback is already active.");return -1;}
        lease=AudioCaptureCoordinator.acquire();
        if(lease==null){done.accept("The microphone is still stopping. Try playback again in a moment.");return -1;}
        busy=true;completion=done;int id=++generation;
        main.postDelayed(()->finish(id,"Playback timed out. Check the offline voice or audio output."),20000);return id;
    }
    private void finish(int id,String message){
        if(!busy||id!=generation)return;
        main.removeCallbacksAndMessages(null);releaseAudio();
        // Keep the lease during a short output tail; never use this timer instead of completion.
        main.postDelayed(()->{
            if(id!=generation)return;AudioCaptureCoordinator.release(lease);lease=null;busy=false;
            Consumer<String> done=completion;completion=null;if(done!=null)done.accept(message);
        },500);
    }
    private void releaseAudio(){
        if(tts!=null){try{tts.stop();tts.shutdown();}catch(Exception ignored){}tts=null;}
        if(track!=null){try{track.stop();}catch(Exception ignored){}try{track.release();}catch(Exception ignored){}track=null;}
    }
    void stop(){if(busy)finish(generation,"Playback stopped.");}
}
