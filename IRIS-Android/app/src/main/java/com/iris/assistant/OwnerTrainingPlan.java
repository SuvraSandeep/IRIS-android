package com.iris.assistant;
/** Four enrollment takes plus four independent phrase AND owner checks. */
final class OwnerTrainingPlan {
    static final int ENROLLMENT=4, VERIFY=4, TOTAL=ENROLLMENT+VERIFY;
    static boolean verification(int index){return index>=ENROLLMENT;}
    static String coaching(int index){
        String[] prompts={"Use your everyday voice.","Use a comfortable softer voice; do not force a whisper.","Use your natural quicker pace, keeping the whole phrase.","Use a relaxed voice at your usual phone distance.","Fresh check: everyday voice.","Fresh check: comfortable softer voice.","Fresh check: natural quicker pace.","Fresh check: relaxed voice, slightly farther away if comfortable."};
        return index>=0&&index<prompts.length?prompts[index]:"All independent checks complete.";
    }
    static String label(int index){
        if(index>=TOTAL)return "All "+TOTAL+" takes verified";
        return (verification(index)?"Verification ":"Phrase sample ")+(verification(index)?index-ENROLLMENT+1:index+1)+" of "+(verification(index)?VERIFY:ENROLLMENT);
    }
}
