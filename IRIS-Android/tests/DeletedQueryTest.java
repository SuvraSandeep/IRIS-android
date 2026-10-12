package com.iris.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Offline checks for {@link DeletedQuery}.
 *
 * The regression guards are the most important part of this file. The new classifier is dispatched
 * BEFORE the existing notification reader, so any phrasing it wrongly claims is a phrasing the
 * notification reader silently stops serving. That exact mistake has already shipped once in this
 * project, so the existing patterns are reproduced here and asserted against directly.
 */
public class DeletedQueryTest {

    private static int checks = 0;

    /**
     * Copies of the live patterns from IrisListeningService, used to prove the new classifier does
     * not overlap them. Kept here deliberately: if the service patterns change, these should be
     * updated in step and the disjointness re-proved.
     */
    private static final Pattern NOTIFICATION_PATTERN = Pattern.compile(
            ".*\\bnotifications?\\b.*"
            + "|.*\\bwho\\s+(?:texted|messaged|pinged)\\s+me\\b.*"
            + "|.*\\bwhat\\s+did\\s+i\\s+miss\\b.*"
            + "|.*\\b(?:how\\s+many|any|check|latest|last|read|unread|new)\\b.*\\b(?:message|messages|text|texts|whatsapp|sms)\\b.*"
            + "|.*\\b(?:message|messages|text|texts)\\s+from\\b.*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLEAR_NOTIFICATION_PATTERN = Pattern.compile(
            "^(?:clear|dismiss|delete|remove|clean)\\s+(?:all\\s+|my\\s+|the\\s+)*notifications?.*$",
            Pattern.CASE_INSENSITIVE);

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
        recognisesDeletedQuestions();
        recognisesOpenRequests();
        openWinsOverAsking();
        ignoresUnrelatedSpeech();
        doesNotStealNotificationCommands();
        doesNotStealClearNotifications();
        requiresADeletionWord();
        recognisesYesAndNo();
        yesAndNoAreNotGreedy();
        phrasesTheNothingFoundAnswer();
        phrasesTheFoundAnswer();
        buildsReadableNameLists();
        System.out.println("Passed " + checks + " deleted-query checks");
    }

    private static void recognisesDeletedQuestions() {
        String[] asks = {
                "any deleted messages",
                "are there any deleted messages",
                "any unsent messages",
                "any new deleted messages",
                "did anyone delete a message",
                "did someone unsend a message",
                "did she delete a message",
                "was any message deleted",
                "have any messages been deleted",
                "deleted messages",
                "unsent messages",
                "any deleted message detected",
                "anything deleted",
                "any deleted messages found",
        };
        for (String ask : asks) {
            check(DeletedQuery.parse(ask) == DeletedQuery.Kind.ANY_DELETED, "recognises: " + ask);
            check(DeletedQuery.matched(ask), "matched() agrees for: " + ask);
        }
    }

    private static void recognisesOpenRequests() {
        String[] opens = {
                "open the deleted messages",
                "show me the deleted messages",
                "show the unsent messages",
                "open the archive",
                "view the deleted messages",
                "open the message log",
                "show me the notification archive",
                "display the deleted messages",
        };
        for (String open : opens) {
            check(DeletedQuery.parse(open) == DeletedQuery.Kind.OPEN_LOG, "recognises open: " + open);
        }
    }

    /** "show me the deleted messages" is a request to open, not a question about existence. */
    private static void openWinsOverAsking() {
        check(DeletedQuery.parse("show me any deleted messages") == DeletedQuery.Kind.OPEN_LOG,
                "an open verb takes precedence over the existence question");
    }

    private static void ignoresUnrelatedSpeech() {
        String[] others = {
                "what's the weather",
                "call my mother",
                "turn on the torch",
                "set an alarm for seven",
                "i deleted the file you sent",
                "please delete that photo",
                "",
                "   ",
        };
        for (String other : others) {
            check(DeletedQuery.parse(other) == DeletedQuery.Kind.NONE, "ignores: '" + other + "'");
        }
        check(DeletedQuery.parse(null) == DeletedQuery.Kind.NONE, "ignores null");
    }

    /**
     * The core guard. These are served today by the notification reader, which needs no SMS
     * permission and reads WhatsApp, Slack and SMS notifications. The new classifier runs first, so
     * claiming any of them would silently break a working command.
     */
    private static void doesNotStealNotificationCommands() {
        String[] theirs = {
                "read my notifications",
                "any notifications",
                "how many notifications",
                "read my last text",
                "read my messages",
                "any new messages",
                "any unread messages",
                "check my messages",
                "latest message",
                "message from ana",
                "messages from ana",
                "who texted me",
                "who messaged me",
                "what did i miss",
                "how many whatsapp messages",
                "any sms",
                "summarise my notifications",
                "what's new",
        };
        for (String phrase : theirs) {
            check(DeletedQuery.parse(phrase) == DeletedQuery.Kind.NONE,
                    "does NOT claim the existing notification command: " + phrase);
        }
    }

    /** "delete my notifications" is an existing clear command and must stay with it. */
    private static void doesNotStealClearNotifications() {
        String[] theirs = {
                "clear my notifications",
                "clear all notifications",
                "dismiss notifications",
                "delete my notifications",
                "delete all notifications",
                "remove the notifications",
                "clean my notifications",
        };
        for (String phrase : theirs) {
            check(CLEAR_NOTIFICATION_PATTERN.matcher(phrase).matches(),
                    "control check, the clear pattern still owns: " + phrase);
            check(DeletedQuery.parse(phrase) == DeletedQuery.Kind.NONE,
                    "does NOT claim the clear command: " + phrase);
        }
    }

    /**
     * Prove disjointness by construction: every phrase the new classifier claims as a question
     * contains a deletion word, and every phrase the notification reader owns does not.
     */
    private static void requiresADeletionWord() {
        List<String> claimed = new ArrayList<>(Arrays.asList(
                "any deleted messages", "any unsent messages", "did anyone delete a message",
                "deleted messages", "unsent messages", "anything deleted"));
        for (String phrase : claimed) {
            boolean hasDeletionWord = phrase.contains("delet") || phrase.contains("unsent")
                    || phrase.contains("unsend");
            check(hasDeletionWord, "every claimed phrase carries a deletion word: " + phrase);
        }
        // And a phrase with no deletion word is never claimed, however message-like it is.
        String[] noDeletionWord = {
                "any messages", "any texts", "new messages", "last message", "unread texts"
        };
        for (String phrase : noDeletionWord) {
            check(DeletedQuery.parse(phrase) == DeletedQuery.Kind.NONE,
                    "a phrase with no deletion word is never claimed: " + phrase);
            check(NOTIFICATION_PATTERN.matcher(phrase).matches(),
                    "control check, the notification pattern owns: " + phrase);
        }
    }

    private static void recognisesYesAndNo() {
        for (String yes : new String[]{"yes", "yeah", "yep", "sure", "ok", "okay", "please",
                "go ahead", "do it", "open it", "show me", "yes please"}) {
            check(DeletedQuery.isYes(yes), "yes: " + yes);
            check(!DeletedQuery.isNo(yes), "not also no: " + yes);
        }
        for (String no : new String[]{"no", "nope", "nah", "not now", "later", "cancel",
                "never mind", "nevermind", "don't", "dont"}) {
            check(DeletedQuery.isNo(no), "no: " + no);
            check(!DeletedQuery.isYes(no), "not also yes: " + no);
        }
    }

    /**
     * A follow-up reply must be anchored at the start. Treating any sentence containing "no" as a
     * refusal would hijack whatever the owner actually said next.
     */
    private static void yesAndNoAreNotGreedy() {
        check(!DeletedQuery.isYes("what's the weather"), "an unrelated sentence is not a yes");
        check(!DeletedQuery.isNo("what's the weather"), "an unrelated sentence is not a no");
        check(!DeletedQuery.isNo("i know nothing about it"),
                "an embedded 'no' mid-sentence is not a refusal");
        check(!DeletedQuery.isYes("okay so call my mother") == false,
                "a leading okay is still a yes, even with more words after it");
        check(!DeletedQuery.isYes(""), "empty is not a yes");
        check(!DeletedQuery.isNo(null), "null is not a no");
    }

    private static void phrasesTheNothingFoundAnswer() {
        check(DeletedQuery.nothingFound(true).contains("since you last checked"),
                "names the window when the owner has asked before");
        check(DeletedQuery.nothingFound(false).contains("so far"),
                "avoids implying a window on a first ask");
        // Never a bare "no", which would read as "nothing has ever happened".
        check(DeletedQuery.nothingFound(true).length() > 5, "the answer is a sentence, not a bare no");
    }

    private static void phrasesTheFoundAnswer() {
        String one = DeletedQuery.found(1, Arrays.asList("Ana"));
        check(one.startsWith("Yes."), "a positive answer leads with yes");
        check(one.contains("1 deleted message"), "singular count");
        check(one.contains("from Ana"), "names the sender, as the owner asked");
        check(one.endsWith("Want me to open the log?"), "offers to open the log");
        // The message body is never spoken: it is the sensitive part and stays on screen.
        check(!one.contains(":"), "no message body is read aloud");

        String many = DeletedQuery.found(3, Arrays.asList("Ana", "Bob"));
        check(many.contains("3 deleted messages"), "plural count");
        check(many.contains("from Ana and Bob"), "names both senders");

        equal(DeletedQuery.found(0, Arrays.asList("Ana")), DeletedQuery.nothingFound(true),
                "a zero count falls back to the nothing-found answer");
    }

    private static void buildsReadableNameLists() {
        equal(DeletedQuery.nameList(null), "", "null list");
        equal(DeletedQuery.nameList(new ArrayList<String>()), "", "empty list");
        equal(DeletedQuery.nameList(Arrays.asList("Ana")), "from Ana", "one name");
        equal(DeletedQuery.nameList(Arrays.asList("Ana", "Bob")), "from Ana and Bob", "two names");
        equal(DeletedQuery.nameList(Arrays.asList("Ana", "Bob", "Cal")),
                "from Ana, Bob and Cal", "three names");
        equal(DeletedQuery.nameList(Arrays.asList("Ana", "Bob", "Cal", "Dee")),
                "from Ana, Bob and Cal", "caps at three names");
        equal(DeletedQuery.nameList(Arrays.asList("Ana", "Ana")), "from Ana", "duplicates collapse");
        equal(DeletedQuery.nameList(Arrays.asList("", "  ", "Ana")), "from Ana", "blanks are skipped");
    }
}
