package com.iris.assistant;
import java.nio.*;
/** One explicitly requested take; standard mono PCM16 WAV for local playback/sharing. */
final class VoiceDiagnosticWav {
    static byte[] encode(short[] pcm){
        if(pcm==null||pcm.length==0||pcm.length>16000*8)throw new IllegalArgumentException("No bounded diagnostic take");
        ByteBuffer b=ByteBuffer.allocate(44+pcm.length*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+pcm.length*2).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length*2);
        for(short v:pcm)b.putShort(v);return b.array();
    }
}
