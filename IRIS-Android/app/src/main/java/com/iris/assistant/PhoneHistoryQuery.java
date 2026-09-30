package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure phrase classifier for phone-history questions that need the SYSTEM call log or SMS
 * inbox, kept deliberately free of any Android dependency so it can be unit-tested offline
 * (same approach as PhoneFacts / Plan / LocalPlanner).
 *
 * Scope note: IRIS already answers OUTGOING call questions from its own records via
 * IrisListeningService.HISTORY_PATTERN ("who did I call last", "how many times did I call X",
 * "when did I last call X", "call history", "my calls"). This class covers only what those
 * cannot: who called ME, missed calls, and reading received SMS. Those existing phrasings are
 * intentionally NOT matched here, and the router runs HISTORY_PATTERN first, so the current
 * behaviour is preserved exactly.
 *
 * Anything uncertain returns NONE so the established routing keeps priority rather than this
 * class guessing and stealing a command from a handler that already works.
 */
public final class PhoneHistoryQuery {

    public enum Kind {
        /** No system-history question recognised. */
        NONE,
        /** "who called me", "who just called" - most recent INCOMING call. */
        WHO_CALLED_ME,
        /** "any missed calls", "did I miss any calls". */
        MISSED_CALLS,
        /** "recent calls", "incoming calls" - a short combined list. */
        RECENT_CALLS,
        /** "read my last text" - most recent received SMS. */
        LAST_SMS,
        /** "read the last message from Ana" - most recent SMS from one sender. */
        SMS_FROM
    }

    public final Kind kind;
    /** Sender/caller name when the phrasing named one, otherwise "". Never null. */
    public final String name;

    private PhoneHistoryQuery(Kind kind, String name) {
        this.kind = kind;
        this.name = name == null ? "" : name.trim();
    }

    private static final PhoneHistoryQuery NO_MATCH = new PhoneHistoryQuery(Kind.NONE, "");

    public boolean matched() { return kind != Kind.NONE; }

    /** True when answering needs READ_CALL_LOG. */
    public boolean needsCallLog() {
        return kind == Kind.WHO_CALLED_ME || kind == Kind.MISSED_CALLS || kind == Kind.RECENT_CALLS;
    }

    /** True when answering needs READ_SMS. */
    public boolean needsSms() {
        return kind == Kind.LAST_SMS || kind == Kind.SMS_FROM;
    }

    // "who called me (last/just now)", "who just called", "who was that"
    private static final Pattern WHO_CALLED = Pattern.compile(
            "^(?:who\\s+(?:just\\s+)?called(?:\\s+me)?(?:\\s+(?:last|just\\s+now|recently))?"
            + "|who\\s+was\\s+(?:that|the\\s+last)\\s+call(?:\\s+from)?"
            + "|who\\s+rang(?:\\s+me)?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MISSED = Pattern.compile(
            // "did I miss any calls" uses "miss" as a VERB and has no "missed" adjective,
            // so it needs its own alternative rather than sharing the "missed calls" branch.
            "^(?:did\\s+i\\s+miss\\s+(?:any|a)\\s+calls?(?:\\s+today)?"
            + "|(?:do\\s+i\\s+have|are\\s+there|any|did\\s+i\\s+have)\\s+(?:any\\s+)?(?:new\\s+)?missed\\s+calls?(?:\\s+today)?"
            + "|(?:my\\s+)?missed\\s+calls?(?:\\s+today)?"
            + ")$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RECENT = Pattern.compile(
            "^(?:(?:my\\s+)?recent\\s+calls?"
            + "|(?:my\\s+)?incoming\\s+calls?"
            + "|(?:who|what)\\s+(?:has|have)\\s+been\\s+calling(?:\\s+me)?)$",
            Pattern.CASE_INSENSITIVE);

    // Named sender first: "read the last text from Ana", "any messages from Ana",
    // "what did Ana text me". Checked before the un-named variants below.
    private static final Pattern FROM_SENDER = Pattern.compile(
            "^(?:(?:please\\s+)?read\\s+)?(?:me\\s+)?(?:the\\s+|my\\s+)?(?:last|latest|new)?\\s*"
            + "(?:text|message|sms)e?s?\\s+from\\s+(.+?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM_SENDER_ALT = Pattern.compile(
            "^(?:any\\s+)?(?:new\\s+)?(?:texts?|messages?|sms)\\s+from\\s+(.+?)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHAT_DID_SAY = Pattern.compile(
            "^what\\s+did\\s+(.+?)\\s+(?:text|message)\\s*(?:me)?$",
            Pattern.CASE_INSENSITIVE);

    // Un-named: "read my last text", "read my messages", "any new texts".
    // Deliberately requires a text/message/sms word so it never competes with the existing
    // "read my notifications" command.
    private static final Pattern LAST_TEXT = Pattern.compile(
            "^(?:(?:please\\s+)?read\\s+)?(?:me\\s+)?(?:my\\s+|the\\s+)?(?:last|latest|newest|new)?\\s*"
            + "(?:text|message|sms)e?s?(?:\\s+(?:i\\s+got|i\\s+received|received))?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANY_NEW_TEXT = Pattern.compile(
            "^(?:do\\s+i\\s+have|are\\s+there|any)\\s+(?:new\\s+|unread\\s+)?"
            + "(?:texts?|messages?|sms)e?s?(?:\\s+for\\s+me)?$",
            Pattern.CASE_INSENSITIVE);

    /** Classify a spoken/typed command. Never throws; returns NONE when unrecognised. */
    public static PhoneHistoryQuery parse(String raw) {
        if (raw == null) return NO_MATCH;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[?.!]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) return NO_MATCH;

        if (WHO_CALLED.matcher(s).matches()) return new PhoneHistoryQuery(Kind.WHO_CALLED_ME, "");
        if (MISSED.matcher(s).matches()) return new PhoneHistoryQuery(Kind.MISSED_CALLS, "");
        if (RECENT.matcher(s).matches()) return new PhoneHistoryQuery(Kind.RECENT_CALLS, "");

        String sender = firstGroup(FROM_SENDER, s);
        if (sender == null) sender = firstGroup(FROM_SENDER_ALT, s);
        if (sender == null) sender = firstGroup(WHAT_DID_SAY, s);
        if (sender != null && !sender.isEmpty()) {
            return new PhoneHistoryQuery(Kind.SMS_FROM, stripTrailingFluff(sender));
        }

        if (LAST_TEXT.matcher(s).matches()) return new PhoneHistoryQuery(Kind.LAST_SMS, "");
        if (ANY_NEW_TEXT.matcher(s).matches()) return new PhoneHistoryQuery(Kind.LAST_SMS, "");
        return NO_MATCH;
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.matches() || m.groupCount() < 1) return null;
        String g = m.group(1);
        return g == null ? null : g.trim();
    }

    /** Drop polite tails so "from ana please" resolves to "ana". */
    private static String stripTrailingFluff(String name) {
        return name.replaceAll("\\s+(?:please|for\\s+me|now)$", "").trim();
    }

    @Override public String toString() {
        return kind + (name.isEmpty() ? "" : "(" + name + ")");
    }
}
