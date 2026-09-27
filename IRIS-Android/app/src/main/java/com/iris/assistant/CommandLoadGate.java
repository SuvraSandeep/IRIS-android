package com.iris.assistant;
/** A model becoming ready is not permission to open an unrelated command window. */
final class CommandLoadGate {
    private long pending=-1;
    void request(long epoch){pending=epoch;}
    void cancel(){pending=-1;}
    boolean consume(long epoch){if(pending<0||pending!=epoch)return false;pending=-1;return true;}
}
