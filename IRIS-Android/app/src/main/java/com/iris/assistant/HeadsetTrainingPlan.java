package com.iris.assistant;

/** Adds a second route to an already-verified phone-route identity, so it does not need to
 *  re-establish "who the owner is" from scratch — only that this route's audio reliably matches
 *  the wake sound and the same owner. Enrollment count matches SoundWakeProfile's per-group
 *  floor of 5 normal + 5 quiet examples exactly; verification count matches the phone-route
 *  plan's 4 held-out takes for the same held-out-validation guarantee. */
final class HeadsetTrainingPlan {
    static final int NORMAL=5, QUIET=5, VERIFY_NORMAL=2, VERIFY_QUIET=2;
    static final int ENROLLMENT=NORMAL+QUIET, TOTAL=ENROLLMENT+VERIFY_NORMAL+VERIFY_QUIET;
    static boolean quiet(int index){return index>=NORMAL&&index<ENROLLMENT || index>=ENROLLMENT+VERIFY_NORMAL;}
    static boolean verification(int index){return index>=ENROLLMENT;}
    static String label(int index){
        if(index>=TOTAL)return "All "+TOTAL+" headset takes verified";
        if(index<NORMAL)return "Headset normal voice "+(index+1)+" of "+NORMAL;
        if(index<ENROLLMENT)return "Headset quiet voice "+(index-NORMAL+1)+" of "+QUIET;
        return "Headset "+(quiet(index)?"quiet":"normal")+" verification "+(quiet(index)?index-ENROLLMENT-VERIFY_NORMAL+1:index-ENROLLMENT+1)+" of 2";
    }
}
