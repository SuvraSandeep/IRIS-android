package com.iris.assistant;

/**
 * Decides whether the responder may answer one message, and records why not when it may not.
 *
 * Pure, with no Android imports. Every setting and counter arrives as a parameter, so the entire
 * decision is reproducible offline and the explain view can replay it.
 *
 * The verdict is an enum rather than a boolean on purpose. The owner asked for a decision log that
 * shows the silences as well as the sends, and "no" is useless without a reason: a responder that
 * quietly declines looks identical to one that is broken. Every refusal here names itself.
 *
 * Where a judgement could go either way, these guards choose silence. That is the opposite of the
 * rule in ListenerWatchdog, where a confused clock means act -- there, failing to act loses
 * capture; here, acting wrongly sends a stranger a message from Suvra's account. The cost is
 * asymmetric, so the default differs.
 */
public final class ResponderGuards {

    /**
     * How many times a day IRIS will explain itself to one conversation.
     *
     * Raised from one to five after the owner found IRIS went silent the second time he addressed
     * it by name while testing. One acknowledgement was too few: someone who writes "iris?" and
     * then "are you there iris?" is owed an answer both times. Five is enough to be reliably self-
     * aware when named, and still bounded so two auto-responders cannot talk all night \u2014 loop
     * detection is the other backstop. Reply-to-every-message lifts this entirely by the owner's
     * explicit choice.
     */
    public static final int TO_IRIS_PER_DAY = 5;

    /** What the responder is allowed to do at all. */
    public enum State {
        /** Not running. */
        OFF,
        /** Deciding and logging, but physically not sending. */
        DRY_RUN,
        /** Sending. */
        LIVE
    }

    /** What the owner has said about one contact. */
    public enum ContactRule {
        /** Always eligible, subject to the remaining guards. */
        ALLOW,
        /** Never answered, whatever else is true. */
        NEVER,
        /** No instruction; the global scope settings decide. */
        INHERIT
    }

    /** The outcome, and the reason when the answer is no. */
    public enum Verdict {
        /** Everything passed. */
        SEND,
        /** The responder is switched off. */
        NOT_ARMED,
        /** The armed period has elapsed. */
        DISARMED_EXPIRED,
        /** Suvra is using the phone, so he can answer himself. */
        PHONE_IN_USE,
        /** The app offers no reply action, so nothing can be sent regardless. */
        NO_REPLY_ACTION,
        /** The message came from Suvra's own account on another device. */
        OWN_MESSAGE,
        /** A roll-up count, a deletion placeholder, or an already-deleted message. */
        BLOCKED_CONTENT,
        /** A one-time code, verification SMS, or an SMS sender ID / shortcode. Never answered. */
        SMS_SENSITIVE,
        /** The contact is marked NEVER. */
        CONTACT_BLOCKED,
        /** A rule on the contact's NAME refuses them, independently of the contact list. */
        NAME_RULE_BLOCKED,
        /** Allowlist mode is on and this contact is not on it. */
        NOT_ALLOWLISTED,
        /** Group replies are switched off. */
        GROUP_NOT_ENABLED,
        /** One-to-one replies are switched off. */
        PERSONAL_NOT_ENABLED,
        /** A group message that did not name Suvra, while mentions are required. */
        NOT_MENTIONED,
        /** The sender is not in contacts and unsaved senders are not answered. */
        UNSAVED_CONTACT,
        /** This category earns no reply in this tone. */
        CATEGORY_SILENT,
        /** The incoming text looks like our own last reply coming back. */
        LOOP_SUSPECTED,
        /** Something in this burst of messages has already been answered. */
        BURST_ALREADY_ANSWERED,
        /** This exact text was already answered in this conversation; a new text would release it. */
        SAME_TEXT_ALREADY_ANSWERED,
        /** A rapid-fire follow-up within the coalesce window of the last reply; folded into it. */
        DEBOUNCED,
        /** Too many back-to-back banter replies to this conversation; stepping back. */
        BANTER_SNOOZE,
        /** Too soon after the last reply to this conversation. */
        COOLDOWN,
        /** This contact has had their allowance for the day. */
        CONTACT_CAP,
        /** IRIS has already explained itself to this conversation today. */
        TO_IRIS_CAP,
        /** The responder has had its allowance for the day. */
        DAILY_CAP
    }

    /** Facts about the message being considered. */
    public static final class Message {
        public MessageCategory.Kind category = MessageCategory.Kind.NONE;
        public ReplyTone.Tone tone = ReplyTone.Tone.PROFESSIONAL;
        public String incomingText = "";
        /** The last reply IRIS sent to this conversation, for loop detection. */
        public String lastSentReply = "";
        public boolean group;
        public boolean mentionsOwner;
        public boolean savedContact;
        public boolean hasReplyAction;
        public boolean fromOwner;
        /** Roll-up count text such as "14 messages from 3 chats". */
        public boolean rollup;
        /** A "this message was deleted" placeholder. */
        public boolean placeholder;
        /** The archive has already flagged this message as deleted by its sender. */
        public boolean flaggedDeleted;
        /** An SMS one-time code / verification message, or an unsaved SMS sender ID / shortcode. */
        public boolean smsSensitive;
        /**
         * The owner set a fixed reply for this sender by name, so a reply is wanted regardless of
         * what the message says. Bypasses the category-silence guard and nothing else: every
         * safety guard, the cooldown and the caps still apply.
         */
        public boolean forceReply;
    }

    /** Settings and counters. All owner-customisable; none are read from storage here. */
    public static final class Policy {
        public State state = State.OFF;
        public long nowMs;
        /** When the armed period ends. Ignored when armedIndefinitely is true. */
        public long armedUntilMs;
        public boolean armedIndefinitely;
        public boolean phoneInUse;
        /** Answer anyway even when Suvra is using the phone. Owner-set; default defers to him. */
        public boolean replyWhileInUse;

        public boolean replyToGroups;
        public boolean replyToPersonal = true;
        public boolean groupsRequireMention = true;
        public boolean replyToUnsaved;

        public ContactRule contactRule = ContactRule.INHERIT;
        /** A rule on the sender's name refuses them. Set from ResponderSettings.nameRuleBlocks. */
        public boolean nameRuleBlocked;
        public boolean allowlistOnly;

        public long cooldownMs;
        public long lastReplyToConversationMs;
        /** Rapid-fire coalesce window in ms; 0 disables (default), so unset callers are unaffected. */
        public long coalesceWindowMs;
        /** Consecutive banter replies already sent to this conversation; 0 if unused. */
        public int banterCount;
        /** Snooze after this many consecutive banter replies; 0 disables (default). */
        public int banterCap;
        public boolean burstAlreadyAnswered;
        /**
         * Answer every message in a flurry, not one per flurry.
         *
         * Off by default: a single WhatsApp notification can hold seven messages, so without burst
         * suppression IRIS fires seven times. But the owner asked for a switch to turn that off and
         * reply to everything, so when this is set the burst and same-text guards are skipped. The
         * cooldown and caps still apply, which keep it from being truly unbounded.
         */
        public boolean replyToEveryMessage;
        /**
         * The exact incoming text IRIS last answered in this conversation.
         *
         * Compared against the current text, normalised, so the identical message re-arriving as a
         * fresh notification is not answered twice. A genuinely different message releases it,
         * which is the owner's rule: "it can if another text is coming".
         */
        public String lastAnsweredText = "";

        public int repliesToContactToday;
        /** How many times IRIS has already explained itself to this conversation today. */
        public int toIrisRepliesToday;
        public int contactCapPerDay;
        public int repliesTodayTotal;
        public int dailyCapTotal;
        /**
         * A saved family contact in the ring exchange. Bypasses the RATE limits (cooldown, caps,
         * burst, same-text) because the owner gave family full priority with no limits. It does
         * NOT bypass the safety guards: a NEVER family contact is still refused, own messages are
         * still ignored, and the phone being in use still defers.
         */
        public boolean familyPriority;
    }

    private ResponderGuards() { }

    /**
     * Run every guard in order and return the first that refuses.
     *
     * Order is deliberate, because the reported reason should be the most fundamental one. There is
     * no point telling the owner a contact is past their daily cap when the responder is switched
     * off entirely.
     */
    public static Verdict evaluate(Message message, Policy policy) {
        if (message == null || policy == null) return Verdict.NOT_ARMED;

        // ─── Is the responder even running? ───
        if (policy.state == State.OFF) return Verdict.NOT_ARMED;
        if (isExpired(policy)) return Verdict.DISARMED_EXPIRED;

        // Suvra is holding the phone, so he can answer it. Checked early because it overrides
        // every per-message consideration.
        //
        // Overridable, at the owner's explicit request after device testing. The default still
        // defers to him, because a reply arriving while he is mid-conversation is worse than no
        // reply at all -- but "I am at my desk with the screen on all day and nothing is being
        // answered" is a legitimate complaint, and this is the switch for it.
        if (policy.phoneInUse && !policy.replyWhileInUse) return Verdict.PHONE_IN_USE;

        // ─── Can anything be sent at all? ───
        // Many apps offer no reply action. Those are skipped silently rather than reported as a
        // failure, because there is nothing wrong: they simply cannot be answered.
        if (!message.hasReplyAction) return Verdict.NO_REPLY_ACTION;
        if (message.fromOwner) return Verdict.OWN_MESSAGE;

        // ─── Is this even a message? ───
        if (message.rollup || message.placeholder || message.flaggedDeleted) {
            return Verdict.BLOCKED_CONTENT;
        }

        // A one-time code / verification SMS, or an SMS sender ID / shortcode, is never answered.
        // Placed early, as a hard safety that no scope or setting can override.
        if (message.smsSensitive) return Verdict.SMS_SENSITIVE;

        // ─── Has the owner excluded this contact? ───
        // NEVER outranks everything that follows, including mentions and urgency. If the owner has
        // said no, no amount of urgency in the message overrides it.
        if (policy.contactRule == ContactRule.NEVER) return Verdict.CONTACT_BLOCKED;
        // A name rule refuses too, but only where the contact list has said nothing. An explicit
        // ALLOW on this conversation is a decision about this person and outranks a pattern that
        // happens to match their name -- otherwise an "eric" rule would permanently silence a
        // relative called Erica whom the owner had deliberately allowed.
        if (policy.nameRuleBlocked && policy.contactRule != ContactRule.ALLOW) {
            return Verdict.NAME_RULE_BLOCKED;
        }
        if (policy.allowlistOnly && policy.contactRule != ContactRule.ALLOW) {
            return Verdict.NOT_ALLOWLISTED;
        }

        // ─── Scope ───
        if (message.group) {
            if (!policy.replyToGroups) return Verdict.GROUP_NOT_ENABLED;
            if (policy.groupsRequireMention && !message.mentionsOwner) {
                return Verdict.NOT_MENTIONED;
            }
        } else {
            if (!policy.replyToPersonal) return Verdict.PERSONAL_NOT_ENABLED;
        }

        // An unknown number is as likely to be a courier or a spammer as a person, and answering
        // tells a stranger the phone is live and unattended.
        if (!message.savedContact && !policy.replyToUnsaved) return Verdict.UNSAVED_CONTACT;

        // ─── Does this category speak in this tone? ───
        // A fixed reply set by name is the owner overriding this specific question: he has said
        // what to send to this person, so "that kind of message earns no reply" no longer applies.
        // Reply-to-every-message is the owner overriding it wholesale: answer everything, even an
        // unclassified message (which the composer then acknowledges generically). Every other
        // guard above and below still does apply.
        if (!message.forceReply
                && !policy.replyToEveryMessage
                && !ReplyTone.answers(message.category, message.tone)) {
            return Verdict.CATEGORY_SILENT;
        }

        // ─── Rate limiting ───
        // Family in the ring exchange bypasses every rate limit below: the owner gave family full
        // priority with no limits, and the ring confirm arrives seconds after the offer, so a
        // cooldown would otherwise swallow the very reply that rings the phone.
        if (policy.familyPriority) return Verdict.SEND;

        // Rapid-fire coalescing. If a reply went to this conversation within the coalesce window,
        // treat this message as part of the same burst and stay quiet, so three quick bubbles
        // don't earn three back-to-back introductions. Applies even to the TO_IRIS cooldown
        // exemption below (that's exactly where the double-intro came from), but never when the
        // owner has asked to answer every message. Disabled when the window is 0 (unset callers).
        if (!policy.replyToEveryMessage
                && Debounce.withinWindow(policy.lastReplyToConversationMs, policy.nowMs,
                        policy.coalesceWindowMs)) {
            return Verdict.DEBOUNCED;
        }

        // Auto-snooze: after enough consecutive banter replies, step back so IRIS doesn't flood
        // the chat with a novel while the owner is away. Only banter categories count; urgent,
        // mentions, reminders, a forced reply, and reply-to-every are never snoozed.
        if (!policy.replyToEveryMessage
                && policy.banterCap > 0
                && policy.banterCount >= policy.banterCap
                && !message.forceReply
                && isBanter(message.category)) {
            return Verdict.BANTER_SNOOZE;
        }

        if (looksLikeLoop(message.incomingText, message.lastSentReply)) {
            return Verdict.LOOP_SUSPECTED;
        }
        // Reply-to-every-message: the owner's explicit "answer every message, not one per burst".
        // It skips the burst, same-text, cooldown and cap guards below. Placed AFTER the safety
        // and scope guards and after loop detection, so it still won't answer two bots echoing
        // each other, a blocked or unsaved contact, or a group \u2014 groups are governed by the
        // scope switch and are deliberately left to the normal burst limiting even here, so one
        // chatty group can't be answered line by line.
        if (policy.replyToEveryMessage && !message.group) return Verdict.SEND;
        // One reply per burst. A single notification held seven messages from one chat on the
        // owner's own phone, so without this the responder would have fired seven times. The owner
        // can switch this off to answer every message.
        if (!policy.replyToEveryMessage && policy.burstAlreadyAnswered) {
            return Verdict.BURST_ALREADY_ANSWERED;
        }
        // The identical text, already answered in this conversation. The owner's rule exactly:
        // don't answer the same message twice, but a different message releases it. Checked after
        // the burst guard and before the cooldown, because it is a more specific "already handled"
        // than either. Normalised so punctuation and casing don't defeat it. A forced reply (a
        // fixed name-rule reply) is exempt: the owner has said to answer this person every time.
        // Reply-to-every-message also lifts it, by the same intent.
        if (!message.forceReply
                && !policy.replyToEveryMessage
                && !message.incomingText.isEmpty()
                && sameText(message.incomingText, policy.lastAnsweredText)) {
            return Verdict.SAME_TEXT_ALREADY_ANSWERED;
        }
        // Someone talking directly to IRIS gets one acknowledgement even inside the cooldown,
        // because the cooldown exists to stop IRIS pestering a conversation and this is the one
        // case where the other person has asked IRIS a direct question. Bounded hard at one per
        // conversation per day: without that bound, two auto-responders or a persistent human
        // could keep the exchange going indefinitely.
        boolean cooldownExempt = message.category == MessageCategory.Kind.TO_IRIS
                && Math.max(0, policy.toIrisRepliesToday) < TO_IRIS_PER_DAY;
        if (!cooldownExempt && withinCooldown(policy)) return Verdict.COOLDOWN;
        if (message.category == MessageCategory.Kind.TO_IRIS
                && Math.max(0, policy.toIrisRepliesToday) >= TO_IRIS_PER_DAY) {
            return Verdict.TO_IRIS_CAP;
        }
        if (atContactCap(policy)) return Verdict.CONTACT_CAP;
        if (atDailyCap(policy)) return Verdict.DAILY_CAP;

        return Verdict.SEND;
    }

    /** Whether a verdict means a reply should actually be produced. */
    public static boolean sends(Verdict verdict) {
        return verdict == Verdict.SEND;
    }

    /**
     * Whether the armed period has elapsed.
     *
     * A non-positive clock is treated as expired. Silence is the safe direction here: a wrong
     * refusal costs one unsent reply, while a wrong send puts a message in someone's chat.
     */
    public static boolean isExpired(Policy policy) {
        if (policy.armedIndefinitely) return false;
        if (policy.nowMs <= 0) return true;
        if (policy.armedUntilMs <= 0) return true;
        return policy.nowMs >= policy.armedUntilMs;
    }

    /**
     * Whether the last reply to this conversation was too recent.
     *
     * A clock that moved backwards produces a negative gap, which is treated as still inside the
     * cooldown rather than outside it, again choosing silence.
     */
    public static boolean withinCooldown(Policy policy) {
        if (policy.cooldownMs <= 0) return false;
        if (policy.lastReplyToConversationMs <= 0) return false;
        long elapsed = policy.nowMs - policy.lastReplyToConversationMs;
        if (elapsed < 0) return true;
        return elapsed < policy.cooldownMs;
    }

    /**
     * Whether this contact has had their allowance.
     *
     * A cap of zero blocks, which is a legitimate way to mute one contact without removing their
     * settings. A negative count is read as zero rather than as credit.
     */
    public static boolean atContactCap(Policy policy) {
        if (policy.contactCapPerDay <= 0) return true;
        return Math.max(0, policy.repliesToContactToday) >= policy.contactCapPerDay;
    }

    /**
     * Banter categories eligible for the auto-snooze. Deliberately excludes URGENT, MENTION,
     * REMINDER, FESTIVAL and BIRTHDAY so important or time-bound messages always get through.
     */
    static boolean isBanter(MessageCategory.Kind kind) {
        if (kind == null) return false;
        switch (kind) {
            case GREETING:
            case TIME_GREETING:
            case SHORT:
            case OTHER:
            case CHECK_IN:
            case QUESTION:
            case TO_IRIS:
            case ACKNOWLEDGEMENT:
            case THANKS:
            case NONE:
                return true;
            default:
                return false;
        }
    }

    /** Whether the responder has had its allowance for the day. */
    public static boolean atDailyCap(Policy policy) {
        if (policy.dailyCapTotal <= 0) return true;
        return Math.max(0, policy.repliesTodayTotal) >= policy.dailyCapTotal;
    }

    /**
     * Whether the incoming message looks like our own reply coming back.
     *
     * Two auto-responders will otherwise talk to each other until a cap stops them. Compared after
     * normalisation so punctuation and casing do not defeat it, and containment is checked in both
     * directions because the other end may quote or wrap what it received.
     */
    public static boolean looksLikeLoop(String incomingText, String lastSentReply) {
        if (incomingText == null || lastSentReply == null) return false;
        String incoming = MessageCategory.normalise(incomingText);
        String sent = MessageCategory.normalise(lastSentReply);
        // Both strings must be substantial. A short incoming like "hi" is a substring of almost
        // any reply (our replies contain "his", "this", etc.), so without this a double-texted
        // "Hi" was wrongly read as our own reply echoing back and silenced \u2014 even with
        // reply-to-every-message on. A genuine loop is a long reply coming back near-verbatim.
        if (incoming.length() < 12 || sent.length() < 12) return false;
        return incoming.contains(sent) || sent.contains(incoming);
    }

    /**
     * Whether two incoming texts are the same message for the purpose of not answering twice.
     *
     * Normalised, so a resend with different punctuation or casing still counts as the same. An
     * empty previous text means nothing has been answered yet, so nothing matches.
     */
    public static boolean sameText(String current, String lastAnswered) {
        if (current == null || lastAnswered == null) return false;
        String a = MessageCategory.normalise(current);
        String b = MessageCategory.normalise(lastAnswered);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b);
    }

    /** Plain-language reason, for the decision log and the explain view. */
    public static String explain(Verdict verdict) {
        if (verdict == null) return "No decision was recorded.";
        switch (verdict) {
            case SEND: return "I answered this one.";
            case NOT_ARMED: return "I was switched off.";
            case DISARMED_EXPIRED:
                return "My armed period had run out, so I stood down on my own.";
            case PHONE_IN_USE:
                return "You were using the phone, so you could answer it better than I could.";
            case NO_REPLY_ACTION:
                return "That app gives me no way to reply, so there was nothing I could do.";
            case OWN_MESSAGE: return "That was you, on another device. I don't answer you.";
            case BLOCKED_CONTENT:
                return "That wasn't really a message \u2014 a notification count, a deleted-message "
                        + "placeholder, or something already taken back.";
            case SMS_SENSITIVE:
                return "A one-time code, a verification SMS, or an SMS sender ID / shortcode \u2014 "
                        + "I never answer those.";
            case CONTACT_BLOCKED: return "You told me never to answer this contact.";
            case NAME_RULE_BLOCKED:
                return "Their name matches one of your rules that says don't reply.";
            case NOT_ALLOWLISTED:
                return "I'm only answering your chosen contacts, and this one isn't on the list.";
            case GROUP_NOT_ENABLED: return "A group, and I'm not answering groups.";
            case PERSONAL_NOT_ENABLED:
                return "A one-to-one chat, and I'm not answering those at the moment.";
            case NOT_MENTIONED:
                return "A group message that didn't name you, so I stayed out of it.";
            case UNSAVED_CONTACT:
                return "Not in your contacts. I don't tell strangers your phone is unattended.";
            case CATEGORY_SILENT:
                return "Nothing worth answering in that, at least not in this tone.";
            case LOOP_SUSPECTED:
                return "That looked like my own reply coming back. I'm not doing this all night.";
            case BURST_ALREADY_ANSWERED:
                return "I'd already answered that burst. One reply per flurry, not one per message.";
            case SAME_TEXT_ALREADY_ANSWERED:
                return "I already answered that exact message. I'll reply again when they say "
                        + "something new.";
            case COOLDOWN: return "Too soon after my last reply to them.";
            case DEBOUNCED:
                return "A rapid-fire follow-up seconds after my reply \u2014 folded into that one "
                        + "rather than firing again.";
            case BANTER_SNOOZE:
                return "Too many back-to-back banter replies \u2014 stepping back so I don't flood "
                        + "the chat. 'urgent' still gets through.";
            case CONTACT_CAP: return "They've had their replies for today.";
            case TO_IRIS_CAP:
                return "I've already introduced myself to them once today. Twice is a "
                        + "conversation, and I'm not equipped for one.";
            case DAILY_CAP: return "I've had my replies for today.";
            default: return verdict.name();
        }
    }
}
