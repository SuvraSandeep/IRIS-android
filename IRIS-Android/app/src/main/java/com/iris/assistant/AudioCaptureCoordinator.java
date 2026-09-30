package com.iris.assistant;

/** A lease is retained until the recorder has actually released its hardware. */
final class AudioCaptureCoordinator {
    private static Object owner;
    private static long acquiredAt;
    // Real bug this constant fixes: with no staleness tracking at all, a holder that never
    // reaches its own finally{release(lease)} (a crash, an uncaught error before the try block,
    // a killed worker thread) leaves the mic PERMANENTLY locked -- every future acquire() call
    // returns null forever, with no recovery except a full process restart. Every real caller
    // (ManagedSpeechService, TimedRecorder, TrainingAudioPreview) already bounds its OWN
    // recording length well under this window (TimedRecorder's longest watchdog is well under a
    // minute; ManagedSpeechService's own acquire spin-wait is 2.5s), so a lease genuinely still
    // held after this long is never a legitimate in-progress recording -- it is a leaked lease
    // from a holder that died without releasing, and reclaiming it is strictly safer than an
    // unrecoverable permanent lockout.
    private static final long STALE_AFTER_MS = 90_000;
    static synchronized Object acquire(){
        if(owner!=null){
            if(System.currentTimeMillis()-acquiredAt<STALE_AFTER_MS)return null;
            // The previous holder is stale -- almost certainly leaked, not still recording.
            // Reclaim rather than stay locked forever; the stale holder's own eventual
            // release(lease) call will safely no-op since it no longer matches the new owner.
        }
        owner=new Object();acquiredAt=System.currentTimeMillis();return owner;
    }
    static synchronized void release(Object lease){if(owner==lease)owner=null;}
    static synchronized boolean busy(){return owner!=null;}
}
