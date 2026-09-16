package com.iris.assistant;

/** A lease is retained until the recorder has actually released its hardware. */
final class AudioCaptureCoordinator {
    private static Object owner;
    static synchronized Object acquire(){if(owner!=null)return null;return owner=new Object();}
    static synchronized void release(Object lease){if(owner==lease)owner=null;}
    static synchronized boolean busy(){return owner!=null;}
}
