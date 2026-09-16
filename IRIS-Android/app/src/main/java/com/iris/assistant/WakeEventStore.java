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
    private static final android.os.Handler expiry=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final ArrayDeque<Event> events=new ArrayDeque<>();
    static synchronized void add(String reason,String revision,float[] vector,boolean accepted){prune();Event event=new Event(reason,revision,vector,accepted);events.addFirst(event);expiry.postDelayed(()->expire(event),120000);while(events.size()>12)erase(events.removeLast());}
    static synchronized List<Event> recent(){prune();return new ArrayList<>(events);}
    private static synchronized void expire(Event e){events.remove(e);erase(e);}
    private static void erase(Event e){if(e.embedding!=null)Arrays.fill(e.embedding,0);}
    private static void prune(){long now=SystemClock.elapsedRealtime();java.util.Iterator<Event> it=events.iterator();while(it.hasNext()){Event e=it.next();if(now-e.at>=120000){it.remove();erase(e);}}}
}
