package com.iris.assistant;

/** Monotonic capture deadlines; silence samples count as data, absent samples do not. */
final class RecordingDeadline {
    private final long end;
    private long lastData;
    RecordingDeadline(long start,int durationMs){end=start+durationMs+3000L;lastData=start;}
    void received(long now){lastData=now;}
    boolean expired(long now){return now>=end || now-lastData>=3000;}
}
