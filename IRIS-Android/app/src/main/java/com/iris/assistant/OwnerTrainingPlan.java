package com.iris.assistant;

/** Enrollment and held-out verification are distinct; verification never trains a centroid. */
final class OwnerTrainingPlan {
    static final int NORMAL=5, QUIET=5, VERIFY_NORMAL=2, VERIFY_QUIET=2;
    static final int ENROLLMENT=NORMAL+QUIET,TOTAL=ENROLLMENT+VERIFY_NORMAL+VERIFY_QUIET;
    static boolean quiet(int index){return index>=NORMAL&&index<ENROLLMENT || index>=ENROLLMENT+VERIFY_NORMAL;}
    static boolean verification(int index){return index>=ENROLLMENT;}
    static String label(int index){
        if(index>=TOTAL)return "All "+TOTAL+" takes verified";
        if(index<NORMAL)return "Normal voice "+(index+1)+" of "+NORMAL;
        if(index<ENROLLMENT)return "Quiet voice "+(index-NORMAL+1)+" of "+QUIET;
        return (quiet(index)?"Quiet":"Normal")+" verification "+(quiet(index)?index-ENROLLMENT-VERIFY_NORMAL+1:index-ENROLLMENT+1)+" of 2";
    }
}
