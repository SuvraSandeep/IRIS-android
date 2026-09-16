package com.iris.assistant;
import org.junit.Test;
import java.nio.*;
import static org.junit.Assert.*;
public class VoiceStudioTest {
 @Test public void diagnosticWavHasCorrectFormatAndSamples(){
  short[] pcm={-32768,0,32767};byte[] bytes=VoiceDiagnosticWav.encode(pcm);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  assertEquals(50,bytes.length);assertEquals(42,b.getInt(4));assertEquals(16000,b.getInt(24));assertEquals(1,b.getShort(22));assertEquals(6,b.getInt(40));assertEquals(-32768,b.getShort(44));assertEquals(32767,b.getShort(48));
  assertThrows(IllegalArgumentException.class,()->VoiceDiagnosticWav.encode(new short[0]));assertThrows(IllegalArgumentException.class,()->VoiceDiagnosticWav.encode(new short[160001]));
 }
 @Test public void freshProfilesDeclareTheirRecognizer()throws Exception {assertEquals("vosk-model-small-en-us-0.15",OwnerVoiceProfileTest.profile().data.getString("recognizerModel"));}
}
