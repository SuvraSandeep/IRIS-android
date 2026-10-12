package com.iris.assistant;

import java.util.TimeZone;

/**
 * Offline checks for {@link DeletedMessageRules}, the decision layer of the notification archive.
 *
 * Runs on a plain JVM (no Android, no Robolectric) because the rules are deliberately Android-free.
 * Registered in scripts/test-speech.sh.
 *
 * The false-positive cases matter as much as the positive ones: a deletion archive that cries wolf
 * is worse than no archive, so owner-dismissal, owner-taps and owner's own deletions each have
 * their own pinned case.
 */
public class DeletedMessageRulesTest {

    private static int checks = 0;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(String actual, String expected, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        detectsSenderDeletionPlaceholders();
        doesNotFlagOwnDeletions();
        ignoresOrdinaryMessages();
        handlesNullAndEmptyText();
        toleratesCaseSpacingAndCurlyApostrophes();
        onlyAppCancelCanBeSuspected();
        suspectedRequiresScreenOff();
        suspectedRespectsAgeBound();
        placeholderSuppressesSuspicion();
        parsesOneToOneUnchanged();
        parsesGroupSenderFromBody();
        parsesGroupSenderFromBodyWithExtraColon();
        parsesSenderAtGroupTitle();
        groupWithoutPrefixLeavesSenderUnknown();
        doesNotSplitOneToOneContainingColon();
        rejectsImplausibleSenderNames();
        fingerprintIsStableAndDiscriminating();
        labelsDistinguishConfirmedFromSuspected();
        caveatsExplainTheLimitation();
        formatsTimeDeterministically();
        System.out.println("Passed " + checks + " deleted-message rule checks");
    }

    private static void detectsSenderDeletionPlaceholders() {
        check(DeletedMessageRules.isSenderDeletion("This message was deleted"), "WhatsApp wording");
        check(DeletedMessageRules.isSenderDeletion("Message unsent"), "Instagram wording");
        check(DeletedMessageRules.isSenderDeletion("Ana unsent a message"), "named unsend");
        check(DeletedMessageRules.isSenderDeletion("message deleted"), "short form");
        check(DeletedMessageRules.isSenderDeletion("Ana: This message was deleted"), "group-prefixed placeholder");
        check(DeletedMessageRules.isAnyDeletionPlaceholder("This message was deleted"), "counts as a placeholder");
    }

    /**
     * The owner's own deletion must never be reported as somebody taking a message back.
     * "You deleted this message" contains "deleted this message", so the owner list has to be
     * checked first; this case pins that ordering.
     */
    private static void doesNotFlagOwnDeletions() {
        check(!DeletedMessageRules.isSenderDeletion("You deleted this message"), "own deletion not a sender unsend");
        check(!DeletedMessageRules.isSenderDeletion("You unsent a message"), "own unsend not a sender unsend");
        check(DeletedMessageRules.isOwnDeletion("You deleted this message"), "own deletion recognised");
        check(!DeletedMessageRules.isOwnDeletion("This message was deleted"), "sender deletion is not an own deletion");
        check(DeletedMessageRules.isAnyDeletionPlaceholder("You deleted this message"),
                "own deletion still counts as a placeholder so it is not archived as a message");
    }

    private static void ignoresOrdinaryMessages() {
        check(!DeletedMessageRules.isSenderDeletion("Are you coming tonight?"), "ordinary message");
        check(!DeletedMessageRules.isSenderDeletion("I deleted the file you sent"), "unrelated use of deleted");
        check(!DeletedMessageRules.isSenderDeletion("Please delete that photo"), "request to delete");
        check(!DeletedMessageRules.isSenderDeletion("\uD83D\uDCF7 Photo"), "media placeholder");
    }

    private static void handlesNullAndEmptyText() {
        check(!DeletedMessageRules.isSenderDeletion(null), "null text is not a deletion");
        check(!DeletedMessageRules.isSenderDeletion(""), "empty text is not a deletion");
        check(!DeletedMessageRules.isSenderDeletion("   "), "blank text is not a deletion");
        check(!DeletedMessageRules.isOwnDeletion(null), "null is not an own deletion");
    }

    private static void toleratesCaseSpacingAndCurlyApostrophes() {
        check(DeletedMessageRules.isSenderDeletion("THIS MESSAGE WAS DELETED"), "upper case");
        check(DeletedMessageRules.isSenderDeletion("  This Message Was Deleted  "), "mixed case and padding");
        check(DeletedMessageRules.isSenderDeletion("This message was deleted\u2019"), "curly apostrophe folded");
    }

    /**
     * The core false-positive guard. Only the app withdrawing a notification can indicate a
     * deletion; the owner swiping it away or tapping it must never be mistaken for one.
     */
    private static void onlyAppCancelCanBeSuspected() {
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 5000, -1) == DeletedMessageRules.Confidence.SUSPECTED, "app cancel with screen off is suspected");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.USER_DISMISSED,
                true, 5000, -1) == DeletedMessageRules.Confidence.NONE, "owner swiping it away is not a deletion");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.USER_CLICKED,
                true, 5000, -1) == DeletedMessageRules.Confidence.NONE, "owner tapping it is not a deletion");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.OTHER,
                true, 5000, -1) == DeletedMessageRules.Confidence.NONE, "reboots and timeouts are not evidence");
        check(DeletedMessageRules.classifyRemoval(null,
                true, 5000, -1) == DeletedMessageRules.Confidence.NONE, "a null reason is not a deletion");
    }

    /**
     * Without the screen-off requirement this heuristic fires every time the owner opens a chat,
     * because reading a message also makes the app cancel its notification. This is the condition
     * that makes the signal usable rather than noise.
     */
    private static void suspectedRequiresScreenOff() {
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                false, 5000, -1) == DeletedMessageRules.Confidence.NONE,
                "an app cancel while the screen was on means the owner probably just read it");
    }

    private static void suspectedRespectsAgeBound() {
        long max = DeletedMessageRules.MAX_SUSPECT_AGE_MS;
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, max - 1, -1) == DeletedMessageRules.Confidence.SUSPECTED, "just inside the age bound");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, max + 1, -1) == DeletedMessageRules.Confidence.NONE,
                "an old notification being tidied up is not a deletion");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 0, -1) == DeletedMessageRules.Confidence.SUSPECTED, "an instant cancel still counts");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, -5, -1) == DeletedMessageRules.Confidence.NONE, "a negative age is rejected, not trusted");
    }

    /**
     * WhatsApp removes the original notification and posts the placeholder as two separate events.
     * Without suppression the one deletion would be reported twice, once confirmed and once
     * suspected.
     */
    private static void placeholderSuppressesSuspicion() {
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 1000, 0) == DeletedMessageRules.Confidence.NONE, "placeholder just seen suppresses suspicion");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 1000, DeletedMessageRules.PLACEHOLDER_SUPPRESSION_MS)
                == DeletedMessageRules.Confidence.NONE, "suppression includes its boundary");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 1000, DeletedMessageRules.PLACEHOLDER_SUPPRESSION_MS + 1)
                == DeletedMessageRules.Confidence.SUSPECTED, "suspicion resumes after the window");
        check(DeletedMessageRules.classifyRemoval(DeletedMessageRules.Removal.APP_CANCELLED,
                true, 1000, -1) == DeletedMessageRules.Confidence.SUSPECTED, "no placeholder ever seen");
    }

    private static void parsesOneToOneUnchanged() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Ana", "Are you coming?", false);
        equal(p.sender, "Ana", "one-to-one sender is the title");
        equal(p.body, "Are you coming?", "one-to-one body is unchanged");
        equal(p.group, "", "one-to-one has no group");
        check(!p.fromGroup(), "one-to-one is not a group");
        equal(p.rawTitle, "Ana", "raw title retained");
    }

    private static void parsesGroupSenderFromBody() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Trip Crew", "Ana: see you at 8", true);
        equal(p.sender, "Ana", "group sender parsed from the body");
        equal(p.body, "see you at 8", "prefix stripped from the body");
        equal(p.group, "Trip Crew", "group name is the title");
        check(p.fromGroup(), "recognised as a group");
        equal(p.rawTitle, "Trip Crew", "raw title retained for a group");
    }

    /** A body that itself contains a colon must split on the first one only. */
    private static void parsesGroupSenderFromBodyWithExtraColon() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Trip Crew", "Ana: Note: call me", true);
        equal(p.sender, "Ana", "splits on the first colon only");
        equal(p.body, "Note: call me", "remaining colon preserved in the body");
    }

    private static void parsesSenderAtGroupTitle() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Ana @ Trip Crew", "see you at 8", false);
        equal(p.sender, "Ana", "sender from the title");
        equal(p.group, "Trip Crew", "group from the title");
        check(p.fromGroup(), "the at-form is treated as a group even without a hint");
        equal(p.body, "see you at 8", "body untouched");
    }

    /** An unknown sender is honest; a guessed one corrupts the archive. */
    private static void groupWithoutPrefixLeavesSenderUnknown() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Trip Crew", "see you at 8", true);
        equal(p.sender, "", "sender left empty rather than guessed");
        equal(p.body, "see you at 8", "body kept whole");
        equal(p.group, "Trip Crew", "still recorded as a group");
    }

    /**
     * The reason splitting is gated on the group hint: a one-to-one message beginning with a word
     * and a colon would otherwise have its first word stolen as the sender.
     */
    private static void doesNotSplitOneToOneContainingColon() {
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender("Ana", "Note: call me later", false);
        equal(p.sender, "Ana", "sender stays the title");
        equal(p.body, "Note: call me later", "body is not split");
    }

    private static void rejectsImplausibleSenderNames() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 60; i++) longName.append('x');
        DeletedMessageRules.Parsed p = DeletedMessageRules.parseSender(
                "Trip Crew", longName + ": hello", true);
        equal(p.sender, "", "an over-long prefix is not treated as a name");

        DeletedMessageRules.Parsed q = DeletedMessageRules.parseSender(
                "Trip Crew", "Ana\nBob: hello", true);
        equal(q.sender, "", "a prefix containing a line break is rejected");

        DeletedMessageRules.Parsed r = DeletedMessageRules.parseSender("Trip Crew", "Ana:", true);
        equal(r.sender, "", "a prefix with no message after it is rejected");
    }

    private static void fingerprintIsStableAndDiscriminating() {
        String key = "0|com.whatsapp|1|null|10";
        long t1 = 1760000000000L;
        String a = DeletedMessageRules.fingerprint("com.whatsapp", key, "Ana", "hello", t1);
        String b = DeletedMessageRules.fingerprint("com.whatsapp", key, "Ana", "hello", t1);
        equal(a, b, "same inputs give the same fingerprint, so a reconnect cannot duplicate rows");

        String differentText = DeletedMessageRules.fingerprint("com.whatsapp", key, "Ana", "hello again", t1);
        check(!a.equals(differentText), "new text in the same conversation is a new message");

        String differentPkg = DeletedMessageRules.fingerprint("com.whatsapp.w4b", key, "Ana", "hello", t1);
        check(!a.equals(differentPkg), "the same message from a cloned app is distinct");

        String differentSender = DeletedMessageRules.fingerprint("com.whatsapp", key, "Bob", "hello", t1);
        check(!a.equals(differentSender), "same text from another sender is distinct");

        // The reason post time is part of the identity: the same short reply sent twice must not
        // collide, or the second one would be silently lost from the archive.
        String repeated = DeletedMessageRules.fingerprint("com.whatsapp", key, "Ana", "ok", t1);
        String repeatedLater = DeletedMessageRules.fingerprint("com.whatsapp", key, "Ana", "ok", t1 + 60000);
        check(!repeated.equals(repeatedLater), "the same text sent twice stays two distinct messages");

        check(a.length() == 64, "fingerprint is a SHA-256 hex digest");
        check(DeletedMessageRules.fingerprint(null, null, null, null, 0L) != null,
                "all-null input still yields a usable fingerprint");
    }

    /** A guess must never be able to read as a fact at a glance. */
    private static void labelsDistinguishConfirmedFromSuspected() {
        long when = 1760000000000L;
        String confirmed = DeletedMessageRules.statusLabel(DeletedMessageRules.Confidence.CONFIRMED, when, UTC);
        String suspected = DeletedMessageRules.statusLabel(DeletedMessageRules.Confidence.SUSPECTED, when, UTC);

        check(confirmed.startsWith("[STATUS: SENDER UNSENT THIS MESSAGE AT "), "confirmed uses the specified wording");
        check(confirmed.endsWith("]"), "confirmed label is closed");
        check(suspected.contains("POSSIBLY UNSENT"), "suspected label says possibly");
        check(suspected.contains("NOT CONFIRMED"), "suspected label states it is unconfirmed");
        check(!suspected.contains("SENDER UNSENT THIS MESSAGE"), "suspected cannot be mistaken for confirmed");
        equal(DeletedMessageRules.statusLabel(DeletedMessageRules.Confidence.NONE, when, UTC), "",
                "an unflagged message has no status label");
        equal(DeletedMessageRules.statusLabel(null, when, UTC), "", "a null confidence yields no label");
    }

    private static void caveatsExplainTheLimitation() {
        String confirmed = DeletedMessageRules.flagCaveat(DeletedMessageRules.Confidence.CONFIRMED);
        check(confirmed.contains("most recent"),
                "confirmed caveat admits only the latest message from that sender can be flagged");
        String suspected = DeletedMessageRules.flagCaveat(DeletedMessageRules.Confidence.SUSPECTED);
        check(suspected.contains("guess"), "suspected caveat states plainly that it is a guess");
        equal(DeletedMessageRules.flagCaveat(DeletedMessageRules.Confidence.NONE), "", "no caveat when unflagged");
    }

    private static void formatsTimeDeterministically() {
        String formatted = DeletedMessageRules.formatTime(0L, UTC);
        equal(formatted, "1 Jan 1970, 00:00", "epoch formats predictably in UTC");
        String ist = DeletedMessageRules.formatTime(0L, TimeZone.getTimeZone("Asia/Kolkata"));
        equal(ist, "1 Jan 1970, 05:30", "rendered in the supplied zone");
        check(DeletedMessageRules.formatTime(0L, null).equals("1 Jan 1970, 00:00"),
                "a null zone falls back to UTC rather than throwing");
    }
}
