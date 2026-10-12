package com.iris.assistant;

import java.util.regex.Pattern;

/**
 * Recognises a notification whose text is a roll-up count rather than a message.
 *
 * Confirmed necessary by an on-device probe of the owner's own notifications, which returned this
 * alongside the real per-chat notifications:
 *
 *   title: WhatsApp
 *   text: 14 messages from 3 chats
 *   style: android.app.Notification$InboxStyle
 *   EXTRA_MESSAGES: 0 message(s)
 *
 * Archiving that produces a row claiming to be a message whose entire content is a number. It is
 * worse than useless: it is indistinguishable in the Vault from a real message reading "14
 * messages from 3 chats", and if a deletion were later detected it could be the row that gets
 * flagged.
 *
 * The matching is deliberately narrow. A false positive discards a real message permanently, and
 * the archive exists precisely because deleted messages cannot be recovered, so every pattern is
 * anchored end to end and requires the digits-then-noun shape. "14 messages from 3 chats" matches;
 * "I sent you 3 messages about the car" does not, because it has text either side.
 */
public final class MessageRollup {

    /**
     * Whole-string roll-up forms seen from messaging apps.
     *
     * Anchored with matches() at both ends on purpose. An unanchored pattern would swallow any
     * message that happened to contain a phrase like "2 new messages", which is a real thing a
     * person might type.
     */
    private static final Pattern ROLLUP = Pattern.compile(
            "\\d+\\s+messages?\\s+from\\s+\\d+\\s+chats?"
            + "|\\d+\\s+new\\s+messages?"
            + "|\\d+\\s+messages?"
            + "|\\d+\\s+new\\s+notifications?"
            + "|\\d+\\s+notifications?",
            Pattern.CASE_INSENSITIVE);

    private MessageRollup() { }

    /**
     * Whether this text is only a count of messages, with no message content.
     *
     * @param text the notification's plain text
     */
    public static boolean isRollup(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        return ROLLUP.matcher(trimmed).matches();
    }

    /**
     * Whether a notification should be skipped entirely as a summary.
     *
     * Requires BOTH roll-up text and an absence of per-message detail. The second condition is
     * what makes this safe: when a messaging app sends a roll-up it leaves EXTRA_MESSAGES empty,
     * whereas a genuine single message that happens to read like a count still carries its own
     * message entry and so is kept.
     *
     * @param text         the notification's plain text
     * @param messageCount how many entries EXTRA_MESSAGES held
     */
    public static boolean isSummaryOnly(String text, int messageCount) {
        return messageCount <= 0 && isRollup(text);
    }

    /**
     * The group or chat name from a messaging notification title.
     *
     * The on-device probe showed WhatsApp decorates group titles with both a message count and the
     * latest sender:
     *
     *   title: Swiss Group (3 messages): Rajesh Bhai Eric Kolkata Shapoorji
     *
     * Storing that whole string as the group name would make every group look like a different
     * group each time the count changed. This takes the part before the count or the colon, so the
     * example yields "Swiss Group" however many messages are pending.
     *
     * The colon is split on FIRST occurrence rather than last, because a group name containing a
     * colon is far likelier than a sender name containing one.
     */
    public static String groupFromTitle(String title) {
        if (title == null) return "";
        String working = title.trim();
        if (working.isEmpty()) return "";

        // Drop a trailing "(N messages)" decoration wherever it appears.
        int bracket = working.indexOf(" (");
        if (bracket > 0) {
            String tail = working.substring(bracket + 2);
            if (isRollup(tail.replace(")", "").trim())
                    || tail.matches("(?i)\\d+\\s+messages?\\).*")) {
                working = working.substring(0, bracket)
                        + afterCloseBracket(working, bracket);
            }
        }

        int colon = working.indexOf(':');
        if (colon > 0) working = working.substring(0, colon);
        return working.trim();
    }

    /** Preserves anything following the stripped "(N messages)" decoration, such as ": sender". */
    private static String afterCloseBracket(String working, int bracket) {
        int close = working.indexOf(')', bracket);
        return close < 0 || close + 1 >= working.length() ? "" : working.substring(close + 1);
    }
}
