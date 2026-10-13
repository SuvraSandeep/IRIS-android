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

    /**
     * Whether a notification represents a group conversation rather than a one-to-one chat.
     *
     * Added after on-device testing found every conversation being treated as a group, which with
     * the shipped defaults (groups off) meant the responder refused everything with
     * GROUP_NOT_ENABLED. The cause was reading a non-empty {@link #groupFromTitle} result as proof
     * of a group. It is not: in a one-to-one chat the title IS the other person, so that test is
     * true for every message ever received.
     *
     * The rule here is that a group must be positively evidenced, because the two mistakes cost
     * different amounts. Calling a group "one-to-one" risks a reply in a group chat, which the
     * mention requirement still guards. Calling one-to-one "a group" silently disables the
     * responder's main job, and reports a reason that sends the owner looking in the wrong place.
     *
     * Evidence accepted, in order:
     * <ol>
     *   <li>the app set EXTRA_IS_GROUP_CONVERSATION -- it knows, so it wins;</li>
     *   <li>more than one distinct person sent a message in this notification;</li>
     *   <li>a group name that is a genuinely different name from the sender.</li>
     * </ol>
     *
     * A title alone is never evidence. An SMS from a Twilio number arrives with the number as the
     * title and no sender field at all, and that is exactly the case this method exists to get
     * right.
     *
     * @param platformHint    the app's own EXTRA_IS_GROUP_CONVERSATION flag
     * @param groupName       the title with count decoration stripped, from {@link #groupFromTitle}
     * @param sender          the sender of the newest message, which may be empty
     * @param distinctSenders how many different people sent messages in this notification
     */
    public static boolean isGroupConversation(boolean platformHint, String groupName,
                                              String sender, int distinctSenders) {
        // The app told us outright. Nothing we can infer beats what the app knows.
        if (platformHint) return true;

        // Two people talking in one notification can only be a group. This is the strongest
        // signal available when an app does not set the hint.
        if (distinctSenders > 1) return true;

        String group = squash(groupName);
        String who = squash(sender);

        // No title to reason about.
        if (group.isEmpty()) return false;

        // No sender to compare the title against. A bare title is not evidence of anything -- it
        // is equally the name of a group and the name of a person -- so this stays one-to-one.
        // This is the Twilio SMS case: title "+15551234567", no sender, one message.
        if (who.isEmpty()) return false;

        if (group.equals(who)) return false;

        // One name inside the other is the same person under a different form: a notification
        // titled "Alice Smith" whose message is attributed to "Alice", or the reverse. Treating
        // that as a group would misread a saved contact as a group of one.
        if (group.contains(who) || who.contains(group)) return false;

        return true;
    }

    /** Lower-cased, punctuation-free, single-spaced, for comparing two names for sameness. */
    private static String squash(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (char c : raw.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                if (space && out.length() > 0) out.append(' ');
                space = false;
                out.append(c);
            } else {
                space = true;
            }
        }
        return out.toString();
    }
}
