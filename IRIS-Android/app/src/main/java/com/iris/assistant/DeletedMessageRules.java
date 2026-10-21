package com.iris.assistant;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Decision rules for the notification archive: whether a notification is a deletion placeholder,
 * who sent it, whether a removal looks like a sender unsending a message, and how to label it.
 *
 * SCOPE: deliberately free of every Android import so it can be compiled and tested on a plain JVM
 * (scripts/test-speech.sh). All the judgement calls live here where they are cheap to test; the
 * Android layer (IrisNotificationListener) only supplies facts and performs the database writes.
 *
 * WHY THE REMOVAL REASON IS AN ENUM, NOT AN int: Android's NotificationListenerService.REASON_*
 * constants cannot be referenced from a pure class, and copying their numeric values here would
 * mean a wrong number compiles cleanly and then silently disables deletion detection forever. The
 * listener maps the raw int to {@link Removal} using the real compiler-checked constants, and this
 * class holds only the logic. The mapping is a handful of lines; the logic is where bugs hide.
 *
 * TWO CONFIDENCE LEVELS, which is the reason this class is not a single boolean:
 *  - CONFIRMED: the messaging app itself posted a placeholder such as "This message was deleted".
 *    This is fact, not inference.
 *  - SUSPECTED: no placeholder arrived, but the app cancelled the notification while the screen was
 *    off, so the owner cannot have read it and cannot have dismissed it. This is a reasoned guess.
 * Presenting a guess as a fact would make the whole archive untrustworthy, so they stay distinct
 * all the way to the UI.
 */
public final class DeletedMessageRules {

    private DeletedMessageRules() { }

    /**
     * Longest a notification may have been alive and still be treated as a suspected deletion.
     *
     * Rationale: WhatsApp's own "delete for everyone" window is finite, and messaging apps also
     * cancel genuinely stale notifications for housekeeping. Without a bound, a phone left
     * untouched for a week would collect false suspicions every time an app tidied up.
     */
    public static final long MAX_SUSPECT_AGE_MS = 48L * 60L * 60L * 1000L;

    /**
     * After a real placeholder is seen for a conversation, suspected detection is suppressed for
     * this long. WhatsApp deletes the old notification and posts the placeholder as two separate
     * events; without this window the single deletion would be flagged twice, once confirmed and
     * once suspected.
     */
    public static final long PLACEHOLDER_SUPPRESSION_MS = 60L * 1000L;

    /** Longest text treated as a sender name when splitting "Sender: message" in a group. */
    private static final int MAX_SENDER_NAME = 48;

    /**
     * Placeholders meaning the OTHER person removed a message.
     * Multilingual coverage: English, Hindi, Odia, Spanish, French.
     */
    private static final String[] SENDER_DELETED = {
            // English
            "this message was deleted",
            "message was deleted",
            "message deleted",
            "message unsent",
            "unsent a message",
            "deleted this message",
            // Hindi (हिंदी)
            "यह संदेश हटा दिया गया था",
            "यह संदेश हटा दिया गया है",
            "यह संदेश हटा दिया गया",
            "संदेश हटा दिया गया था",
            "संदेश हटा दिया गया",
            "यह संदेश मिटा दिया गया था",
            "यह संदेश मिटा दिया गया",
            // Odia (ଓଡ଼ିଆ)
            "ଏହି ବାର୍ତ୍ତାଟି ଡିଲିଟ୍ କରାଯାଇଛି",
            "ଏହି ବାର୍ତ୍ତାଟି ଡିଲିଟ୍ ହୋଇଛି",
            "ଏହି ମେସେଜ୍ ଡିଲିଟ୍ କରାଯାଇଛି",
            "ମେସେଜ୍ ଡିଲିଟ୍ କରାଯାଇଛି",
            "ଏହି ସନ୍ଦେଶ ହଟାଇ ଦିଆଯାଇଛି",
            // Spanish (Español)
            "este mensaje fue eliminado",
            "este mensaje ha sido eliminado",
            "se eliminó este mensaje",
            "se elimino este mensaje",
            "mensaje eliminado",
            "mensaje anulado",
            "anuló un mensaje",
            "anulo un mensaje",
            // French (Français)
            "ce message a été supprimé",
            "ce message a ete supprime",
            "ce message a été effacé",
            "ce message a ete efface",
            "message supprimé",
            "message supprime",
            "a annulé l'envoi d'un message",
            "a annule l'envoi d'un message"
    };

    /**
     * Placeholders meaning the OWNER removed their own message. Checked first and never flagged:
     * the feature exists to catch what other people took back, not to report the owner to himself.
     */
    private static final String[] OWN_DELETED = {
            // English
            "you deleted this message",
            "you unsent a message",
            "you deleted a message",
            // Hindi (हिंदी)
            "आपने यह संदेश हटा दिया था",
            "आपने यह संदेश हटा दिया है",
            "आपने यह संदेश हटा दिया",
            "आपने संदेश हटा दिया",
            "आपने यह संदेश मिटा दिया",
            // Odia (ଓଡ଼ିଆ)
            "ଆପଣ ଏହି ବାର୍ତ୍ତାଟି ଡିଲିଟ୍ କରିଛନ୍ତି",
            "ଆପଣ ଏହି ମେସେଜ୍ ଡିଲିଟ୍ କରିଛନ୍ତି",
            "ଆପଣ ଏହି ସନ୍ଦେଶ ହଟାଇ ଦେଇଛନ୍ତି",
            // Spanish (Español)
            "eliminaste este mensaje",
            "has eliminado este mensaje",
            "anulaste un mensaje",
            // French (Français)
            "vous avez supprimé ce message",
            "vous avez supprime ce message",
            "vous avez annulé l'envoi d'un message",
            "vous avez annule l'envoi d'un message"
    };

    /** How a notification left the shade. Mapped from Android's REASON_* constants by the listener. */
    public enum Removal {
        /** The owner swiped it away. Cannot be a deletion. */
        USER_DISMISSED,
        /** The owner tapped it, so they read it. Cannot be an unseen deletion. */
        USER_CLICKED,
        /** The posting app withdrew it. The only removal that can indicate a deletion. */
        APP_CANCELLED,
        /** Anything else: reboot, package change, listener shutdown, timeout. Not evidence. */
        OTHER
    }

    /** Confidence that a message was removed by its sender. */
    public enum Confidence {
        /** Not a deletion. */
        NONE,
        /** The app said so outright. */
        CONFIRMED,
        /** Inferred, and must be shown as an inference. */
        SUSPECTED
    }

    // ─── Placeholder detection ───

    /**
     * True when the text is a placeholder indicating the SENDER removed a message.
     *
     * Returns false for the owner's own deletions, checked first so that "You deleted this message"
     * cannot match the looser "deleted this message" entry.
     */
    public static boolean isSenderDeletion(String text) {
        String t = normalise(text);
        if (t.isEmpty()) return false;
        if (containsAny(t, OWN_DELETED)) return false;
        return containsAny(t, SENDER_DELETED);
    }

    /** True when the placeholder refers to the owner removing their own message. */
    public static boolean isOwnDeletion(String text) {
        String t = normalise(text);
        return !t.isEmpty() && containsAny(t, OWN_DELETED);
    }

    /** True for any recognised deletion placeholder, whoever performed it. Used to avoid archiving it. */
    public static boolean isAnyDeletionPlaceholder(String text) {
        return isSenderDeletion(text) || isOwnDeletion(text);
    }

    // ─── Removal classification ───

    /**
     * Decide the confidence for a notification that was removed without a placeholder arriving.
     *
     * Every condition exists to remove a specific false positive:
     *  - a placeholder already seen means the confirmed path owns this event;
     *  - only an app-initiated cancel can be a deletion, so owner dismissal and owner taps are out;
     *  - the screen must have been off or locked for the notification's whole life, because
     *    otherwise the owner could have opened the chat, which also makes the app cancel it. This
     *    single condition is what separates a usable signal from constant noise;
     *  - and the notification must not be older than {@link #MAX_SUSPECT_AGE_MS}.
     *
     * @param removal              how the notification left the shade
     * @param screenOffThroughout  true only if the screen stayed off/locked for its entire life
     * @param ageMs                how long it was alive, in milliseconds
     * @param msSincePlaceholder   milliseconds since a placeholder was seen for this conversation,
     *                             or a negative value if none has been seen
     */
    public static Confidence classifyRemoval(Removal removal, boolean screenOffThroughout,
                                             long ageMs, long msSincePlaceholder) {
        if (removal == null) return Confidence.NONE;
        if (msSincePlaceholder >= 0 && msSincePlaceholder <= PLACEHOLDER_SUPPRESSION_MS) {
            return Confidence.NONE;
        }
        if (removal != Removal.APP_CANCELLED) return Confidence.NONE;
        if (!screenOffThroughout) return Confidence.NONE;
        if (ageMs < 0 || ageMs > MAX_SUSPECT_AGE_MS) return Confidence.NONE;
        return Confidence.SUSPECTED;
    }

    // ─── Sender parsing ───

    /** A notification's sender, kept alongside the raw title so nothing captured is ever lost. */
    public static final class Parsed {
        /** The notification title exactly as it arrived. */
        public final String rawTitle;
        /** Best guess at the individual who sent it; equals rawTitle for one-to-one chats. */
        public final String sender;
        /** Group name when this was a group conversation, otherwise empty. */
        public final String group;
        /** Message body with any "Sender: " prefix removed. */
        public final String body;

        Parsed(String rawTitle, String sender, String group, String body) {
            this.rawTitle = rawTitle;
            this.sender = sender;
            this.group = group;
            this.body = body;
        }

        public boolean fromGroup() { return !group.isEmpty(); }
    }

    /**
     * Work out who actually sent a message.
     *
     * One-to-one: the title is the sender and the text is the body, so both pass through unchanged.
     *
     * Group: the title is the group name and the text is usually "Sender: message". Splitting is
     * done on the FIRST colon only, so a body that itself contains a colon ("Ana: Note: call me")
     * still yields sender "Ana" and body "Note: call me".
     *
     * Splitting is attempted only when the notification is actually a group conversation. Doing it
     * unconditionally would wreck a one-to-one message like "Note: call me", turning "Note" into
     * the sender. When the caller cannot tell, a title containing " @ " (the other WhatsApp group
     * format, "Sender @ Group") is also treated as a group.
     *
     * @param groupHint true when the platform reported a group conversation
     */
    public static Parsed parseSender(String title, String text, boolean groupHint) {
        String rawTitle = title == null ? "" : title.trim();
        String body = text == null ? "" : text.trim();

        // "Sender @ Group" carries both parts in the title itself.
        int at = rawTitle.indexOf(" @ ");
        if (at > 0) {
            String who = rawTitle.substring(0, at).trim();
            String grp = rawTitle.substring(at + 3).trim();
            if (!who.isEmpty() && !grp.isEmpty()) return new Parsed(rawTitle, who, grp, body);
        }

        if (!groupHint) return new Parsed(rawTitle, rawTitle, "", body);

        // A group conversation: the sender is prefixed onto the body.
        int colon = body.indexOf(':');
        if (colon > 0 && colon <= MAX_SENDER_NAME) {
            String who = body.substring(0, colon).trim();
            String rest = body.substring(colon + 1).trim();
            // A name containing a line break is a mis-parse, not a name.
            if (!who.isEmpty() && !rest.isEmpty() && who.indexOf('\n') < 0) {
                return new Parsed(rawTitle, who, rawTitle, rest);
            }
        }
        // Group with no usable prefix: the individual is unknown, so keep the body whole rather
        // than guess. An empty sender is honest; a wrong one corrupts the archive.
        return new Parsed(rawTitle, "", rawTitle, body);
    }

    // ─── Duplicate suppression ───

    /**
     * Stable fingerprint for a captured notification.
     *
     * Needed because messaging apps legitimately re-post the same notification several times as a
     * conversation accumulates, and because reconnecting the listener re-reads everything already
     * showing in the shade. Without this, every reboot would duplicate the visible notifications.
     *
     * The notification key is included because it is stable per conversation, so an updated
     * notification carrying new text produces a new fingerprint (a genuinely new message) while an
     * identical re-post produces the same one.
     *
     * WHY POST TIME IS PART OF THE IDENTITY: without it, the same short text sent twice in one
     * conversation ("ok", then "ok" again) would collide and the second message would be silently
     * dropped. Re-reading the shade after a reconnect replays the ORIGINAL post time, so including
     * it still suppresses those duplicates while keeping genuinely repeated messages distinct.
     *
     * Falls back to a plain concatenation if SHA-256 is somehow unavailable, so capture degrades
     * rather than failing.
     */
    public static String fingerprint(String pkg, String key, String title, String text, long postTime) {
        String joined = safe(pkg) + "\u0000" + safe(key) + "\u0000" + safe(title)
                + "\u0000" + safe(text) + "\u0000" + postTime;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(joined.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                int v = b & 0xFF;
                if (v < 0x10) hex.append('0');
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (Exception e) {
            return "raw:" + joined.hashCode();
        }
    }

    // ─── Labels ───

    /**
     * The status line shown on a flagged card.
     *
     * The confirmed wording is the owner's specified format. The suspected wording is deliberately
     * different in both shape and content: it says "possibly", names the uncertainty, and cannot be
     * mistaken for the confirmed line at a glance.
     *
     * Time is formatted against an explicit zone so the output is deterministic and testable; the
     * UI passes the device's default zone.
     */
    public static String statusLabel(Confidence confidence, long whenMs, TimeZone zone) {
        if (confidence == null || confidence == Confidence.NONE) return "";
        String when = formatTime(whenMs, zone);
        if (confidence == Confidence.CONFIRMED) {
            return "[STATUS: SENDER UNSENT THIS MESSAGE AT " + when + "]";
        }
        return "[STATUS: POSSIBLY UNSENT AT " + when + " \u2014 NOT CONFIRMED]";
    }

    /**
     * Exact forensic timeline indicating when the message was posted, when it was deleted or withdrawn,
     * and its total active lifespan (e.g. "Sent at 10:14 PM → Deleted at 10:19 PM (Existed for 5m 12s)").
     */
    public static String deletionTimeline(Confidence confidence, long postTime, long deletionTime, TimeZone zone) {
        if (confidence == null || confidence == Confidence.NONE) return "";
        if (deletionTime > 0 && postTime > 0 && deletionTime >= postTime) {
            String sentAt = formatClockTime(postTime, zone);
            String action = (confidence == Confidence.CONFIRMED) ? "Deleted at " : "Withdrawn at ";
            String deletedAt = formatClockTime(deletionTime, zone);
            String lifespan = formatDuration(deletionTime - postTime);
            return "Sent at " + sentAt + " \u2192 " + action + deletedAt + " (Existed for " + lifespan + ")";
        } else if (deletionTime > 0) {
            String action = (confidence == Confidence.CONFIRMED) ? "Deleted at " : "Withdrawn at ";
            return action + formatClockTime(deletionTime, zone);
        }
        return "";
    }

    /**
     * Overloaded status label including both the status banner and exact forensics timeline when available.
     */
    public static String statusLabel(Confidence confidence, long postTime, long deletionTime, TimeZone zone) {
        if (confidence == null || confidence == Confidence.NONE) return "";
        String base = statusLabel(confidence, deletionTime, zone);
        String timeline = deletionTimeline(confidence, postTime, deletionTime, zone);
        if (!timeline.isEmpty()) {
            return base + "\n" + timeline;
        }
        return base;
    }

    /** Formats a timestamp into clock time (e.g. "10:14 PM"). */
    public static String formatClockTime(long whenMs, TimeZone zone) {
        if (whenMs <= 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.US);
        format.setTimeZone(zone == null ? TimeZone.getTimeZone("UTC") : zone);
        return format.format(new Date(whenMs));
    }

    /** Formats a millisecond duration into a concise forensic lifespan (e.g. "5m 12s", "42s", "1h 14m 30s"). */
    public static String formatDuration(long durationMs) {
        if (durationMs <= 0) return "< 1s";
        long totalSeconds = durationMs / 1000L;
        if (totalSeconds < 1) return "< 1s";
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        } else if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        } else if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        } else {
            return seconds + "s";
        }
    }

    /**
     * One-line explanation of which message a flag applies to.
     *
     * A deletion placeholder does not say which message was removed, so when several arrived from
     * one person only the latest can be flagged. The UI states that outright instead of implying a
     * precision the platform never provided.
     */
    public static String flagCaveat(Confidence confidence) {
        if (confidence == Confidence.CONFIRMED) {
            return "The app did not say which message was removed, so this is the most recent one "
                    + "from this sender.";
        }
        if (confidence == Confidence.SUSPECTED) {
            return "This notification was withdrawn by the app while the screen was off, so it was "
                    + "probably unsent \u2014 but this is a guess, not confirmed.";
        }
        return "";
    }

    /** Readable timestamp for cards, exports and spoken replies. */
    public static String formatTime(long whenMs, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US);
        format.setTimeZone(zone == null ? TimeZone.getTimeZone("UTC") : zone);
        return format.format(new Date(whenMs));
    }

    // ─── Helpers ───

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    /** Lower-cased, trimmed, with curly apostrophes folded so "wasn’t" matches "wasn't". */
    private static String normalise(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase(Locale.US).replace('\u2019', '\'');
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
