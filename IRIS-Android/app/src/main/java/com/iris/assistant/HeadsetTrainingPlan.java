package com.iris.assistant;
/** Four enrollment takes plus four independent phrase AND owner checks. */
final class HeadsetTrainingPlan {
    static final int ENROLLMENT=4, VERIFY=4, TOTAL=ENROLLMENT+VERIFY;
    static boolean verification(int index){return index>=ENROLLMENT;}
    static String label(int index){
        if(index>=TOTAL)return "All "+TOTAL+" headset takes verified";
        return (verification(index)?"Verification ":"Phrase sample ")+(verification(index)?index-ENROLLMENT+1:index+1)+" of "+(verification(index)?VERIFY:ENROLLMENT);
    }
}
