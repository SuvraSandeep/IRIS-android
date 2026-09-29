package com.iris.assistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class AudioRoutingRecoveryTest {
 static class Fake implements CommunicationRouteLease.Controls {int mode,sets,clears;boolean accept=true,failClear,failSelect;
  public int mode(){return mode;}public void communication(){mode=3;sets++;}public boolean select(){if(failSelect)throw new IllegalStateException();return accept;}
  public void clear(){clears++;if(failClear)throw new IllegalStateException();}public void normal(){mode=0;}}
 @Test public void refusedBluetoothDoesNotLeaveEarpieceMode(){Fake f=new Fake();f.accept=false;CommunicationRouteLease r=new CommunicationRouteLease(f);assertFalse(r.acquire());assertEquals(0,f.mode);assertFalse(r.owned());}
 @Test public void failureAndCleanupExceptionStillReleaseMode(){Fake f=new Fake();f.failSelect=true;f.failClear=true;assertFalse(new CommunicationRouteLease(f).acquire());assertEquals(0,f.mode);}
 @Test public void anotherCallsModeIsNotTakenOver(){Fake f=new Fake();f.mode=2;assertFalse(new CommunicationRouteLease(f).acquire());assertEquals(2,f.mode);assertEquals(0,f.sets);}
 @Test public void fiveHundredAcquireReleaseCyclesNeverLeaveCommunicationMode(){Fake f=new Fake();CommunicationRouteLease r=new CommunicationRouteLease(f);for(int i=0;i<500;i++){assertTrue(r.acquire());r.close();r.close();assertEquals(0,f.mode);}assertEquals(500,f.clears);}
}
