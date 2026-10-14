package com.iris.assistant;

import java.util.HashMap;
import java.util.Map;

/** Offline checks for learned-reply scope keys and precedence. */
public final class LearnedRepliesTest {

    private static int checks;

    private static void eq(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAILED: " + what + " expected [" + b + "] got [" + a + "]");
        }
    }

    public static void main(String[] args) {
        Map<String, String> store = new HashMap<>();

        // Nothing taught yet.
        eq(LearnedReplies.choose(store, "Ravi", "GREETING"), "", "no learned reply");

        // Sender-wide learned reply applies to any category.
        store.put(LearnedReplies.scopeAny("Ravi"), "I'll have Suvra call you. IRIS.");
        eq(LearnedReplies.choose(store, "Ravi", "GREETING"),
                "I'll have Suvra call you. IRIS.", "sender-wide applies to greeting");
        eq(LearnedReplies.choose(store, "Ravi", "QUESTION"),
                "I'll have Suvra call you. IRIS.", "sender-wide applies to question");

        // A specific sender+category beats the sender-wide one.
        store.put(LearnedReplies.scopeFor("Ravi", "GREETING"), "Hi! Suvra's assistant IRIS here.");
        eq(LearnedReplies.choose(store, "Ravi", "GREETING"),
                "Hi! Suvra's assistant IRIS here.", "specific beats sender-wide");
        eq(LearnedReplies.choose(store, "Ravi", "QUESTION"),
                "I'll have Suvra call you. IRIS.", "other category still uses sender-wide");

        // Case-insensitive sender match.
        eq(LearnedReplies.choose(store, "ravi", "GREETING"),
                "Hi! Suvra's assistant IRIS here.", "sender match is case-insensitive");

        // A different sender is unaffected.
        eq(LearnedReplies.choose(store, "Mom", "GREETING"), "", "a different sender has nothing");

        // Empty values are treated as absent.
        store.put(LearnedReplies.scopeFor("Sam", "THANKS"), "");
        eq(LearnedReplies.choose(store, "Sam", "THANKS"), "", "empty learned value is absent");

        System.out.println("Passed " + checks + " learned reply checks");
    }
}
