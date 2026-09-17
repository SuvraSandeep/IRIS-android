package com.iris.assistant;

/** UI stage and bounded operation clock, independent of recognizer callbacks. */
final class OwnerTrainingStage {
    enum Kind {IDLE, SPEECH_MODEL, SPEAKER_MODEL, READY, MICROPHONE, RECORDING, ANALYSIS, RETRY, PHRASE_READY, REVIEW, SAVED, SAVE_FAILED, FAILED}
    private Kind kind=Kind.IDLE;
    private String message="Ready to prepare voice training";
    private long began,deadline;
    void enter(Kind next,String text,long now,long timeoutMs){kind=next;message=text;began=now;deadline=timeoutMs>0?now+timeoutMs:0;}
    Kind kind(){return kind;}
    String message(){return message;}
    boolean expired(long now){return deadline>0&&now>=deadline;}
    long elapsedSeconds(long now){return Math.max(0,now-began)/1000;}
    boolean busy(){return kind==Kind.SPEECH_MODEL||kind==Kind.SPEAKER_MODEL||kind==Kind.MICROPHONE||kind==Kind.RECORDING||kind==Kind.ANALYSIS;}
    String title(){switch(kind){
        case SPEECH_MODEL:return "Preparing speech model";
        case SPEAKER_MODEL:return "Preparing speaker model";
        case READY:return "Get ready";
        case MICROPHONE:return "Opening microphone";
        case RECORDING:return "Recording";
        case ANALYSIS:return "Checking your recording";
        case RETRY:return "Take not accepted";
        case PHRASE_READY:return "Phrase check passed";
        case REVIEW:return "Ready to save";
        case SAVED:return "Owner voice saved";
        case SAVE_FAILED:return "Save failed \u2014 your takes are kept";
        case FAILED:return "Training stopped";
        default:return "Ready to train";
    }}
}
