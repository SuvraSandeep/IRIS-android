package com.iris.assistant;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Short-term conversation memory (context window).
 * Keeps the last N user/assistant exchanges so IRIS can remember what was
 * just said and stay in context. Pure in-memory, resets when the service restarts.
 */
public final class ConversationManager {
    private static final int MAX_TURNS = 4; // last 4 exchanges

    public static class Turn {
        public final String user;
        public final String assistant;
        Turn(String user, String assistant) { this.user = user; this.assistant = assistant; }
    }

    private final Deque<Turn> history = new ArrayDeque<>();

    /** Record an exchange. */
    public synchronized void add(String user, String assistant) {
        history.addLast(new Turn(user == null ? "" : user, assistant == null ? "" : assistant));
        while (history.size() > MAX_TURNS) history.removeFirst();
    }

    /** Build a transcript of recent conversation for the LLM prompt. */
    public synchronized String transcript() {
        if (history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Recent conversation:\n");
        for (Turn t : history) {
            if (!t.user.isEmpty()) sb.append("User: ").append(t.user).append("\n");
            if (!t.assistant.isEmpty()) sb.append("IRIS: ").append(t.assistant).append("\n");
        }
        return sb.toString();
    }

    /** Get the last thing the user said (for "call him back", "do it again" style). */
    public synchronized String lastUserMessage() {
        return history.isEmpty() ? "" : history.peekLast().user;
    }

    /** Recent turns as a JSON array of {role,text} for the server /chat endpoint. */
    public synchronized org.json.JSONArray turnsJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (Turn t : history) {
                if (!t.user.isEmpty()) arr.put(new org.json.JSONObject().put("role", "user").put("text", t.user));
                if (!t.assistant.isEmpty()) arr.put(new org.json.JSONObject().put("role", "assistant").put("text", t.assistant));
            }
        } catch (Throwable ignored) { }
        return arr;
    }

    public synchronized void clear() { history.clear(); }
    public synchronized boolean isEmpty() { return history.isEmpty(); }
}
