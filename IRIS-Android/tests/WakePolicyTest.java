package com.iris.assistant;
import java.util.*;
public final class WakePolicyTest {
    private static int checks;
    private static void check(boolean pass, String name) {
        checks++; if (!pass) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        List<String> phrases = List.of("Hello IRIS");
        check(WakePolicy.matches(" HELLO,   IRIS! ", phrases), "normalized complete phrase");
        for (String noise : List.of("iris", "there", "hello", "hello iris is on television", "iris hello", "yellow virus", "", "[unk]"))
            check(!WakePolicy.matches(noise, phrases), "reject: " + noise);
        check(!WakePolicy.matches(null, phrases), "null transcript");
        float[] owner = new float[128]; owner[0] = 1;
        float[] other = new float[128]; other[1] = 1;
        check(WakePolicy.owner(owner, owner, .75), "owner passes");
        check(!WakePolicy.owner(other, owner, .75), "different voice rejects");
        for (int i = 0; i < 10; i++) check(!WakePolicy.owner(other, owner, .75), "repeated rejects never bypass");
        check(!WakePolicy.owner(null, owner, .75), "missing embedding");
        check(!WakePolicy.owner(owner, null, .75), "missing enrollment");
        check(!WakePolicy.owner(new float[192], owner, .75), "wrong model dimensions");
        check(!WakePolicy.owner(new float[128], owner, .75), "zero vector");
        float[] bad = owner.clone(); bad[1] = Float.NaN;
        check(!WakePolicy.owner(bad, owner, .75), "nan embedding");
        check(WakePolicy.enrollment(List.of(owner, owner)) == null, "minimum samples");
        check(WakePolicy.enrollment(List.of(owner, owner, other)) == null, "mixed speakers");
        check(WakePolicy.owner(WakePolicy.enrollment(List.of(owner, owner, owner)), owner, .99), "consistent enrollment");
        check(!WakePolicy.usableAudio(new short[48000]), "silence");
        short[] clipped = new short[48000]; Arrays.fill(clipped, Short.MAX_VALUE);
        check(!WakePolicy.usableAudio(clipped), "clipping");
        short[] noise = new short[48000]; Arrays.fill(noise, (short)1000);
        check(!WakePolicy.usableAudio(noise), "stationary noise");
        short[] speech = new short[48000];
        for (int i = 8000; i < 32000; i++) speech[i] = (short)(2000 * Math.sin(i * .2));
        check(WakePolicy.usableAudio(speech), "speech with quiet lead and tail");
        check(Math.abs(WakePolicy.threshold(.5f) - .75) < 1e-6, "default threshold");
        check(WakePolicy.threshold(-5) >= .65 && WakePolicy.threshold(5) <= .86, "threshold bounds");
        check(Double.isFinite(WakePolicy.threshold(Float.NaN)), "invalid sensitivity");
        check(WakePolicy.matchesRepeated("hello iris hello iris", phrases), "short phrase repeated for evidence");
        check(!WakePolicy.matchesRepeated("hello iris call mom", phrases), "commands are never swallowed as wake");
        check(!WakePolicy.matchesRepeated("hello iris hello", phrases), "partial repetition rejects");
        check(!WakePolicy.owner(other, owner, -1), "invalid threshold cannot bypass owner");
        check(!WakePolicy.owner(owner, owner, Double.NaN), "NaN threshold rejects");
        check(WakePolicy.ownerAny(other, List.of(owner,other), .75), "explicit quiet profile works");
        check(!WakePolicy.ownerAny(other, List.of(owner), .75), "quiet mode never skips verification");
        check(!WakePolicy.ownerAny(null, List.of(owner,other), .75), "missing voice evidence fails closed");
        check(!WakePolicy.rejection("hello iris",phrases, .99, .6, 0,null).isEmpty(), "missing speaker evidence diagnosed");
        check(!WakePolicy.rejection("hello iris",phrases, .2, .6, 50,owner).isEmpty(), "weak phrase confidence rejects");
        check(WakePolicy.rejection("hello iris",phrases, .99, .4, 50,owner).isEmpty(), "short complete phrase with owner evidence");
        short[] quiet=speech.clone();for(int i=0;i<quiet.length;i++)quiet[i]/=25;
        check(WakePolicy.usableAudio(quiet), "quiet speech not rejected by fixed loudness floor");
        short[] processed=QuietAudioProcessor.prepare(quiet);
        check(!Arrays.equals(processed,quiet), "quiet audio gain applied");
        int peak=0;for(short v:processed)peak=Math.max(peak,Math.abs((int)v));
        check(peak<28001, "gain leaves headroom");
        check(Arrays.equals(QuietAudioProcessor.prepare(new short[16000]),new short[16000]), "digital silence stays silent");
        check(!WakePolicy.usableAudio(QuietAudioProcessor.prepare(noise)), "DC offset cannot become speech");
        java.util.Random random=new java.util.Random(42);short[] hiss=new short[48000];
        for(int i=0;i<hiss.length;i++)hiss[i]=(short)(random.nextInt(101)-50);
        check(!WakePolicy.usableAudio(hiss), "stationary quiet hiss rejected");
        short[] original=quiet.clone();QuietAudioProcessor.prepare(quiet);
        check(Arrays.equals(original,quiet), "training originals not mutated");
        boolean blocked=false;
        try{WakeChangeApproval.require();}catch(SecurityException expected){blocked=true;}
        check(blocked,"settings writes blocked without explicit authentication scope");
        WakeChangeApproval.runApproved(()->WakeChangeApproval.require());
        blocked=false;try{WakeChangeApproval.require();}catch(SecurityException expected){blocked=true;}
        check(blocked,"approval removed after one synchronous action");
        try{WakeChangeApproval.runApproved(()->{throw new IllegalStateException();});}catch(IllegalStateException expected){}
        blocked=false;try{WakeChangeApproval.require();}catch(SecurityException expected){blocked=true;}
        check(blocked,"exception does not leak approval");
        java.util.concurrent.atomic.AtomicBoolean isolated=new java.util.concurrent.atomic.AtomicBoolean();
        WakeChangeApproval.runApproved(()->{
            Thread worker=new Thread(()->{try{WakeChangeApproval.require();}catch(SecurityException expected){isolated.set(true);}});
            worker.start();try{worker.join();}catch(InterruptedException e){throw new AssertionError(e);}
        });
        check(isolated.get(),"background AI or training worker cannot inherit approval");
        System.out.println("Passed " + checks + " wake regression checks");
    }
}
