package com.iris.assistant;
import java.util.Arrays;
/** One analysis in flight plus the newest pending utterance. PCM is wiped on every exit. */
final class WakeAnalysisQueue implements AutoCloseable {
    private static final class Job {
        final short[] pcm; final Runnable action;
        Job(short[] pcm,Runnable action){this.pcm=pcm;this.action=action;}
        void erase(){Arrays.fill(pcm,(short)0);}
    }
    private Job pending; private boolean closed; private final Thread worker;
    WakeAnalysisQueue(){worker=new Thread(this::run,"IRIS-RecordedWake");worker.start();}
    synchronized void offer(short[] pcm,Runnable action){
        if(closed){Arrays.fill(pcm,(short)0);return;}
        if(pending!=null)pending.erase();pending=new Job(pcm,action);notifyAll();
    }
    private void run(){
        while(true){
            Job job;
            synchronized(this){
                while(!closed&&pending==null)try{wait();}catch(InterruptedException ignored){}
                if(closed)return;job=pending;pending=null;
            }
            try{job.action.run();}finally{job.erase();}
        }
    }
    public synchronized void close(){
        closed=true;if(pending!=null){pending.erase();pending=null;}notifyAll();worker.interrupt();
    }
}
