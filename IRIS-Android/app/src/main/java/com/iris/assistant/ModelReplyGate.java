package com.iris.assistant;
import java.util.concurrent.CompletableFuture;
/** Request identity remains independent of Binder reconnects and native completion order. */
final class ModelReplyGate<T> {
 static final class Ticket<T>{final int id;final CompletableFuture<T> result=new CompletableFuture<>();Ticket(int id){this.id=id;}}
 private int serial;private Ticket<T> active;
 synchronized Ticket<T> begin(){fail(new IllegalStateException("Request superseded"));return active=new Ticket<>(++serial);}
 synchronized boolean complete(int id,T value){if(active==null||id!=active.id)return false;Ticket<T> t=active;active=null;return t.result.complete(value);}
 synchronized void fail(Exception error){if(active!=null){Ticket<T> t=active;active=null;t.result.completeExceptionally(error);}}
 synchronized void expire(int id){if(active!=null&&active.id==id)fail(new IllegalStateException("Request expired"));}
}
