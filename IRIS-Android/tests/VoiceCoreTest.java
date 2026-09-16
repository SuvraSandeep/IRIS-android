package com.iris.assistant;
import java.util.*;
public class VoiceCoreTest {
 public static void main(String[] args)throws Exception{
  if(PhraseEvidence.complete("hello iris","hello"))throw new AssertionError("incomplete phrase");
  if(PhraseEvidence.complete("hello iris","hello iris call"))throw new AssertionError("extra words");
  if(!PhraseEvidence.complete("Hello Iris","hello, iris!"))throw new AssertionError("normalization");
  Object token=AudioCaptureCoordinator.acquire();if(token==null||AudioCaptureCoordinator.acquire()!=null)throw new AssertionError("lease");AudioCaptureCoordinator.release(new Object());if(!AudioCaptureCoordinator.busy())throw new AssertionError("foreign release");AudioCaptureCoordinator.release(token);
  SpeechEndpoint end=new SpeechEndpoint();short[] silence=new short[320],speech=new short[320];Arrays.fill(speech,(short)1200);
  for(int i=0;i<150;i++)if(end.add(silence,320))throw new AssertionError("silence endpoint");
  for(int i=0;i<30;i++)if(end.add(speech,320))throw new AssertionError("speech endpoint");
  for(int i=0;i<40;i++)if(end.add(silence,320))throw new AssertionError("word gap");
  for(int i=0;i<30;i++)end.add(speech,320);for(int i=0;i<59;i++)if(end.add(silence,320))throw new AssertionError("tail");if(!end.add(silence,320))throw new AssertionError("no endpoint");
  char[] password="a test passphrase long enough".toCharArray();byte[] data="private owner evidence".getBytes("UTF-8"),sealed=VoiceProfileCrypto.seal(data,password);
  if(!Arrays.equals(data,VoiceProfileCrypto.open(sealed,password)))throw new AssertionError("round trip");
  sealed[sealed.length-1]^=1;try{VoiceProfileCrypto.open(sealed,password);throw new AssertionError("tamper");}catch(javax.crypto.AEADBadTagException expected){}
  System.out.println("Passed voice phrase, endpoint, microphone lease and encryption regression scenarios");
 }
}
