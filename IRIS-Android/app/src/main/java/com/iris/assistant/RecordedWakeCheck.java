package com.iris.assistant;
import java.util.List;
/** One decision rule for held-out training, manual testing, and background wake. */
final class RecordedWakeCheck {
    static String guidance(String reason){
        if("PHRASE_MISMATCH".equals(reason))return "The recorded sound differed from your examples. Use the same complete phrase and a natural, steady pace.";
        if("OWNER_REJECTED".equals(reason))return "The speaker match was below your owner-verification setting. Keep the microphone at the same distance and use your normal voice.";
        if("SPEAKER_EVIDENCE".equals(reason))return "The sound was too short or unclear to verify the speaker. Say the complete phrase at a comfortable pace.";
        if("AUDIO_QUALITY".equals(reason))return "No complete usable phrase was captured. Wait for Listening, say the phrase, then pause.";
        if("HEADSET_PROFILE_REQUIRED".equals(reason))return "Add a headset voice profile, or select Phone microphone.";
        return reason;
    }
    static String diagnostic(float[][] pattern,List<float[][]> phrases,double threshold,float[] speaker,float[] enrolled,double policy){
        return String.format(java.util.Locale.ROOT,"Phrase distance %.4f / maximum %.4f; speaker similarity %.4f / minimum %.4f",
            SoundPattern.score(pattern,phrases),threshold,WakePolicy.cosine(speaker,enrolled),policy);
    }
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
