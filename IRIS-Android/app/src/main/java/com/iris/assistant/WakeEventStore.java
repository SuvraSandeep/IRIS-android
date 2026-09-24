package com.iris.assistant;

import android.os.SystemClock;
import java.util.*;

/** Bounded process-local sound features and speaker evidence for authenticated feedback.
 * Raw microphone recordings are never retained here. Evidence expires after two minutes. */
final class WakeEventStore {
    static final class Event {
        final String id=UUID.randomUUID().toString(),revision,route;
        volatile String reason;
        final float[][] pattern; final AudioRouteController.Route input;
        final long at=SystemClock.elapsedRealtime();final float[] ecapaEmbedding,voskEmbedding;volatile boolean accepted;
        Event(String reason,String revision,float[] ecapaEmbedding,float[] voskEmbedding,boolean accepted,float[][] pattern,AudioRouteController.Route input){
            this.reason=reason;this.revision=revision;this.input=input;
            this.pattern=copy(pattern);
            this.ecapaEmbedding=ecapaEmbedding==null?null:ecapaEmbedding.clone();
            this.voskEmbedding=voskEmbedding==null?null:voskEmbedding.clone();
            this.accepted=accepted;route=input.name();
        }
    }
    private static final android.os.Handler expiry=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final ArrayDeque<Event> events=new ArrayDeque<>();
    static synchronized Event add(String reason,String revision,float[] ecapaVector,float[] voskVector,boolean accepted){
        return add(reason,revision,ecapaVector,voskVector,accepted,null,AudioRouteController.Route.UNCONFIRMED);
    }
    static synchronized Event add(String reason,String revision,float[] ecapaVector,float[] voskVector,boolean accepted,float[][] pattern,AudioRouteController.Route input){
        prune();Event event=new Event(reason,revision,ecapaVector,voskVector,accepted,pattern,input);events.addFirst(event);
        expiry.postDelayed(()->expire(event),120000);while(events.size()>12)erase(events.removeLast());return event;
    }
    static synchronized void outcome(Event event,String reason,boolean accepted){
        if(event!=null&&events.contains(event)){event.reason=reason;event.accepted=accepted;}
    }
    static synchronized List<Event> recent(){prune();return new ArrayList<>(events);}
    private static synchronized void expire(Event e){events.remove(e);erase(e);}
    static float[][] copy(float[][] pattern){
        if(!SoundPattern.valid(pattern))return null;
        float[][] out=new float[pattern.length][];for(int i=0;i<out.length;i++)out[i]=pattern[i].clone();return out;
    }
    private static void erase(Event e){if(e.pattern!=null)for(float[] row:e.pattern)Arrays.fill(row,0);if(e.ecapaEmbedding!=null)Arrays.fill(e.ecapaEmbedding,0);if(e.voskEmbedding!=null)Arrays.fill(e.voskEmbedding,0);}
    private static void prune(){long now=SystemClock.elapsedRealtime();java.util.Iterator<Event> it=events.iterator();while(it.hasNext()){Event e=it.next();if(now-e.at>=120000){it.remove();erase(e);}}}
}
