package com.iris.assistant;
import java.util.List;
/** One decision rule for held-out training, manual testing, and background wake. */
final class RecordedWakeCheck {
    static String reject(float[][] pattern,List<float[][]> phrases,double phraseThreshold,
                         float[] speaker,float[] enrolled,double ownerThreshold){
        if(!SoundPattern.valid(pattern))return "AUDIO_QUALITY";
        if(!Double.isFinite(phraseThreshold)||phraseThreshold<.025||phraseThreshold>.32)return "INVALID_PHRASE_PROFILE";
        if(SoundPattern.score(pattern,phrases)>phraseThreshold)return "PHRASE_MISMATCH";
        if(!WakePolicy.owner(speaker,speaker,.99))return "SPEAKER_EVIDENCE";
        if(!WakePolicy.owner(speaker,enrolled,ownerThreshold))return "OWNER_REJECTED";
        return "";
    }
}
