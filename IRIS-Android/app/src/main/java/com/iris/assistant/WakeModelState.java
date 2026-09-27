package com.iris.assistant;
/** Loading is recoverable. A fingerprint is authoritative only after required models are ready. */
final class WakeModelState {
    enum State {LOADING_VOSK,LOADING_OWNER,MODEL_CHANGED,READY}
    static State evaluate(boolean speech,boolean speaker,boolean enhanced,boolean ownerReady,boolean fingerprintsMatch){
        if(!speech||!speaker)return State.LOADING_VOSK;
        if(enhanced&&!ownerReady)return State.LOADING_OWNER;
        return fingerprintsMatch?State.READY:State.MODEL_CHANGED;
    }
}
