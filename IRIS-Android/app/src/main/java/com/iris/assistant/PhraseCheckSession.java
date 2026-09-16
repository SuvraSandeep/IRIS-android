package com.iris.assistant;

/** A phrase preview cannot grant enrollment or save identity on its own. */
final class PhraseCheckSession {
    private enum State {OFF,CHECKING,READY}
    private State state=State.OFF;
    void begin(){state=State.CHECKING;}
    boolean checking(){return state!=State.OFF;}
    boolean passed(){return state==State.READY;}
    boolean recognize(String expected,String heard){
        if(state!=State.CHECKING||!PhraseEvidence.complete(expected,heard))return false;
        state=State.READY;return true;
    }
    void startEnrollment(){if(state!=State.READY)throw new IllegalStateException("Check the complete phrase before enrollment");state=State.OFF;}
    void clear(){state=State.OFF;}
}
