package com.iris.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import org.vosk.Recognizer;

/** One bounded microphone/recognizer owner. stop() joins before a replacement can use the mic. */
final class WakeAudioCapture {
    interface Listener { void result(String json); void error(String message); }
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private Thread worker;
    private final Recognizer recognizer;
    private final Listener listener;
    WakeAudioCapture(Recognizer recognizer, Listener listener) {
        this.recognizer=recognizer; this.listener=listener;
    }
    void start() {
        running=true;
        worker=new Thread(this::loop,"IRIS-OwnerWake-Audio"); worker.start();
    }
    private void loop() {
        AudioRecord audio=null;
        try {
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0) throw new IllegalStateException("16 kHz microphone unavailable");
            audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,6400));
            recorder=audio;
            if(audio.getState()!=AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone initialization failed");
            if(!running)return;
            audio.startRecording();
            if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Microphone recording was blocked");
            short[] frame=new short[320]; QuietAudioProcessor gain=new QuietAudioProcessor();
            int samples=0, emptyReads=0;
            while(running) {
                int n=audio.read(frame,0,frame.length);
                if(!running)break;
                if(n<0)throw new IllegalStateException("Microphone read failed ("+n+")");
                if(n==0){if(++emptyReads>50)throw new IllegalStateException("Microphone stalled");continue;}
                emptyReads=0; gain.process(frame,n); samples+=n;
                if(recognizer.acceptWaveForm(frame,n)) {
                    listener.result(recognizer.getResult());
                    recognizer.reset(); samples=0;
                } else if(samples>=16000*8) {
                    // Bound silence/noise segments and native recognizer memory.
                    listener.result(recognizer.getFinalResult());
                    recognizer.reset(); samples=0;
                }
            }
        } catch(Throwable e) {
            if(running)listener.error(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        } finally {
            running=false;
            if(audio!=null){try{audio.stop();}catch(Exception ignored){} audio.release();}
            recorder=null; recognizer.close();
        }
    }
    boolean stop() {
        running=false; AudioRecord a=recorder;
        if(a!=null)try{a.stop();}catch(Exception ignored){}
        if(worker!=null && worker!=Thread.currentThread())try{worker.join(750);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        return worker==null || !worker.isAlive();
    }
    void awaitClosed() {
        if(worker!=null && worker!=Thread.currentThread())try{worker.join();}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
