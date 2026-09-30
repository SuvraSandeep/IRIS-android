package com.iris.assistant;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.*;
public class ModelReplyGateTest {
 @Test public void expiredOwnerPassCannotCompleteNextRequest(){ModelReplyGate<String> g=new ModelReplyGate<>();ModelReplyGate.Ticket<String> old=g.begin();g.expire(old.id);ModelReplyGate.Ticket<String> next=g.begin();assertFalse(g.complete(old.id,"owner accepted"));assertFalse(next.result.isDone());assertTrue(g.complete(next.id,"owner rejected"));assertEquals("owner rejected",next.result.join());}
 @Test public void disconnectWakesWaiterAsFailure(){ModelReplyGate<String> g=new ModelReplyGate<>();ModelReplyGate.Ticket<String> t=g.begin();g.fail(new IllegalStateException("worker died"));assertThrows(CompletionException.class,t.result::join);assertFalse(g.complete(t.id,"accepted"));}
 @Test public void fiveHundredCyclesCannotAcceptDuplicatedReplies(){ModelReplyGate<String> g=new ModelReplyGate<>();for(int i=0;i<500;i++){ModelReplyGate.Ticket<String> t=g.begin();assertTrue(g.complete(t.id,"ok"));assertFalse(g.complete(t.id,"late"));assertEquals("ok",t.result.join());}}
}
