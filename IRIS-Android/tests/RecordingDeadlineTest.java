package com.iris.assistant;
public final class RecordingDeadlineTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Deadline check "+checks);}
    public static void main(String[] args){
        RecordingDeadline absent=new RecordingDeadline(1000,5000);
        check(!absent.expired(3999));check(absent.expired(4000));
        RecordingDeadline stalled=new RecordingDeadline(1000,5000);
        stalled.received(2000);check(!stalled.expired(4999));check(stalled.expired(5000));
        RecordingDeadline normal=new RecordingDeadline(1000,5000);
        for(long t=1000;t<6000;t+=100){normal.received(t);check(!normal.expired(t));}
        RecordingDeadline dribble=new RecordingDeadline(1000,5000);
        dribble.received(8999);check(!dribble.expired(8999));check(dribble.expired(9000));
        RecordingDeadline longRecording=new RecordingDeadline(900000,60000);
        longRecording.received(959900);check(!longRecording.expired(960000));check(longRecording.expired(963000));
        System.out.println("Passed "+checks+" recorder deadline checks");
    }
}
