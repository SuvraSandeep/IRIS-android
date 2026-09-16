package com.iris.assistant;

/** Diagnostics retain no raw recording after the take finishes. */
final class PhraseEvidence {
    final String rawText,processedText,reason;
    final short[] speakerPcm;
    PhraseEvidence(String raw,String processed,String reason,short[] pcm){rawText=raw;processedText=processed;this.reason=reason;speakerPcm=pcm;}
    boolean accepted(){return reason.isEmpty();}
    String summary(){return "Raw: “"+rawText+"”; processed: “"+processedText+"”. "+reason;}
    static boolean complete(String expected,String actual){return WakePolicy.matches(actual,java.util.Collections.singletonList(expected));}
    static String mismatch(String expected,String heard){
        String e=WakePolicy.normalize(expected),h=WakePolicy.normalize(heard);
        if(h.isEmpty())return "NO_CLEAR_WORDS: no clear words were decoded. Check the input microphone.";
        if(e.startsWith(h+" "))return "PHRASE_INCOMPLETE: the recognizer missed the end of the phrase. Say both words naturally, without a long pause.";
        return "PHRASE_MISMATCH: the full phrase was not recognized. No voice sample was added.";
    }
}
