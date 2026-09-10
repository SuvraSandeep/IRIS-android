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
        System.out.println("Passed " + checks + " wake regression checks");
    }
}
