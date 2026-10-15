package com.iris.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Offline checks for the per-chat rolling history buffer. */
public final class ChatHistoryTest {
    private static int checks;
    private static void eq(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAILED: " + what + " expected " + b + " got " + a);
        }
    }
    private static void t(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        // Trims to capacity, oldest dropped.
        List<String> h = new ArrayList<>();
        h = ChatHistory.append(h, "hey", 3);
        h = ChatHistory.append(h, "are you free", 3);
        h = ChatHistory.append(h, "tomorrow", 3);
        h = ChatHistory.append(h, "evening?", 3);
        eq(h, Arrays.asList("are you free", "tomorrow", "evening?"), "keeps last 3, drops oldest");

        // Capacity 0 => empty (just-this-message mode).
        eq(ChatHistory.append(h, "x", 0), new ArrayList<String>(), "capacity 0 keeps nothing");

        // Blank messages ignored.
        eq(ChatHistory.append(new ArrayList<>(), "   ", 3), new ArrayList<String>(),
                "blank message ignored");

        // Depth clamped to MAX_DEPTH.
        eq(ChatHistory.clampDepth(999), ChatHistory.MAX_DEPTH, "depth clamps to max");
        eq(ChatHistory.clampDepth(-5), 0, "negative depth clamps to 0");

        // window returns the last n, oldest first.
        List<String> buf = Arrays.asList("a", "b", "c", "d");
        eq(ChatHistory.window(buf, 2), Arrays.asList("c", "d"), "window last 2");
        eq(ChatHistory.window(buf, 10), buf, "window larger than buffer returns all");
        t(ChatHistory.window(buf, 0).isEmpty(), "window 0 is empty");

        // combine joins oldest-first with '. '
        eq(ChatHistory.combine(Arrays.asList("hey", "are you free", "tomorrow")),
                "hey. are you free. tomorrow", "combine joins in order");
        t(ChatHistory.combine(new ArrayList<>()).isEmpty(), "combine empty -> empty");

        System.out.println("Passed " + checks + " chat history checks");
    }
}
