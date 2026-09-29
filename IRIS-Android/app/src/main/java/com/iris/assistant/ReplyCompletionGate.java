package com.iris.assistant;
/** Serializes reply completion; late TTS callbacks must not finish a newer command. */
final class ReplyCompletionGate {
    private long generation; private boolean pending;
    synchronized long begin(){pending=true;return ++generation;}
    synchronized boolean complete(long token){if(!pending||token!=generation)return false;pending=false;return true;}
    synchronized void cancel(){generation++;pending=false;}
}
