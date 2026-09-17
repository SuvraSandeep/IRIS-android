package com.iris.assistant;

/** Phrase-independent identity collection; final wake verification still requires the phrase. */
final class OwnerEnrollmentPolicy {
    private static final String[] PROMPTS={
        "Today I am teaching my assistant to recognize my natural voice.",
        "I can speak comfortably and take a short pause between sentences.",
        "Please remember the sound of my voice when I speak to you.",
        "The morning is a good time to make plans for the day.",
        "I would like my assistant to listen carefully when I need help."};
    static boolean identityTake(boolean preview,int index){return !preview&&index>=0&&index<OwnerTrainingPlan.ENROLLMENT;}
    static String prompt(int index){return PROMPTS[Math.floorMod(index,PROMPTS.length)];}
    static String rejectIdentity(short[] pcm,float[] embedding){
        if(!TrainingAudioQuality.measure(pcm).enrollmentUsable())return "Not enough clear speech. Read the sentence naturally, then pause. Check the microphone or replay a diagnostic take.";
        if(!WakePolicy.owner(embedding,embedding,.99))return "Speech was captured, but the voice encoder could not extract a usable sample. Try again at a comfortable volume.";
        return "";
    }
}
