package com.iris.assistant;

/**
 * Offline checks for the responder guards.
 *
 * These are the checks that stop the feature embarrassing its owner, so they are written as
 * adversarially as possible: each one describes a specific way the responder could misbehave and
 * proves it cannot.
 *
 * The single most important case is the burst. One notification on the owner's own phone held
 * seven messages from one chat. Without the burst guard that is seven replies to one person in one
 * second, which is the kind of thing that ends a conversation permanently.
 */
public class ResponderGuardsTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void verdict(ResponderGuards.Message m, ResponderGuards.Policy p,
                                ResponderGuards.Verdict expected, String what) {
        checks++;
        ResponderGuards.Verdict actual = ResponderGuards.evaluate(m, p);
        if (actual != expected) {
            throw new AssertionError("FAILED: " + what
                    + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    /** A message and policy that would send, so each test can break exactly one thing. */
    private static ResponderGuards.Message sendableMessage() {
        ResponderGuards.Message m = new ResponderGuards.Message();
        m.category = MessageCategory.Kind.GREETING;
        m.tone = ReplyTone.Tone.PROFESSIONAL;
        m.incomingText = "hello";
        m.lastSentReply = "";
        m.group = false;
        m.mentionsOwner = false;
        m.savedContact = true;
        m.hasReplyAction = true;
        m.fromOwner = false;
        return m;
    }

    private static ResponderGuards.Policy sendablePolicy() {
        ResponderGuards.Policy p = new ResponderGuards.Policy();
        p.state = ResponderGuards.State.LIVE;
        p.nowMs = 1_700_000_000_000L;
        p.armedUntilMs = p.nowMs + 3_600_000L;
        p.armedIndefinitely = false;
        p.phoneInUse = false;
        p.replyToPersonal = true;
        p.replyToGroups = false;
        p.groupsRequireMention = true;
        p.replyToUnsaved = false;
        p.contactRule = ResponderGuards.ContactRule.INHERIT;
        p.allowlistOnly = false;
        p.cooldownMs = 2 * 60 * 60 * 1000L;
        p.lastReplyToConversationMs = 0;
        p.burstAlreadyAnswered = false;
        p.repliesToContactToday = 0;
        p.contactCapPerDay = 3;
        p.repliesTodayTotal = 0;
        p.dailyCapTotal = 30;
        return p;
    }

    public static void main(String[] args) {
        theBaselineActuallySends();
        offAndExpiredStatesRefuse();
        phoneInUseStopsEverything();
        appsWithoutReplyAreSkipped();
        ownMessagesAreIgnored();
        nonMessagesAreRefused();
        neverOutranksEverything();
        nameRuleCanRefuse();
        fixedReplyBypassesOnlyCategorySilence();
        allowlistModeExcludesByDefault();
        scopeRules();
        groupMentionsRule();
        unsavedContactsAreRefusedByDefault();
        toneDrivenSilence();
        burstProducesOneReplyNotSeven();
        sameTextIsNotAnsweredTwice();
        familyPriorityBypassesRateLimitsOnly();
        cooldownBlocksAndThenReleases();
        capsBlockIncludingZero();
        loopDetection();
        clockSafetyChoosesSilence();
        everyVerdictExplainsItself();
        nullsFailSafe();
        System.out.println("Passed " + checks + " responder guard checks");
    }

    private static void theBaselineActuallySends() {
        verdict(sendableMessage(), sendablePolicy(), ResponderGuards.Verdict.SEND,
                "the baseline sends, so every other test breaks exactly one thing");
    }

    private static void offAndExpiredStatesRefuse() {
        ResponderGuards.Policy off = sendablePolicy();
        off.state = ResponderGuards.State.OFF;
        verdict(sendableMessage(), off, ResponderGuards.Verdict.NOT_ARMED, "OFF refuses");

        // DRY_RUN still evaluates: the decision is made and logged, and only the send is withheld
        // by the caller. Otherwise a dry run would prove nothing.
        ResponderGuards.Policy dry = sendablePolicy();
        dry.state = ResponderGuards.State.DRY_RUN;
        verdict(sendableMessage(), dry, ResponderGuards.Verdict.SEND,
                "DRY RUN reaches the same verdict, so the log shows what would have happened");

        ResponderGuards.Policy expired = sendablePolicy();
        expired.armedUntilMs = expired.nowMs - 1;
        verdict(sendableMessage(), expired, ResponderGuards.Verdict.DISARMED_EXPIRED,
                "an elapsed armed period refuses");

        ResponderGuards.Policy indefinite = sendablePolicy();
        indefinite.armedIndefinitely = true;
        indefinite.armedUntilMs = 0;
        verdict(sendableMessage(), indefinite, ResponderGuards.Verdict.SEND,
                "armed indefinitely never expires");
    }

    private static void phoneInUseStopsEverything() {
        ResponderGuards.Policy inUse = sendablePolicy();
        inUse.phoneInUse = true;
        verdict(sendableMessage(), inUse, ResponderGuards.Verdict.PHONE_IN_USE,
                "if Suvra is holding the phone he can answer it himself");

        // Even an urgent mention from an allowed contact waits.
        ResponderGuards.Message urgent = sendableMessage();
        urgent.category = MessageCategory.Kind.URGENT;
        urgent.mentionsOwner = true;
        verdict(urgent, inUse, ResponderGuards.Verdict.PHONE_IN_USE,
                "phone-in-use outranks even an urgent mention");

        // ...unless the owner has said to answer anyway. Added at his explicit request after
        // device testing, where a phone awake on the desk meant nothing was ever answered.
        ResponderGuards.Policy override = sendablePolicy();
        override.phoneInUse = true;
        override.replyWhileInUse = true;
        verdict(sendableMessage(), override, ResponderGuards.Verdict.SEND,
                "the owner can opt into being answered for while he's using the phone");
        verdict(urgent, override, ResponderGuards.Verdict.SEND,
                "the override also applies to an urgent mention");

        // The override must not reach past its own guard and weaken anything else.
        ResponderGuards.Policy stillOff = sendablePolicy();
        stillOff.phoneInUse = true;
        stillOff.replyWhileInUse = true;
        stillOff.state = ResponderGuards.State.OFF;
        verdict(sendableMessage(), stillOff, ResponderGuards.Verdict.NOT_ARMED,
                "answering while in use does not switch the responder on");

        ResponderGuards.Policy stillBlocked = sendablePolicy();
        stillBlocked.phoneInUse = true;
        stillBlocked.replyWhileInUse = true;
        stillBlocked.contactRule = ResponderGuards.ContactRule.NEVER;
        verdict(sendableMessage(), stillBlocked, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "answering while in use does not override a NEVER contact");

        // Off by default, so an existing install behaves exactly as before the option existed.
        ResponderGuards.Policy fresh = sendablePolicy();
        check(!fresh.replyWhileInUse, "replying while in use is off unless chosen");
        fresh.phoneInUse = true;
        verdict(sendableMessage(), fresh, ResponderGuards.Verdict.PHONE_IN_USE,
                "the default still defers to Suvra");

        // The override is only relevant when the phone IS in use; it must not be a general
        // bypass that changes anything when he is not holding it.
        ResponderGuards.Policy notInUse = sendablePolicy();
        notInUse.phoneInUse = false;
        notInUse.replyWhileInUse = true;
        verdict(sendableMessage(), notInUse, ResponderGuards.Verdict.SEND,
                "the override is harmless when the phone is idle");
    }

    /** The owner's requirement: apps that cannot be replied to are simply ignored. */
    private static void appsWithoutReplyAreSkipped() {
        ResponderGuards.Message noAction = sendableMessage();
        noAction.hasReplyAction = false;
        verdict(noAction, sendablePolicy(), ResponderGuards.Verdict.NO_REPLY_ACTION,
                "an app with no reply action is skipped, not treated as an error");
    }

    private static void ownMessagesAreIgnored() {
        ResponderGuards.Message own = sendableMessage();
        own.fromOwner = true;
        verdict(own, sendablePolicy(), ResponderGuards.Verdict.OWN_MESSAGE,
                "Suvra's own message from another device is never answered");
    }

    private static void nonMessagesAreRefused() {
        ResponderGuards.Message rollup = sendableMessage();
        rollup.rollup = true;
        verdict(rollup, sendablePolicy(), ResponderGuards.Verdict.BLOCKED_CONTENT,
                "'14 messages from 3 chats' is not a message and gets no reply");

        ResponderGuards.Message placeholder = sendableMessage();
        placeholder.placeholder = true;
        verdict(placeholder, sendablePolicy(), ResponderGuards.Verdict.BLOCKED_CONTENT,
                "'this message was deleted' gets no reply");

        ResponderGuards.Message flagged = sendableMessage();
        flagged.flaggedDeleted = true;
        verdict(flagged, sendablePolicy(), ResponderGuards.Verdict.BLOCKED_CONTENT,
                "a message the sender already deleted gets no reply");
    }

    /** NEVER must beat everything, or the setting is worthless. */
    private static void neverOutranksEverything() {
        ResponderGuards.Policy never = sendablePolicy();
        never.contactRule = ResponderGuards.ContactRule.NEVER;

        verdict(sendableMessage(), never, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "NEVER refuses an ordinary message");

        ResponderGuards.Message urgentMention = sendableMessage();
        urgentMention.category = MessageCategory.Kind.URGENT;
        urgentMention.mentionsOwner = true;
        verdict(urgentMention, never, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "NEVER refuses even an urgent message that names Suvra");

        ResponderGuards.Message mention = sendableMessage();
        mention.category = MessageCategory.Kind.MENTION;
        mention.group = true;
        mention.mentionsOwner = true;
        ResponderGuards.Policy groupsOn = sendablePolicy();
        groupsOn.contactRule = ResponderGuards.ContactRule.NEVER;
        groupsOn.replyToGroups = true;
        verdict(mention, groupsOn, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "NEVER refuses a group mention too");
    }

    /**
     * A rule on the sender's NAME can refuse them, and must not outrank a decision about the
     * person themselves.
     */
    private static void nameRuleCanRefuse() {
        ResponderGuards.Policy blocked = sendablePolicy();
        blocked.nameRuleBlocked = true;
        verdict(sendableMessage(), blocked, ResponderGuards.Verdict.NAME_RULE_BLOCKED,
                "a name rule saying never stops the reply");

        // An explicit ALLOW is a decision about this person and beats a pattern that merely
        // matches their name -- otherwise an "eric" rule would silence a deliberately allowed
        // relative called Erica for good.
        ResponderGuards.Policy allowed = sendablePolicy();
        allowed.nameRuleBlocked = true;
        allowed.contactRule = ResponderGuards.ContactRule.ALLOW;
        verdict(sendableMessage(), allowed, ResponderGuards.Verdict.SEND,
                "an explicit ALLOW on the contact overrides a name-pattern refusal");

        // NEVER on the contact is reported as the contact rule, the more specific reason.
        ResponderGuards.Policy both = sendablePolicy();
        both.nameRuleBlocked = true;
        both.contactRule = ResponderGuards.ContactRule.NEVER;
        verdict(sendableMessage(), both, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "an explicit NEVER is reported ahead of the name rule");

        // Urgency and mentions do not override it, same as a NEVER contact.
        ResponderGuards.Message urgent = sendableMessage();
        urgent.category = MessageCategory.Kind.URGENT;
        urgent.mentionsOwner = true;
        verdict(urgent, blocked, ResponderGuards.Verdict.NAME_RULE_BLOCKED,
                "urgency does not override a name-rule refusal");

        ResponderGuards.Policy clear = sendablePolicy();
        check(!clear.nameRuleBlocked, "no name rule blocks by default");
        verdict(sendableMessage(), clear, ResponderGuards.Verdict.SEND,
                "an unmatched name rule changes nothing");
    }

    /**
     * A fixed reply set by name bypasses the category-silence guard, and nothing else.
     *
     * The owner has said what to send to this person, so "that kind of message earns no reply"
     * does not apply. Every safety guard still must.
     */
    private static void fixedReplyBypassesOnlyCategorySilence() {
        // A bare "ok" is ACKNOWLEDGEMENT, silent in the professional tone. Normally refused...
        ResponderGuards.Message ack = sendableMessage();
        ack.category = MessageCategory.Kind.ACKNOWLEDGEMENT;
        ack.tone = ReplyTone.Tone.PROFESSIONAL;
        verdict(ack, sendablePolicy(), ResponderGuards.Verdict.CATEGORY_SILENT,
                "a professional-tone acknowledgement is silent without a fixed reply");

        // ...but a fixed reply means the owner wants an answer to this sender regardless.
        ResponderGuards.Message forced = sendableMessage();
        forced.category = MessageCategory.Kind.ACKNOWLEDGEMENT;
        forced.tone = ReplyTone.Tone.PROFESSIONAL;
        forced.forceReply = true;
        verdict(forced, sendablePolicy(), ResponderGuards.Verdict.SEND,
                "a fixed reply overrides category silence");

        // It does not override anything else. A NEVER contact stays silent.
        ResponderGuards.Policy never = sendablePolicy();
        never.contactRule = ResponderGuards.ContactRule.NEVER;
        verdict(forced, never, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "a fixed reply does not override a NEVER contact");

        // Nor the cooldown.
        ResponderGuards.Policy cooling = sendablePolicy();
        cooling.cooldownMs = 60_000;
        cooling.lastReplyToConversationMs = cooling.nowMs - 1_000;
        verdict(forced, cooling, ResponderGuards.Verdict.COOLDOWN,
                "a fixed reply still waits out the cooldown");

        // Nor the phone-in-use guard, when the owner has not opted out of it.
        ResponderGuards.Policy inUse = sendablePolicy();
        inUse.phoneInUse = true;
        verdict(forced, inUse, ResponderGuards.Verdict.PHONE_IN_USE,
                "a fixed reply still defers to the phone being in use");
    }

    /** Allowlist mode excludes by default. */
    private static void allowlistModeExcludesByDefault() {
        ResponderGuards.Policy strict = sendablePolicy();
        strict.allowlistOnly = true;
        verdict(sendableMessage(), strict, ResponderGuards.Verdict.NOT_ALLOWLISTED,
                "in allowlist mode an uninstructed contact is excluded");

        strict.contactRule = ResponderGuards.ContactRule.ALLOW;
        verdict(sendableMessage(), strict, ResponderGuards.Verdict.SEND,
                "an explicitly allowed contact passes in allowlist mode");
    }

    private static void scopeRules() {
        ResponderGuards.Message group = sendableMessage();
        group.group = true;
        verdict(group, sendablePolicy(), ResponderGuards.Verdict.GROUP_NOT_ENABLED,
                "groups are refused by default");

        ResponderGuards.Policy noPersonal = sendablePolicy();
        noPersonal.replyToPersonal = false;
        verdict(sendableMessage(), noPersonal, ResponderGuards.Verdict.PERSONAL_NOT_ENABLED,
                "one-to-one can be switched off independently");
    }

    private static void groupMentionsRule() {
        ResponderGuards.Policy groupsOn = sendablePolicy();
        groupsOn.replyToGroups = true;
        groupsOn.groupsRequireMention = true;

        ResponderGuards.Message silentGroup = sendableMessage();
        silentGroup.group = true;
        silentGroup.mentionsOwner = false;
        verdict(silentGroup, groupsOn, ResponderGuards.Verdict.NOT_MENTIONED,
                "a group message that does not name Suvra is left alone");

        ResponderGuards.Message named = sendableMessage();
        named.group = true;
        named.mentionsOwner = true;
        verdict(named, groupsOn, ResponderGuards.Verdict.SEND,
                "a group message naming Suvra is answered");

        groupsOn.groupsRequireMention = false;
        verdict(silentGroup, groupsOn, ResponderGuards.Verdict.SEND,
                "with mentions not required, any group message is answered");
    }

    private static void unsavedContactsAreRefusedByDefault() {
        ResponderGuards.Message unsaved = sendableMessage();
        unsaved.savedContact = false;
        verdict(unsaved, sendablePolicy(), ResponderGuards.Verdict.UNSAVED_CONTACT,
                "an unknown number is not told the phone is unattended");

        ResponderGuards.Policy permissive = sendablePolicy();
        permissive.replyToUnsaved = true;
        verdict(unsaved, permissive, ResponderGuards.Verdict.SEND,
                "unsaved senders can be allowed deliberately");
    }

    /** The owner's acknowledgement rule, reaching all the way through the guards. */
    private static void toneDrivenSilence() {
        ResponderGuards.Message ack = sendableMessage();
        ack.category = MessageCategory.Kind.ACKNOWLEDGEMENT;
        ack.tone = ReplyTone.Tone.PROFESSIONAL;
        verdict(ack, sendablePolicy(), ResponderGuards.Verdict.CATEGORY_SILENT,
                "'ok' is silent when professional");

        ack.tone = ReplyTone.Tone.SARCASTIC;
        verdict(ack, sendablePolicy(), ResponderGuards.Verdict.SEND,
                "'ok' is answered when sarcastic");

        ResponderGuards.Message nothing = sendableMessage();
        nothing.category = MessageCategory.Kind.NONE;
        for (ReplyTone.Tone tone : ReplyTone.Tone.values()) {
            nothing.tone = tone;
            verdict(nothing, sendablePolicy(), ResponderGuards.Verdict.CATEGORY_SILENT,
                    "an unrecognised message is silent in " + tone);
        }
    }

    /** Seven messages arrived in one notification on the owner's phone. One reply, not seven. */
    private static void burstProducesOneReplyNotSeven() {
        ResponderGuards.Policy policy = sendablePolicy();
        verdict(sendableMessage(), policy, ResponderGuards.Verdict.SEND,
                "the first message of a burst is answered");

        policy.burstAlreadyAnswered = true;
        for (int i = 2; i <= 7; i++) {
            verdict(sendableMessage(), policy, ResponderGuards.Verdict.BURST_ALREADY_ANSWERED,
                    "message " + i + " of the same burst is not answered again");
        }

        // ...unless the owner switched on replying to every message.
        ResponderGuards.Policy every = sendablePolicy();
        every.burstAlreadyAnswered = true;
        every.replyToEveryMessage = true;
        verdict(sendableMessage(), every, ResponderGuards.Verdict.SEND,
                "reply-to-every-message answers each message in the burst");
        every.lastAnsweredText = MessageCategory.normalise("same thing");
        ResponderGuards.Message same = sendableMessage();
        same.incomingText = "same thing";
        verdict(same, every, ResponderGuards.Verdict.SEND,
                "reply-to-every-message also lifts the same-text guard");
    }

    /**
     * The identical text is not answered twice, but a different text releases it.
     *
     * The owner's rule exactly: "once replied, don't reply to the same text again; it can if
     * another text is coming."
     */
    private static void sameTextIsNotAnsweredTwice() {
        ResponderGuards.Message m = sendableMessage();
        m.incomingText = "are you free this evening";
        ResponderGuards.Policy policy = sendablePolicy();

        // First time: nothing answered yet, so it sends.
        verdict(m, policy, ResponderGuards.Verdict.SEND, "the first time a text is seen it sends");

        // The identical text arrives again, now recorded as last answered.
        policy.lastAnsweredText = MessageCategory.normalise("are you free this evening");
        verdict(m, policy, ResponderGuards.Verdict.SAME_TEXT_ALREADY_ANSWERED,
                "the identical text is not answered a second time");

        // Punctuation and casing do not defeat it.
        ResponderGuards.Message loud = sendableMessage();
        loud.incomingText = "ARE YOU FREE THIS EVENING!!!";
        verdict(loud, policy, ResponderGuards.Verdict.SAME_TEXT_ALREADY_ANSWERED,
                "a resend with different casing and punctuation still counts as the same");

        // A genuinely different message releases it.
        ResponderGuards.Message next = sendableMessage();
        next.incomingText = "actually never mind";
        verdict(next, policy, ResponderGuards.Verdict.SEND,
                "a different message is answered");

        // A fixed name-rule reply is exempt: the owner said to answer this person every time.
        ResponderGuards.Message forced = sendableMessage();
        forced.incomingText = "are you free this evening";
        forced.forceReply = true;
        verdict(forced, policy, ResponderGuards.Verdict.SEND,
                "a fixed reply answers even the identical text");
    }

    /**
     * Family priority skips every rate limit, but none of the safety guards.
     *
     * The ring confirm arrives seconds after the offer, so a cooldown would otherwise swallow the
     * very reply that rings the phone. But a NEVER family contact, the owner's own message, and
     * the phone being in use must still be respected.
     */
    private static void familyPriorityBypassesRateLimitsOnly() {
        // Cooldown, caps, burst, same-text: all skipped under family priority.
        ResponderGuards.Policy limited = sendablePolicy();
        limited.familyPriority = true;
        limited.cooldownMs = 2 * 60 * 60 * 1000L;
        limited.lastReplyToConversationMs = limited.nowMs - 1000;
        limited.burstAlreadyAnswered = true;
        limited.lastAnsweredText = MessageCategory.normalise("ring");
        limited.repliesToContactToday = 999;
        limited.repliesTodayTotal = 999;
        ResponderGuards.Message m = sendableMessage();
        m.incomingText = "ring";
        verdict(m, limited, ResponderGuards.Verdict.SEND,
                "family priority sends through cooldown, caps, burst and same-text");

        // But NEVER still blocks.
        ResponderGuards.Policy never = sendablePolicy();
        never.familyPriority = true;
        never.contactRule = ResponderGuards.ContactRule.NEVER;
        verdict(sendableMessage(), never, ResponderGuards.Verdict.CONTACT_BLOCKED,
                "a NEVER family contact is still refused");

        // The owner's own message still ignored.
        ResponderGuards.Policy own = sendablePolicy();
        own.familyPriority = true;
        ResponderGuards.Message mine = sendableMessage();
        mine.fromOwner = true;
        verdict(mine, own, ResponderGuards.Verdict.OWN_MESSAGE,
                "family priority does not make IRIS answer the owner");

        // Phone in use still defers (no point ringing a phone he's holding).
        ResponderGuards.Policy inUse = sendablePolicy();
        inUse.familyPriority = true;
        inUse.phoneInUse = true;
        verdict(sendableMessage(), inUse, ResponderGuards.Verdict.PHONE_IN_USE,
                "family priority still defers when the phone is in use");
    }

    private static void cooldownBlocksAndThenReleases() {
        ResponderGuards.Policy policy = sendablePolicy();
        policy.lastReplyToConversationMs = policy.nowMs - 60_000L;
        verdict(sendableMessage(), policy, ResponderGuards.Verdict.COOLDOWN,
                "a minute after the last reply is too soon");

        policy.lastReplyToConversationMs = policy.nowMs - policy.cooldownMs;
        verdict(sendableMessage(), policy, ResponderGuards.Verdict.SEND,
                "exactly one cooldown later is allowed");

        policy.lastReplyToConversationMs = policy.nowMs - policy.cooldownMs - 1;
        verdict(sendableMessage(), policy, ResponderGuards.Verdict.SEND,
                "past the cooldown is allowed");

        ResponderGuards.Policy noCooldown = sendablePolicy();
        noCooldown.cooldownMs = 0;
        noCooldown.lastReplyToConversationMs = noCooldown.nowMs - 1;
        verdict(sendableMessage(), noCooldown, ResponderGuards.Verdict.SEND,
                "a zero cooldown disables the check rather than blocking everything");
    }

    private static void capsBlockIncludingZero() {
        ResponderGuards.Policy atContact = sendablePolicy();
        atContact.repliesToContactToday = 3;
        verdict(sendableMessage(), atContact, ResponderGuards.Verdict.CONTACT_CAP,
                "a contact at their cap is refused");

        ResponderGuards.Policy overContact = sendablePolicy();
        overContact.repliesToContactToday = 99;
        verdict(sendableMessage(), overContact, ResponderGuards.Verdict.CONTACT_CAP,
                "past the cap is refused");

        ResponderGuards.Policy zeroCap = sendablePolicy();
        zeroCap.contactCapPerDay = 0;
        verdict(sendableMessage(), zeroCap, ResponderGuards.Verdict.CONTACT_CAP,
                "a cap of zero mutes a contact, which is a legitimate setting");

        ResponderGuards.Policy atDaily = sendablePolicy();
        atDaily.repliesTodayTotal = 30;
        verdict(sendableMessage(), atDaily, ResponderGuards.Verdict.DAILY_CAP,
                "the overall daily cap is enforced");

        ResponderGuards.Policy negativeCount = sendablePolicy();
        negativeCount.repliesToContactToday = -5;
        verdict(sendableMessage(), negativeCount, ResponderGuards.Verdict.SEND,
                "a negative counter is read as zero, not as credit");
    }

    private static void loopDetection() {
        String reply = "Suvra is asleep. I'm IRIS, his personal intelligence.";

        check(ResponderGuards.looksLikeLoop(reply, reply), "an exact echo is a loop");
        check(ResponderGuards.looksLikeLoop("  SUVRA IS ASLEEP. I'M IRIS, HIS PERSONAL "
                        + "INTELLIGENCE.  ", reply),
                "case and padding do not defeat loop detection");
        check(ResponderGuards.looksLikeLoop("auto: " + reply + " (sent by bot)", reply),
                "our reply wrapped by the other end is still a loop");

        check(!ResponderGuards.looksLikeLoop("hello", reply), "an ordinary message is not a loop");
        check(!ResponderGuards.looksLikeLoop("", reply), "empty is not a loop");
        check(!ResponderGuards.looksLikeLoop(reply, ""), "no previous reply means no loop");
        check(!ResponderGuards.looksLikeLoop(null, reply), "null is not a loop");
        check(!ResponderGuards.looksLikeLoop("ok", "ok"),
                "short strings coincide too easily to count as evidence");

        ResponderGuards.Message echo = sendableMessage();
        echo.incomingText = reply;
        echo.lastSentReply = reply;
        verdict(echo, sendablePolicy(), ResponderGuards.Verdict.LOOP_SUSPECTED,
                "two responders talking to each other is stopped");
    }

    /**
     * Where the clock is untrustworthy these guards choose silence, which is the opposite of
     * ListenerWatchdog. There, failing to act loses capture; here, acting wrongly puts a message
     * in someone's chat.
     */
    private static void clockSafetyChoosesSilence() {
        ResponderGuards.Policy noClock = sendablePolicy();
        noClock.nowMs = 0;
        verdict(sendableMessage(), noClock, ResponderGuards.Verdict.DISARMED_EXPIRED,
                "a missing clock reading stands down rather than sending");

        ResponderGuards.Policy backwards = sendablePolicy();
        backwards.lastReplyToConversationMs = backwards.nowMs + 600_000L;
        verdict(sendableMessage(), backwards, ResponderGuards.Verdict.COOLDOWN,
                "a last-reply time in the future is treated as still cooling down");

        ResponderGuards.Policy noArmTime = sendablePolicy();
        noArmTime.armedUntilMs = 0;
        noArmTime.armedIndefinitely = false;
        verdict(sendableMessage(), noArmTime, ResponderGuards.Verdict.DISARMED_EXPIRED,
                "no armed-until time and not indefinite means stood down");
    }

    private static void everyVerdictExplainsItself() {
        for (ResponderGuards.Verdict v : ResponderGuards.Verdict.values()) {
            String text = ResponderGuards.explain(v);
            check(text != null && text.length() > 10,
                    v + " has a real explanation for the decision log");
        }
        check(ResponderGuards.explain(null).length() > 0,
                "a null verdict still explains rather than crashing the log");
        check(ResponderGuards.sends(ResponderGuards.Verdict.SEND), "SEND sends");
        for (ResponderGuards.Verdict v : ResponderGuards.Verdict.values()) {
            if (v == ResponderGuards.Verdict.SEND) continue;
            check(!ResponderGuards.sends(v), v + " does not send");
        }
    }

    private static void nullsFailSafe() {
        check(ResponderGuards.evaluate(null, sendablePolicy())
                        == ResponderGuards.Verdict.NOT_ARMED,
                "a null message refuses rather than throwing");
        check(ResponderGuards.evaluate(sendableMessage(), null)
                        == ResponderGuards.Verdict.NOT_ARMED,
                "a null policy refuses rather than throwing");
        check(ResponderGuards.evaluate(null, null) == ResponderGuards.Verdict.NOT_ARMED,
                "both null refuses rather than throwing");
    }
}
