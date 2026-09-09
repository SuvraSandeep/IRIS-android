package com.iris.assistant;
import java.util.*;

public final class SpeechTextTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static void command(String spoken, String expected) {
        check(expected.equals(SpeechText.command(spoken)), spoken + " -> " + SpeechText.command(spoken));
    }
    public static void main(String[] args) {
        command("Hey, IRIS, could you please switch on the torch?", "torch on");
        command("put the flashlight off", "torch off");
        command("turn the torch on please", "torch on");
        command("Kindly make a call to Maa.", "call Maa");
        command("give Rahul a call", "call Rahul");
        command("can you increase the volume?", "volume up");
        command("please reduce the sound", "volume down");
        command("click one screenshot", "take a screenshot");
        command("take a screen shot", "take a screenshot");
        command("text Maa saying Call Rahul, please!", "text Maa saying Call Rahul, please!");
        command("Please send a message to Dad saying I'll be on time.", "send a message to Dad saying I'll be on time.");
        command("search for battery replacement", "search for battery replacement");
        command("remember I like tea, please", "remember I like tea, please");
        command("do not call Rahul", "do not call Rahul");
        command("don't record the screen", "don't record the screen");
        command("what is a screenshot", "what is a screenshot");
        command(null, "");
        for (String s : new String[]{"text mom the battery is low", "search for battery",
                "remind me to charge tomorrow", "set a timer for ten minutes",
                "send a message to dad i will be on time", "open time tracker",
                "remember my battery capacity", "record video for some time"})
            check(!SpeechText.quickInfo(s), "Do not steal payload: " + s);
        for (String s : new String[]{"what is the time", "time", "battery level",
                "how much battery is left", "is it charging", "tell me the time"})
            check(SpeechText.quickInfo(s), "Information query: " + s);
        Map<String,List<String>> aliases = new HashMap<>();
        aliases.put("call", Arrays.asList("coal", "kol"));
        aliases.put("text", Arrays.asList("tax"));
        check("call Maa".equals(SpeechText.aliases("coal Maa", aliases)), "Exact learned head");
        check("call Coal".equals(SpeechText.aliases("call Coal", aliases)), "Keep contact name");
        check("text Maa saying coal tax please".equals(SpeechText.aliases("text Maa saying coal tax please", aliases)), "Keep message");
        check("coals Maa".equals(SpeechText.aliases("coals Maa", aliases)), "No fuzzy guessing");
        check("do not coal Maa".equals(SpeechText.aliases("do not coal Maa", aliases)), "Keep negation");
        aliases.put("open", Arrays.asList("coal"));
        check("coal Maa".equals(SpeechText.aliases("coal Maa", aliases)), "Ambiguous alias unchanged");
        check(SpeechText.lowConfidence(.2f), "Low confidence asks again");
        check(!SpeechText.lowConfidence(-1f), "Missing confidence is not low confidence");
        check(!SpeechText.lowConfidence(.9f), "Good confidence");
        check(!SpeechText.lowConfidence(Float.NaN), "Unavailable confidence");
        System.out.println("Passed " + checks + " NLP regression checks.");
    }
}
