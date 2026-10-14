package com.iris.assistant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Expands common SMS shorthand and texting typos to full words before matching, so "r u there"
 * reads as "are you there", "ur" as "your", "acc"/"loc" as "account"/"location". Pure and
 * offline-testable.
 *
 * Deliberately conservative: only whole standalone tokens are replaced (punctuation and emoji on
 * either side are preserved), and only unambiguous forms are mapped. "no", "so", "me" and the like
 * are never touched. This fixes the real-chat failures where a human typed "Iris r u there" and
 * the matcher missed the "are you there" rule.
 */
public final class Shorthand {

    private Shorthand() { }

    private static final Map<String, String> MAP = build();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("r", "are");
        m.put("u", "you");
        m.put("ur", "your");
        m.put("urs", "yours");
        m.put("yr", "your");
        m.put("n", "and");
        m.put("nd", "and");
        m.put("wat", "what");
        m.put("wht", "what");
        m.put("wot", "what");
        m.put("whr", "where");
        m.put("thay", "that");
        m.put("tht", "that");
        m.put("dat", "that");
        m.put("dis", "this");
        m.put("bzy", "busy");
        m.put("pls", "please");
        m.put("plz", "please");
        m.put("plz.", "please");
        m.put("acc", "account");
        m.put("acct", "account");
        m.put("loc", "location");
        m.put("msg", "message");
        m.put("msgs", "messages");
        m.put("abt", "about");
        m.put("hv", "have");
        m.put("cn", "can");
        m.put("wrk", "work");
        m.put("nmbr", "number");
        m.put("numbr", "number");
        m.put("pwd", "password");
        m.put("pw", "password");
        m.put("acnt", "account");
        return m;
    }

    public static String expand(String text) {
        if (text == null) return "";
        String[] parts = text.split("\\s+");
        StringBuilder out = new StringBuilder(text.length());
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(expandToken(part));
        }
        return out.toString();
    }

    private static String expandToken(String tok) {
        int i = 0, j = tok.length();
        while (i < j && !Character.isLetterOrDigit(tok.charAt(i))) i++;
        while (j > i && !Character.isLetterOrDigit(tok.charAt(j - 1))) j--;
        if (i >= j) return tok;                       // punctuation / emoji only
        String lead = tok.substring(0, i);
        String core = tok.substring(i, j);
        String trail = tok.substring(j);
        String rep = MAP.get(core.toLowerCase(Locale.ROOT));
        return lead + (rep != null ? rep : core) + trail;
    }
}
