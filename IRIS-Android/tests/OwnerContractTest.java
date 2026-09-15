package com.iris.assistant;
import java.util.*;
public final class OwnerContractTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Owner contract check "+checks);}
    public static void main(String[] args){
        List<String> phrase=Collections.singletonList("Hello Iris");
        for(String s:new String[]{"Hello Iris","hello iris","Hello, Iris!"," HELLO  IRIS "})check(WakePolicy.matches(s,phrase));
        for(String s:new String[]{"Hello","Iris","Hi Iris","Hello Irish","Please hello iris","hello iris take a photo","hello iris hello iris","","[unk]"})check(!WakePolicy.matches(s,phrase));
        float[] v=new float[128];v[0]=1;float[] other=new float[128];other[1]=1;
        check(WakePolicy.owner(v,v,.8));check(!WakePolicy.owner(other,v,.8));
        check(!WakePolicy.owner(null,v,.8));check(!WakePolicy.owner(v,null,.8));check(!WakePolicy.owner(new float[12],v,.8));check(!WakePolicy.owner(new float[128],v,.8));
        check(!WakePolicy.owner(v,v,Double.NaN));check(!WakePolicy.owner(v,v,-1));check(!WakePolicy.owner(v,v,2));
        check(!WakePolicy.ownerEither(other,v,null,.8));check(WakePolicy.ownerEither(v,null,v,.8));
        float[] broken=v.clone();broken[2]=Float.NaN;check(!WakePolicy.owner(broken,v,.8));
        check(WakePolicy.normalize("नमस्ते").equals("नमस्ते"));
        boolean blocked=false;try{WakeChangeApproval.require();}catch(SecurityException e){blocked=true;}check(blocked);
        WakeChangeApproval.runApproved(()->WakeChangeApproval.require());
        blocked=false;try{WakeChangeApproval.require();}catch(SecurityException e){blocked=true;}check(blocked);
        short[] silence=new short[16000];check(!WakePolicy.usableAudio(QuietAudioProcessor.prepare(silence)));
        short[] loud=new short[16000];for(int i=0;i<loud.length;i++)loud[i]=(short)(i%2==0?32767:-32767);
        short[] prepared=QuietAudioProcessor.prepare(loud);check(loud[0]==32767);
        boolean bounded=true;for(short value:prepared)if(Math.abs((int)value)>28000)bounded=false;check(bounded);
        System.out.println("Passed "+checks+" owner contract checks");
    }
}
