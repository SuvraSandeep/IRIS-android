package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises spoken questions about deleted or unsent messages, and the yes/no reply that follows.
 *
 * SCOPE: no Android imports, so every phrasing is pinned by offline tests.
 *
 * THE COLLISION THIS CLASS MUST AVOID, which is the entire reason it is a separate classifier:
 * IrisListeningService.NOTIFICATION_PATTERN already matches
 *   .*\b(how many|any|check|latest|last|read|unread|new)\b.*\b(message|messages|text|texts|...)\b.*
 * so a bare "any deleted messages" would be swallowed by the existing notification reader. Every
 * pattern here therefore REQUIRES the word "deleted", "unsent" or "delete", and this classifier is
 * dispatched BEFORE the notification handler. That makes the two sets disjoint by construction
 * rather than by luck.
 *
 * This matters because the same mistake has already shipped once: a previous change routed
 * "read my last text" away from the notification handler that was correctly serving it. The
 * regression guards in DeletedQueryTest pin the existing phrasings so it cannot happen again.
 */
public final class DeletedQuery {

    private DeletedQuery() { }

    /** What the owner asked for. */
    public enum Kind {
        NONE,
        /** "any deleted messages", "did anyone delete a message" */
        ANY_DELETED,
        /** "open the deleted messages log", "show me the deleted messages" */
        OPEN_LOG
    }

    /**
     * Asking whether anything has been taken back.
     *
     * Each alternative anchors on a deletion word. "message"/"text" alone is never enough, because
     * that is the existing notification reader's territory.
     */
    private static final Pattern ANY_DELETED_PATTERN = Pattern.compile(
            ".*\\b(?:any|some|new)\\b.*\\b(?:deleted|unsent)\\b.*\\b(?:messages?|texts?)\\b.*"
            + "|.*\\b(?:deleted|unsent)\\b.*\\b(?:messages?|texts?)\\b.*\\b(?:detected|found|caught)\\b.*"
            + "|.*\\bdid\\s+(?:anyone|anybody|someone|somebody|he|she|they)\\b.*\\b(?:delete|unsend|unsent)\\b.*"
            + "|.*\\b(?:was|were|has|have)\\b.*\\b(?:any|a)\\b.*\\bmessages?\\b.*\\b(?:deleted|unsent)\\b.*"
            + "|.*\\b(?:any|anything)\\b.*\\b(?:deleted|unsent)\\b.*"
            + "|.*\\bdeleted\\s+messages?\\b.*"
            + "|.*\\bunsent\\s+messages?\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** Asking to see the archive. Requires both an open verb and a deletion or archive noun. */
    private static final Pattern OPEN_LOG_PATTERN = Pattern.compile(
            ".*\\b(?:open|show|view|see|display)\\b.*\\b(?:deleted|unsent|archive)\\b.*"
            + "|.*\\b(?:open|show|view|see|display)\\b.*\\b(?:message|notification)\\s+(?:log|archive|history)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** Affirmative reply to "want me to open the log?". */
    private static final Pattern YES_PATTERN = Pattern.compile(
            "^(?:yes|yeah|yep|yup|ya|sure|ok|okay|please|go ahead|do it|open it|show me|show it)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** Negative reply. Matched explicitly so an unrelated sentence is not read as "no". */
    private static final Pattern NO_PATTERN = Pattern.compile(
            "^(?:no|nope|nah|not now|later|cancel|never mind|nevermind|don'?t|dont)\\b.*",
            Pattern.CASE_INSENSITIVE);

    public static Kind parse(String spoken) {
        String text = normalise(spoken);
        if (text.isEmpty()) return Kind.NONE;
        // Opening is checked first: "show me the deleted messages" is a request to open, not a
        // question about whether any exist, and it matches both patterns.
        if (OPEN_LOG_PATTERN.matcher(text).matches()) return Kind.OPEN_LOG;
        if (ANY_DELETED_PATTERN.matcher(text).matches()) return Kind.ANY_DELETED;
        return Kind.NONE;
    }

    public static boolean matched(String spoken) {
        return parse(spoken) != Kind.NONE;
    }

    public static boolean isYes(String spoken) {
        String text = normalise(spoken);
        return !text.isEmpty() && YES_PATTERN.matcher(text).matches();
    }

    public static boolean isNo(String spoken) {
        String text = normalise(spoken);
        return !text.isEmpty() && NO_PATTERN.matcher(text).matches();
    }

    /**
     * The spoken answer when nothing has been taken back.
     *
     * Mentions the window so "no" is not mistaken for "nothing has ever happened".
     */
    public static String nothingFound(boolean everChecked) {
        return everChecked
                ? "No deleted messages since you last checked."
                : "No deleted messages so far.";
    }

    /**
     * The spoken answer when something was found.
     *
     * Names senders because the owner asked for that, and offers to open the log rather than
     * reading message bodies aloud -- the body is the sensitive part and stays on screen only.
     *
     * @param senders up to a few names, already de-duplicated by the caller
     */
    public static String found(int count, java.util.List<String> senders) {
        if (count <= 0) return nothingFound(true);
        StringBuilder out = new StringBuilder();
        out.append("Yes. ").append(count)
                .append(count == 1 ? " deleted message" : " deleted messages");
        String names = nameList(senders);
        if (!names.isEmpty()) out.append(", ").append(names);
        out.append(". Want me to open the log?");
        return out.toString();
    }

    /** "from Ana", "from Ana and Bob", "from Ana, Bob and one other". */
    static String nameList(java.util.List<String> senders) {
        if (senders == null || senders.isEmpty()) return "";
        java.util.List<String> clean = new java.util.ArrayList<>();
        for (String name : senders) {
            if (name == null) continue;
            String trimmed = name.trim();
            if (!trimmed.isEmpty() && !clean.contains(trimmed)) clean.add(trimmed);
            if (clean.size() == 3) break;
        }
        if (clean.isEmpty()) return "";
        if (clean.size() == 1) return "from " + clean.get(0);
        if (clean.size() == 2) return "from " + clean.get(0) + " and " + clean.get(1);
        return "from " + clean.get(0) + ", " + clean.get(1) + " and " + clean.get(2);
    }

    private static String normalise(String spoken) {
        if (spoken == null) return "";
        return spoken.trim().toLowerCase(Locale.US).replace('\u2019', '\'');
    }
}
