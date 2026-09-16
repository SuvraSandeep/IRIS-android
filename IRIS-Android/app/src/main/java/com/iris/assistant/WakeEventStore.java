package com.iris.assistant;

import android.os.SystemClock;
import java.util.*;

/** Bounded process-local evidence. Never writes ambient recordings or vectors to logs/disk. */
final class WakeEventStore {
    static final class Event {
        final String id=UUID.randomUUID().toString(),reason,revision,route;
        final long at=SystemClock.elapsedRealtime();final float[] embedding;final boolean accepted;
        Event(String reason,String revision,float[] embedding,boolean accepted){this.reason=reason;this.revision=revision;this.embedding=embedding==null?null:embedding.clone();this.accepted=accepted;route=AudioRouteController.observed;}
    }
    private static final ArrayDeque<Event> events=new ArrayDeque<>();
    static synchronized void add(String reason,String revision,float[] vector,boolean accepted){prune();events.addFirst(new Event(reason,revision,vector,accepted));while(events.size()>12)events.removeLast();}
    static synchronized List<Event> recent(){prune();return new ArrayList<>(events);}
    private static void prune(){long now=SystemClock.elapsedRealtime();events.removeIf(e->now-e.at>120000);}
}
