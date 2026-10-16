package com.iris.assistant;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Assembles the facts, asks the rules, sends the answer.
 *
 * Sending happens ONLY when the state is LIVE. In DRY_RUN the identical decision is reached and
 * logged and nothing leaves the phone, which is what makes the dry run worth running: the log
 * shows exactly what live would have done.
 *
 * All judgement lives in the pure classes -- MessageCategory, ReplyTone, ResponderWindow,
 * ResponderGuards, ReplyTemplates -- which are covered offline. This class only gathers Android
 * facts, converts the clock once, and reports.
 */
public final class AutoResponder {

    /**
     * Notification keys already answered in this process.
     *
     * A second layer under the cooldown. Messaging apps re-post the same notification as messages
     * accumulate, so one conversation can arrive three or four times in as many seconds; the
     * cooldown stops the repeats being answered, and this stops them even being considered.
     * Bounded, because a long-running listener would otherwise accumulate keys indefinitely.
     */
    private static final Set<String> ANSWERED_KEYS = new LinkedHashSet<>();
    private static final int ANSWERED_MAX = 200;

    /** Step back after this many consecutive banter replies to one conversation. */
    private static final int BANTER_SNOOZE_CAP = 10;

    private AutoResponder() { }

    /** Facts the listener already has, passed in so this class does no parsing of its own. */
    public static final class Incoming {
        public StatusBarNotification sbn;
        public String pkg = "";
        public String appLabel = "";
        /** Sender of the newest message in the notification. */
        public String sender = "";
        /** Text of the newest message. Used for classification and never stored by the log. */
        public String body = "";
        public boolean group;
        /** The text is a roll-up count such as "14 messages from 3 chats". */
        public boolean rollup;
        /** The text is a "this message was deleted" placeholder. */
        public boolean placeholder;
        public boolean fromOwner;
    }

    /**
     * Consider one notification and record what would happen.
     *
     * Called once per notification rather than once per message. The newest message is the one
     * worth answering, and considering each of seven messages separately would mean seven
     * decisions in the log for one conversation.
     */
    public static void consider(Context context, Incoming incoming) {
        if (context == null || incoming == null || incoming.sbn == null) return;

        ResponderSettings settings = new ResponderSettings(context);
        ResponderGuards.State state = settings.state();
        // Off means off. No classification, no log entry, no work on the notification path.
        if (state == ResponderGuards.State.OFF) return;

        // The responder has its own app list, separate from the archive's. Ticking an app for
        // archiving must never silently authorise outgoing messages through it.
        if (!settings.allowsApp(incoming.pkg)) return;

        // Re-posted identical notification? WhatsApp re-adds the same notification while a message
        // stays unread, which used to clear the in-process answered set and draw a second reply.
        // This persisted key+body fingerprint blocks that across removals and restarts, unless the
        // owner has asked to reply to every message.
        long nowEarly = System.currentTimeMillis();
        String keyEarly = incoming.sbn.getKey();
        if (!settings.replyToEveryMessage()
                && settings.answeredRecently(keyEarly, incoming.body, nowEarly)) {
            return;
        }

        long now = System.currentTimeMillis();
        int minuteOfDay = minuteOfDay(now);

        String resolvedSender = ContactResolver.display(context, incoming.sender);
        // Shared builder, so the contacts screen and the responder can never disagree about
        // which key a rule was stored under.
        String conversationKey = ResponderSettings.conversationKey(incoming.pkg, resolvedSender);

        // Never process IRIS's own reply coming back as a notification. In a group, WhatsApp
        // re-surfaces the latest message -- which may be the line IRIS just sent -- and because
        // that line contains "IRIS" it would otherwise be re-classified as TO_IRIS and considered
        // again (visible as the "You (group)" entries in the decision log). Match it against the
        // reply we last sent to this conversation and drop it before any work.
        if (isOwnEcho(context, incoming.pkg, resolvedSender, incoming.body)) return;

        // Group Chat Silence Rule: In group chats (like You (group) in Comms Deck),
        // IRIS should never auto-respond unless someone explicitly tags @Suvra or IRIS.
        if (incoming.group && !GroupMention.isExplicitlyAddressed(
                incoming.body, ReplyTemplates.OWNER, settings.mentionKeywords(),
                settings.mentionHandles(), settings.mentionNumbers())) {
            return;
        }

        // ─── Root-Level Interceptor for Deleted Messages ───
        // A deletion placeholder from a messaging app ("This message was deleted") is a system
        // notification update, NOT human conversational intent. Intercept it here at the root level,
        // completely bypassing category classification, modes, and bedtime/quiet hours so IRIS never
        // declares "Past Suvra's bedtime... your message is safe with me" to an erased message!
        String rawBody = incoming.body == null ? "" : incoming.body.trim();
        if (DeletedMessageRules.isOwnDeletion(rawBody)) {
            return;
        }
        if (DeletedMessageRules.isSenderDeletion(rawBody)) {
            boolean hasReplyAction = IrisNotificationListener.replyAction(incoming.sbn) != null;
            ResponderGuards.ContactRule contactRule =
                    settings.effectiveContactRule(conversationKey, resolvedSender);
            boolean savedContact = ContactResolver.isSaved(context, incoming.sender);

            ResponderLog.Entry delEntry = new ResponderLog.Entry();
            delEntry.whenMs = now;
            delEntry.appLabel = incoming.appLabel;
            delEntry.pkg = incoming.pkg;
            delEntry.sender = resolvedSender;
            delEntry.group = incoming.group;
            delEntry.category = "DELETED";
            delEntry.tone = settings.defaultTone().name();
            delEntry.incoming = rawBody;
            delEntry.dryRun = (state == ResponderGuards.State.DRY_RUN);

            if (!hasReplyAction) {
                delEntry.verdict = ResponderGuards.Verdict.NO_REPLY_ACTION.name();
                delEntry.reason = "Root deletion interceptor: Notification has no direct reply action";
                new ResponderLog(context).record(delEntry);
                return;
            }
            if (contactRule == ResponderGuards.ContactRule.NEVER) {
                delEntry.verdict = ResponderGuards.Verdict.CONTACT_BLOCKED.name();
                delEntry.reason = "Root deletion interceptor: Contact rule is NEVER for " + resolvedSender;
                new ResponderLog(context).record(delEntry);
                return;
            }
            if (!savedContact && !settings.replyToUnsaved()) {
                delEntry.verdict = ResponderGuards.Verdict.UNSAVED_CONTACT.name();
                delEntry.reason = "Root deletion interceptor: Unsaved contact and reply to unsaved is disabled";
                new ResponderLog(context).record(delEntry);
                return;
            }

            int rot = settings.nextRotation();
            String privacyReply = SmartReplies.poolReply("deleted", rot);
            if (privacyReply.isEmpty()) {
                privacyReply = "I can never access those deleted messages; privacy matters the most. I'm IRIS, "
                        + ReplyTemplates.OWNER + "'s assistant.";
            }

            String lastSent = lastReplyText(context, incoming.pkg, resolvedSender);
            if (!lastSent.isEmpty()
                    && MessageCategory.normalise(privacyReply).equals(MessageCategory.normalise(lastSent))) {
                privacyReply = SmartReplies.poolReply("deleted", rot + 1);
                if (privacyReply.isEmpty() || MessageCategory.normalise(privacyReply).equals(MessageCategory.normalise(lastSent))) {
                    privacyReply = "Whatever was deleted stays deleted \u2014 " + ReplyTemplates.OWNER
                            + " wired me to respect privacy. I'm IRIS, his assistant.";
                }
            }

            delEntry.verdict = "SEND";
            delEntry.reply = privacyReply;
            delEntry.reason = "Root deletion interceptor: Sender revoked message -> direct privacy reply (bypassed sleep/bedtime)";

            if (state == ResponderGuards.State.LIVE) {
                NotificationReply.Result sendRes = NotificationReply.send(
                        context, incoming.sbn, privacyReply, "auto_reply", false);
                delEntry.sendResult = sendRes.name();
                settings.recordReply(conversationKey, now);
                rememberAnswered(incoming.sbn.getKey());
            }

            new ResponderLog(context).record(delEntry);
            return; // CRITICAL: Stop execution here so it NEVER hits Bedtime, modes, or category composer!
        }

        // ─── Tone first, because it decides whether some categories speak at all ───
        ReplyTone.Relation contactRel = settings.effectiveContactRelation(conversationKey, resolvedSender);
        ReplyTone.Tone relTone = settings.relationTone(contactRel);
        ReplyTone.Tone tone = ReplyTone.resolve(
                settings.contactTone(conversationKey),
                relTone,
                settings.toneForName(resolvedSender),
                settings.appTone(incoming.pkg),
                ResponderWindow.toneFor(settings.toneWindows(), minuteOfDay),
                settings.defaultTone());

        // Never answer a distressed or angry message with sarcasm. A lexicon read of the message
        // softens only the one tone that would be hurtful here; it changes nothing else and never
        // causes a send. Everything above (category, urgency, quiet hours) is unaffected.
        if (tone == ReplyTone.Tone.SARCASTIC
                && SentimentScore.score(incoming.body).shouldSoften()) {
            tone = ReplyTone.Tone.PROFESSIONAL;
        }

        // ─── Category ───
        // Expand SMS shorthand first ("r u there" -> "are you there") so the classifier and the
        // matchers see full words. Numbers are untouched, so group-mention matching still uses raw.
        String normBody = Shorthand.expand(incoming.body);
        MessageCategory.Result category = MessageCategory.classify(
                normBody, settings.mentionKeywords(),
                !incoming.group && settings.irisSpokeLast(conversationKey, now));
        // Keep a short rolling history of this chat (customizable depth) for multi-bubble context.
        settings.recordIncomingHistory(conversationKey, normBody, now);

        // In a group, an @-handle or phone-number mention of the owner counts as being named,
        // even though it isn't his saved name. Checked on the raw body (not normalised, which
        // would mangle an underscored handle), and it does not override a message already aimed
        // at IRIS itself.
        if (incoming.group
                && category.kind != MessageCategory.Kind.TO_IRIS
                && GroupMention.isMentioned(incoming.body, settings.mentionHandles(),
                        settings.mentionNumbers())) {
            category = new MessageCategory.Result(MessageCategory.Kind.MENTION, "");
        }

        // Implicit urgency: a panicked message that never says "urgent" (shouting, "!!!",
        // distress words, or a frantic burst) is promoted to URGENT -- but only from the generic
        // buckets, so a reminder, a greeting or a direct IRIS question keeps its own handling.
        int burst = settings.recordIncomingAndCountBurst(
                conversationKey, now, UrgencyScore.BURST_WINDOW_MS);
        if (SmartReplies.handlesCategory(category.kind)
                && category.kind != MessageCategory.Kind.TO_IRIS
                && UrgencyScore.isUrgent(incoming.body, burst)) {
            category = new MessageCategory.Result(MessageCategory.Kind.URGENT, "");
        }

        // ─── Family ring ───
        // A saved family contact gets full priority: an urgent message is offered a word to make
        // the phone ring, and replying that word rings it. Computed before everything else so it
        // can take precedence, and not tied to any mode or to quiet hours \u2014 the owner's rule.
        boolean savedForRing = ContactResolver.isSaved(context, incoming.sender);
        boolean isFamily = savedForRing
                && settings.effectiveContactRelation(conversationKey, resolvedSender)
                        == ReplyTone.Relation.FAMILY;
        String pendingWord = settings.pendingRingWord(conversationKey);
        boolean confirmsRing = isFamily && !pendingWord.isEmpty()
                && messageIsWord(incoming.body, pendingWord);
        // An explicit "where are you IRIS / ring the phone / find my phone" from family rings at
        // once \u2014 the phrase IS the deliberate request, so it needs no second confirm word. Still
        // gated to saved family and the master switch.
        boolean ringRequest = isFamily && settings.familyRingEnabled()
                && isRingRequest(incoming.body);
        boolean urgentFamily = isFamily && category.kind == MessageCategory.Kind.URGENT
                && !incoming.group;

        // A fixed reply set for this sender by name. When present it is the whole reply, and the
        // owner has thereby said a reply is wanted whatever the message, so the category-silence
        // guard is skipped -- but only when there is real content, never for an empty body.
        String nameReply = settings.nameRuleReply(resolvedSender);

        // A reply the owner TAUGHT IRIS from the decision log for this sender (and optionally this
        // category). It is an explicit instruction, so it outranks the name rule, smart replies
        // and templates. Specific sender+category beats a sender-wide one.
        String learnedReply = new LearnedReplyStore(context)
                .lookup(resolvedSender, category.kind.name());

        // If IRIS previously asked this sender for a reminder time, THIS message is the answer,
        // whatever it classified as -- a bare "at 5pm" is not a reminder phrase and would
        // otherwise be silenced before the reminder path could read it. Route it through, and
        // force it past the category-silence guard.
        boolean awaitingReminderTime =
                !new ReminderStore(context).awaitingTask(conversationKey).isEmpty();

        // Multi-turn follow-up: "actually make it 6" / "cancel that" right after a reminder was
        // set in this conversation. Only recognised while a recent reminder exists, so an
        // ordinary "cancel that" to a shop is never read as cancelling an IRIS reminder.
        long recentReminderId = new ReminderStore(context).lastReminderId(conversationKey, now);
        ConversationContext.FollowUp followUp =
                ConversationContext.classify(incoming.body, recentReminderId > 0);

        // Two-turn offer: if IRIS last asked "shall I flag this urgent?" in this conversation,
        // THIS message may be the yes/no answer. Classified only while that offer is pending.
        ConversationContext.Offer pendingOffer = settings.pendingOffer(conversationKey, now);
        ConversationContext.OfferReply offerReply =
                ConversationContext.classifyOffer(incoming.body, pendingOffer);

        // Smart keyword replies: an additive layer for messages the classifier left generic
        // (someone talking to IRIS, a bare question, an unremarkable or unmatched message, or a
        // link). It fires ONLY for those generic buckets, so greetings, thanks, festivals, urgent
        // messages and reminders keep their existing wording untouched.
        // The owner's own name is understood like a pronoun for the generic matchers, so "is
        // Suvra free", "where is Suvra" and "Suvra's email" hit the same rules written for "he".
        String aliasedBody = OwnerAlias.toThirdPerson(normBody, ReplyTemplates.OWNER);

        String smartReply = SmartReplies.matchMulti(aliasedBody, tone, settings.nextRotation());
        boolean smartMatched = !smartReply.isEmpty()
                && SmartReplies.handlesCategory(category.kind);

        // Multi-bubble context: ONLY when the current message is a short fragment (a split-off
        // continuation like "tomorrow" or "6pm") that matched nothing on its own. A full,
        // standalone message must never be answered via the window, or IRIS re-answers a question
        // from an earlier bubble -- the cause of surprising replies. Gated hard to <=3 words.
        int currentWords = normBody.trim().isEmpty() ? 0 : normBody.trim().split("\\s+").length;
        if (!smartMatched
                && currentWords > 0 && currentWords <= 3
                && settings.contextDepth() > 1
                && SmartReplies.handlesCategory(category.kind)) {
            java.util.List<String> hist = ChatHistory.window(
                    settings.chatHistory(conversationKey, now), settings.contextDepth());
            if (hist.size() > 1) {
                String combined = ChatHistory.combine(hist);
                // Multi-bubble context should never stitch previous URLs unless the current message
                // itself carries a URL; otherwise a short follow-up or bare word like "Amazon"
                // erroneously re-triggers the earlier bubble's link/reel classifier.
                if (!LinkCategory.hasUrl(combined) || LinkCategory.hasUrl(normBody)) {
                    String windowBody = OwnerAlias.toThirdPerson(
                            combined, ReplyTemplates.OWNER);
                    String windowReply = SmartReplies.matchMulti(windowBody, tone);
                    if (!windowReply.isEmpty()) {
                        smartReply = windowReply;
                        smartMatched = true;
                    }
                }
            }
        }

        // Hinglish/Odia layer: recognise the owner's real vernacular traffic instead of silence.
        // Same gating as smart replies (generic buckets only), and whole-phrase matched so the
        // real-Odia must-not-match corpus stays silent.
        String vernReply = Vernacular.match(aliasedBody, tone);
        boolean vernMatched = !vernReply.isEmpty()
                && SmartReplies.handlesCategory(category.kind);

        // Opt-in profile sharing: answer a few harmless "about Suvra" questions (role/company/
        // city) ONLY from facts he put under "share" in iris-me.json. Nothing is disclosed unless
        // he opted in, and no question maps to a phone/address/email, so PII can never leak.
        String profileReply = ProfileShare.reply(
                ProfileShare.fieldFor(aliasedBody), ShareProfile.fields(context),
                ReplyTemplates.OWNER);
        boolean profileMatched = !profileReply.isEmpty()
                && SmartReplies.handlesCategory(category.kind);

        // ─── Guards ───
        ResponderGuards.Message message = new ResponderGuards.Message();
        message.category = category.kind;
        message.tone = tone;
        message.incomingText = incoming.body;
        message.lastSentReply = lastReplyText(context, incoming.pkg, resolvedSender);
        message.group = incoming.group;
        message.mentionsOwner = GroupMention.isExplicitlyAddressed(
                incoming.body, ReplyTemplates.OWNER, settings.mentionKeywords(),
                settings.mentionHandles(), settings.mentionNumbers());
        // Positive verification, not an inference from the sender's shape. An SMS shortcode such
        // as VM-HDFCBK contains letters, so "not a phone number" must never be read as "therefore
        // a saved contact".
        message.savedContact = ContactResolver.isSaved(context, incoming.sender);
        // SMS one-time codes / verification messages and unsaved SMS sender-IDs are never answered.
        message.smsSensitive = SmsSafety.blocksAutoReply(
                MessagingApp.fromPackage(incoming.pkg), incoming.sender, incoming.body,
                message.savedContact);
        message.hasReplyAction = IrisNotificationListener.replyAction(incoming.sbn) != null;
        message.fromOwner = incoming.fromOwner;
        message.rollup = incoming.rollup;
        message.placeholder = incoming.placeholder;
        // A sender's own deletion placeholder is answered (privacy/mock line) rather than silenced;
        // the owner's own deletions and generic placeholders stay silent.
        message.senderDeletion = DeletedMessageRules.isSenderDeletion(incoming.body);
        message.flaggedDeleted = false;
        message.forceReply = ((!nameReply.isEmpty() || !learnedReply.isEmpty()
                        || awaitingReminderTime
                        || confirmsRing || urgentFamily || ringRequest)
                && category.kind != MessageCategory.Kind.NONE)
                || smartMatched
                || vernMatched
                || profileMatched
                || followUp != ConversationContext.FollowUp.NONE
                || offerReply != ConversationContext.OfferReply.NONE
                || !learnedReply.isEmpty();

        ResponderGuards.Policy policy = new ResponderGuards.Policy();
        policy.state = state;
        policy.nowMs = now;
        policy.armedUntilMs = settings.armedUntilMs();
        policy.armedIndefinitely = settings.armedIndefinitely();
        policy.phoneInUse = phoneInUse(context);
        policy.replyWhileInUse = settings.replyWhileInUse();
        policy.replyToGroups = settings.replyToGroups();
        policy.replyToPersonal = settings.replyToPersonal();
        policy.groupsRequireMention = settings.groupsRequireMention();
        policy.replyToUnsaved = settings.replyToUnsaved();
        policy.contactRule = settings.effectiveContactRule(conversationKey, resolvedSender);
        policy.nameRuleBlocked = settings.nameRuleBlocks(resolvedSender);
        policy.allowlistOnly = settings.allowlistOnly();
        policy.cooldownMs = settings.cooldownMs();
        policy.lastReplyToConversationMs = settings.lastReplyTo(conversationKey);
        policy.coalesceWindowMs = Debounce.DEFAULT_WINDOW_MS;
        policy.banterCount = settings.banterCount(conversationKey);
        policy.banterCap = BANTER_SNOOZE_CAP;
        policy.burstAlreadyAnswered = alreadyAnswered(incoming.sbn.getKey());
        policy.replyToEveryMessage = settings.replyToEveryMessage();
        policy.lastAnsweredText = settings.lastAnsweredText(conversationKey);
        policy.familyPriority = confirmsRing || urgentFamily || ringRequest;
        policy.repliesToContactToday = settings.repliesToContactToday(conversationKey, now);
        policy.toIrisRepliesToday = settings.toIrisRepliesToday(conversationKey, now);
        policy.contactCapPerDay = settings.contactCapPerDay();
        policy.repliesTodayTotal = settings.repliesToday(now);
        policy.dailyCapTotal = settings.dailyCapTotal();

        ResponderGuards.Verdict verdict = ResponderGuards.evaluate(message, policy);

        // An owner override on the category is applied after the guards, so the log still reports
        // the more fundamental reason when there is one. A fixed reply set by name is the owner
        // overriding exactly this, so it is not re-silenced here.
        if (verdict == ResponderGuards.Verdict.SEND
                && !message.forceReply
                && !settings.replyToEveryMessage()
                && !settings.categoryEnabled(category.kind, tone)) {
            verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
        }

        String reply = "";
        String decisionReason = "";
        if (ResponderGuards.sends(verdict)) {
            if (confirmsRing || urgentFamily || ringRequest) {
                // Family ring takes precedence over every other interpretation of the message.
                reply = handleFamilyRing(context, settings, conversationKey, resolvedSender,
                        confirmsRing || ringRequest, state == ResponderGuards.State.LIVE);
                decisionReason = "Family priority: Emergency ring triggered for saved family contact";
            } else if (offerReply != ConversationContext.OfferReply.NONE) {
                // The sender is answering IRIS's "shall I flag this urgent?" offer. Consume it.
                if (state == ResponderGuards.State.LIVE) settings.clearPendingOffer(conversationKey);
                if (offerReply == ConversationContext.OfferReply.ACCEPT) {
                    // Actually flag it: post an owner-facing heads-up so this is no longer an empty
                    // promise. Only in LIVE (preview must not notify). Best-effort; the reply still
                    // sends even if the alert cannot be posted.
                    if (state == ResponderGuards.State.LIVE) {
                        ArchiveAlerts.urgentFlag(context, incoming.appLabel, resolvedSender, now);
                    }
                    reply = "Done \u2014 I've flagged this as high-priority for " + ReplyTemplates.OWNER
                            + ". I'm IRIS, his assistant.";
                    decisionReason = "Escalation accepted: sender requested urgent flag for " + ReplyTemplates.OWNER;
                } else {
                    reply = "Understood \u2014 I won't disturb " + ReplyTemplates.OWNER
                            + " with an urgent alert. Your message is saved for when he's free. I'm "
                            + "IRIS, his assistant.";
                    decisionReason = "Escalation declined: sender opted not to disturb " + ReplyTemplates.OWNER;
                }
            } else if (followUp != ConversationContext.FollowUp.NONE) {
                // A follow-up edits or cancels the reminder just set in this conversation. Checked
                // before the reminder branch, because "cancel that reminder" also classifies as a
                // reminder but must be treated as a cancel, not a new one.
                reply = handleFollowUp(context, incoming, conversationKey, resolvedSender, now,
                        minuteOfDay, followUp, recentReminderId,
                        state == ResponderGuards.State.LIVE);
                decisionReason = "Follow-up action: " + followUp.name() + " on recent reminder";
            } else if (category.kind == MessageCategory.Kind.REMINDER || awaitingReminderTime) {
                // Reminders are their own path: parse, (in LIVE) store and schedule, and reply to
                // the sender confirming or asking for a time. The scope check is separate from the
                // reply scope, because "who may set an alarm on my phone" is a stricter question
                // than "who may get an auto-reply".
                if (!settings.reminderAllowed(message.savedContact,
                        settings.effectiveContactRelation(conversationKey, resolvedSender))) {
                    verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
                    decisionReason = "Reminder withheld: sender not authorized to schedule alarms";
                } else {
                    reply = handleReminder(context, settings, incoming, conversationKey,
                            resolvedSender, now, minuteOfDay,
                            state == ResponderGuards.State.LIVE);
                    decisionReason = "Reminder: parsed and scheduled confirmation";
                }
            } else {
                // A learned reply (owner-taught) is the highest-precedence wording; then a fixed
                // name-rule reply; then a smart keyword match; otherwise the category composer.
                if (!learnedReply.isEmpty()) {
                    reply = ReplyTemplates.fill(learnedReply, resolvedSender,
                            ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Learned reply: owner-taught response for " + resolvedSender + " (" + category.kind.name() + ")";
                } else if (!nameReply.isEmpty()) {
                    reply = ReplyTemplates.fill(nameReply, resolvedSender,
                            ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Name rule: fixed reply assigned for " + resolvedSender;
                } else if (smartMatched) {
                    reply = ReplyTemplates.fill(smartReply, resolvedSender,
                            ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Smart reply: rule matched keyword intent in category " + category.kind.name();
                } else if (vernMatched) {
                    reply = ReplyTemplates.fill(vernReply, resolvedSender,
                            ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Vernacular: Odia/Hinglish phrase matched";
                } else if (profileMatched) {
                    reply = ReplyTemplates.fill(profileReply, resolvedSender,
                            ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Profile share: shared safe bio detail from iris-me.json";
                } else if (category.kind == MessageCategory.Kind.MEDIA_ONLY
                        && !MediaType.reply(MediaType.classify(null, incoming.body), tone)
                                .isEmpty()) {
                    // Media gets a witty, per-type reply (voice note / photo / sticker / doc /
                    // contact / location) when the owner has media replies enabled.
                    reply = ReplyTemplates.fill(
                            MediaType.reply(MediaType.classify(null, incoming.body), tone),
                            resolvedSender, ResponderWindow.formatMinute(minuteOfDay),
                            ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now));
                    decisionReason = "Media acknowledgement: per-type reply for media attachment";
                } else {
                    reply = compose(settings, category, tone, resolvedSender, now, minuteOfDay);
                    decisionReason = "Category composer: template generated for " + category.kind.name()
                            + " (tone: " + tone.name() + ")";
                }
                // Once IRIS has already introduced itself in this conversation (or the sender is
                // clearly addressing IRIS by name), drop the "I'm IRIS, Suvra's assistant" intro
                // so a continuing chat stops repeating itself. Owner-defined wording is untouched.
                if (learnedReply.isEmpty() && nameReply.isEmpty()) {
                    boolean introduced = settings.lastReplyTo(conversationKey) > 0
                            || IntroTrim.senderKnowsIris(incoming.body);
                    reply = IntroTrim.trim(reply, introduced, ReplyTemplates.OWNER);
                }
                // Hard outgoing dedup: never send the identical reply we last sent to this
                // conversation (e.g. "Aita" after a link re-firing the link intro, or a repeated
                // direct-address line). Owner-defined/learned wording is exempt.
                if (learnedReply.isEmpty() && nameReply.isEmpty() && !reply.isEmpty()) {
                    String lastSent = lastReplyText(context, incoming.pkg, resolvedSender);
                    if (!lastSent.isEmpty()
                            && MessageCategory.normalise(reply)
                                    .equals(MessageCategory.normalise(lastSent))) {
                        reply = "Caught that too \u2014 I'm IRIS, " + ReplyTemplates.OWNER
                                + "'s assistant, keeping it all together for him.";
                        decisionReason += " (Outgoing dedup applied: avoiding repeat of previous reply)";
                    }
                }
            }
            // A category with no text left to send is a silence, not an empty message.
            if (reply.isEmpty()) {
                verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
                decisionReason = "Silence: category composer produced empty reply for " + category.kind.name();
            }
        } else {
            decisionReason = "Guard refusal: " + ResponderGuards.explain(verdict);
        }

        ResponderLog.Entry entry = new ResponderLog.Entry();
        entry.whenMs = now;
        entry.appLabel = incoming.appLabel;
        entry.pkg = incoming.pkg;
        entry.sender = resolvedSender;
        entry.incoming = incoming.body;
        entry.group = incoming.group;
        entry.category = category.kind.name();
        entry.tone = tone.name();
        entry.verdict = verdict.name();
        entry.reply = reply;
        entry.dryRun = state == ResponderGuards.State.DRY_RUN;
        entry.reason = decisionReason;

        if (ResponderGuards.sends(verdict)) {
            // Counters are advanced in BOTH states, deliberately.
            //
            // A dry run that did not consume the cooldown and the caps would be a dishonest
            // simulation: the seven-message burst on the owner's own phone would appear in the log
            // as seven replies, when live it would be one reply and six refusals. Recording here
            // makes the log show what live would actually do. The only cost is that going live on
            // the same day starts with some allowance already spent, which errs toward fewer
            // messages and is therefore the safe direction.
            settings.recordReply(conversationKey, now);
            // Track consecutive banter replies for the auto-snooze: bump for a banter category,
            // reset the run for anything substantive (urgent, mention, reminder, offer answer).
            if (state == ResponderGuards.State.LIVE) {
                if (ResponderGuards.isBanter(category.kind)
                        && offerReply == ConversationContext.OfferReply.NONE) {
                    settings.bumpBanterCount(conversationKey);
                } else {
                    settings.resetBanterCount(conversationKey);
                }
            }
            // Arm or clear the escalation offer: if this reply answered an availability question,
            // the next message may be a yes/no about flagging it urgent. If we just consumed an
            // offer answer, it was already cleared above.
            if (offerReply == ConversationContext.OfferReply.NONE) {
                if (ConversationContext.armsEscalationOffer(incoming.body)) {
                    settings.setPendingOffer(conversationKey,
                            ConversationContext.Offer.OFFERED_ESCALATION, now);
                } else if (pendingOffer != ConversationContext.Offer.NONE) {
                    // The sender moved on without answering; drop the stale offer.
                    settings.clearPendingOffer(conversationKey);
                }
            }
            // Remember exactly what was answered, so the identical message re-arriving is not
            // answered again, and mark that IRIS has now introduced itself to this thread so the
            // next reply can be short.
            settings.recordAnsweredText(conversationKey, incoming.body);
            settings.markIntroduced(conversationKey);
            // Persisted fingerprint, so the same notification re-posted after removal or a restart
            // is not answered a second time.
            settings.recordAnsweredFingerprint(incoming.sbn.getKey(), incoming.body, now);
            // Counted separately, because this is the one category allowed past the cooldown and
            // its own bound is the only thing stopping an exchange.
            if (category.kind == MessageCategory.Kind.TO_IRIS) {
                settings.recordIrisIntroduction(conversationKey, now);
            }
            rememberAnswered(incoming.sbn.getKey());

            if (state == ResponderGuards.State.LIVE) {
                // requireSameNotification=false: the responder answers the CONVERSATION, and a
                // messaging app re-posting its notification between classification and send is
                // routine. The key still has to match, which is what keeps the reply in the right
                // conversation.
                NotificationReply.Result result = NotificationReply.send(
                        context, incoming.sbn, reply, "auto_reply", false);
                entry.sendResult = result.name();
                if (!NotificationReply.sent(result)) {
                    // The decision stands but the send did not happen, and the log must say so
                    // rather than claiming a message was delivered.
                    //
                    // The reply text is deliberately LEFT ALONE here. Overwriting it with the
                    // failure message is what made the log read "SENT: the notification changed",
                    // because the display had nothing but that field to go on. The outcome now
                    // lives in its own field and the two are rendered separately.
                    entry.verdict = ResponderGuards.Verdict.SEND.name();
                    try (ActionLedger ledger = new ActionLedger(context)) {
                        ledger.record("auto_reply", ActionLedger.FAILED,
                                "Auto-reply to " + incoming.pkg + " not sent: " + result.name(),
                                "", "", false);
                    } catch (Throwable ignored) { }
                } else if (!settings.keepUnread()) {
                    // The owner opted out of keeping messages unread: best-effort trigger the app's
                    // own "mark as read" action. By default this does NOT run, so the message stays
                    // unread and visible, which is what the owner asked for.
                    tryMarkRead(incoming.sbn);
                }
            }
        }

        new ResponderLog(context).record(entry);
    }

    /**
     * Build the reply text.
     *
     * The reply ALWAYS reflects the message's own category (or the active mode). An earlier
     * version collapsed every follow-up in a thread to one generic "still IRIS here" line once
     * IRIS had introduced itself, which is why the owner saw every message answered the same way
     * regardless of what it was \u2014 a festival wish, a thank-you and a question all got the same
     * reply. That shortcut is gone: the "What I answer" categories now drive the wording every
     * time, at the cost of IRIS naming itself in full on each reply, which is the safer default
     * anyway since every recipient must know it is not Suvra typing.
     *
     * Quiet hours are evaluated here rather than inside the guards because they change what is
     * said, not whether anything is said. The single exception is urgency, which selects a
     * different quiet-hours variant rather than escaping quiet hours.
     */
    private static String compose(ResponderSettings settings, MessageCategory.Result category,
                                  ReplyTone.Tone tone, String senderName, long nowMs,
                                  int minuteOfDay) {
        // A greeting that doesn't match the clock -- "good morning" sent at night -- gets an aware
        // reply rather than echoing the wrong part of day. Only clear conflicts trigger this.
        if (category.kind == MessageCategory.Kind.TIME_GREETING && !category.detail.isEmpty()) {
            String actualPart = ResponderWindow.partOfDay(minuteOfDay);
            if (TimeGreeting.mistimed(category.detail, actualPart)) {
                return ReplyTemplates.fill(
                        TimeGreeting.reply(category.detail, actualPart, tone), senderName,
                        ResponderWindow.formatMinute(minuteOfDay), actualPart, -1, dayName(nowMs));
            }
        }
        // An active mode overrides both the time window and the category: the owner has stated the
        // situation outright, and that beats whatever the hour would otherwise imply. SLEEPING is
        // handled inside modeVariants by reusing the quiet-hours wording, so the hours-to-wake
        // placeholder still needs filling for it.
        ResponderMode mode = settings.mode();
        // Self-activating modes: when the owner hasn't set one by hand, let the enabled signals
        // decide. Only auto-sleep (quiet hours) needs no extra permission and is wired here;
        // driving/meeting signals require device permissions and are added separately.
        if (mode == ResponderMode.NONE) {
            AutoModePolicy.Signals sig = new AutoModePolicy.Signals();
            sig.autoSleep = settings.autoSleepInQuietHours();
            sig.quietHours = ResponderWindow.isQuiet(
                    settings.quietStart(), settings.quietEnd(), minuteOfDay);
            mode = AutoModePolicy.resolve(ResponderMode.NONE, AutoModePolicy.Source.NONE, sig).mode;
        }
        boolean quiet;
        List<String> variants;
        if (category.kind == MessageCategory.Kind.TO_IRIS) {
            // Someone is addressing IRIS directly. It must answer AS itself, in full, regardless
            // of quiet hours or an active mode \u2014 "who are you?" at 2am deserves "I'm IRIS,
            // Suvra's assistant", not "Suvra is asleep". This is the one category that always
            // speaks for itself, which is also why the owner kept finding it "not self-aware":
            // at night or in a mode it was being answered with the blanket reply instead.
            quiet = false;
            variants = settings.variantsFor(MessageCategory.Kind.TO_IRIS, tone, false);
        } else if (mode.isActive()) {
            variants = settings.modeVariantsFor(mode, category.kind, tone);
            quiet = mode == ResponderMode.SLEEPING;
        } else {
            quiet = ResponderWindow.isQuiet(
                    settings.quietStart(), settings.quietEnd(), minuteOfDay);
            variants = settings.variantsFor(category.kind, tone, quiet);
        }
        // Reply-to-every-message must never fall silent. If the category itself has nothing to
        // say \u2014 an unclassified message, or one the owner left switched off \u2014 acknowledge
        // receipt generically rather than dropping the message he asked to have answered.
        if (variants.isEmpty() && settings.replyToEveryMessage()) {
            variants = settings.variantsFor(MessageCategory.Kind.OTHER, tone, quiet);
        }
        if (variants.isEmpty()) return "";

        String chosen = ReplyTemplates.choose(variants, settings.nextRotation());

        // Optional time/day flavour ("It's Friday evening\u2026"), opt-in and only on the generic
        // template path, so witty category replies are untouched. Prepended once.
        if (settings.timeFlavorEnabled() && category.kind != MessageCategory.Kind.TO_IRIS) {
            java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getDefault());
            cal.setTimeInMillis(nowMs);
            String flavour = TimeFlavor.of(cal.get(java.util.Calendar.DAY_OF_WEEK),
                    cal.get(java.util.Calendar.HOUR_OF_DAY));
            if (!flavour.isEmpty()) chosen = flavour + " " + chosen;
        }

        int hoursToWake = quiet
                ? ResponderWindow.hoursUntil(settings.quietEnd(), minuteOfDay)
                : -1;
        // The part of day comes from the message's own greeting when it had one, so "good morning"
        // is answered as morning even if it arrives slightly late.
        String partOfDay = category.detail.isEmpty()
                ? ResponderWindow.partOfDay(minuteOfDay) : category.detail;

        return ReplyTemplates.fill(chosen, senderName,
                ResponderWindow.formatMinute(minuteOfDay), partOfDay, hoursToWake, dayName(nowMs));
    }

    /**
     * Handle the family-ring exchange and return the reply.
     *
     * Two states. If this message is the confirm word, ring the phone (in LIVE) and tell the
     * sender it's ringing. Otherwise it's an urgent message from family: if the capability is on,
     * tell them the exact word to reply; if the owner has switched it off, tell them to call
     * directly, which is the agreed fallback.
     *
     * The confirm word is a fixed, explicitly-stated "RING" rather than a secret, because the
     * owner wanted the sender told plainly what to do. The safety is not in the word being hard to
     * guess \u2014 it is that only a SAVED FAMILY contact reaches this at all, and only a second,
     * deliberate message rings.
     */
    private static String handleFamilyRing(Context context, ResponderSettings settings,
                                           String conversationKey, String senderName,
                                           boolean confirmsRing, boolean live) {
        String owner = ReplyTemplates.OWNER;
        if (confirmsRing) {
            if (live) {
                settings.clearPendingRingWord(conversationKey);
                RingActivity.ring(context, senderName);
            }
            return "Ringing his phone now. I'm IRIS, " + owner + "'s assistant \u2014 "
                    + "if he can reach it, he will.";
        }

        // Urgent family message.
        if (!settings.familyRingEnabled()) {
            return "He's not reachable here and I can't ring his phone right now. If it's truly "
                    + "urgent, call him directly. I'm IRIS, " + owner + "'s assistant.";
        }
        if (live) settings.setPendingRingWord(conversationKey, RING_WORD);
        return "If this genuinely can't wait, reply " + RING_WORD + " and I'll make " + owner
                + "'s phone ring loudly, even if it's on silent. I'm IRIS, his assistant.";
    }

    /** The word a family member replies to trigger the ring. Stated to them explicitly. */
    private static final String RING_WORD = "RING";

    /** Whether a message is, in substance, just the given word (ignoring case and punctuation). */
    private static boolean messageIsWord(String body, String word) {
        if (body == null || word == null) return false;
        String a = MessageCategory.normalise(body);
        String b = MessageCategory.normalise(word);
        return !b.isEmpty() && a.equals(b);
    }

    /**
     * An explicit request to ring the phone \u2014 find-my-phone by text.
     *
     * "where are you iris", "ring the phone", "find my phone", "make your phone ring". Phrase-
     * matched on the normalised body. Only acted on for a saved family contact, so it is not a
     * ring button anyone can press.
     */
    private static boolean isRingRequest(String body) {
        if (body == null) return false;
        String flat = " " + MessageCategory.normalise(body) + " ";
        String[] phrases = {
                "where are you iris", "where is the phone", "where is your phone",
                "ring the phone", "ring your phone", "ring my phone", "ring his phone",
                "find my phone", "find the phone", "make your phone ring", "make the phone ring",
                "sound the alarm iris", "iris ring"};
        for (String p : phrases) {
            if (flat.contains(" " + p + " ")) return true;
        }
        return false;
    }

    /**
     * Handle a reminder request end to end and return the reply to the sender.
     *
     * The flow the owner asked for: parse the task and time; if there's a time, store and schedule
     * it and confirm; if there's no time, ask the sender for one and remember the task against
     * this conversation, so their next message supplies it; and if that next message still has no
     * parseable time, store it undated so it is at least visible in the app.
     *
     * Side effects (storing, scheduling) happen only when live. In dry run the reply is composed
     * exactly as it would be, but nothing is written or scheduled, matching how the send itself is
     * withheld.
     */
    private static String handleReminder(Context context, ResponderSettings settings,
                                         Incoming incoming, String conversationKey,
                                         String senderName, long now, int minuteOfDay,
                                         boolean live) {
        ReminderStore store = new ReminderStore(context);

        // If IRIS already asked this sender for a time, read THIS message as the time for the
        // task it remembered, rather than as a fresh reminder.
        String awaiting = store.awaitingTask(conversationKey);
        boolean answeringTimeQuestion = !awaiting.isEmpty();

        ReminderParser.Result parsed = ReminderParser.parse(incoming.body);
        String task = answeringTimeQuestion ? awaiting
                : (parsed.task.isEmpty() ? incoming.body.trim() : parsed.task);

        java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getDefault());
        cal.setTimeInMillis(now);
        int nowWeekday = cal.get(java.util.Calendar.DAY_OF_WEEK);
        int nowDay = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int nowMonth = cal.get(java.util.Calendar.MONTH) + 1;
        int nowYear = cal.get(java.util.Calendar.YEAR);
        int delay = ReminderParser.delayMinutes(parsed, minuteOfDay, nowWeekday,
                nowDay, nowMonth, nowYear);

        // When IRIS asked for a time and this reply still parses to one, use it against the
        // remembered task even if the task text itself came back empty.
        if (answeringTimeQuestion && !parsed.task.isEmpty() && delay <= 0) {
            // The reply was treated as a fresh task with no time; re-read it purely as a time.
            delay = ReminderParser.delayMinutes(
                    ReminderParser.parse("remind me " + incoming.body),
                    minuteOfDay, nowWeekday, nowDay, nowMonth, nowYear);
        }

        if (delay > 0) {
            long dueMs = now + delay * 60_000L;
            // Stored and scheduled in BOTH dry run and live, deliberately. A reminder is a note
            // for Suvra, not a message to the sender; dry run withholds the outgoing reply (via
            // the normal send gating) but should not stop IRIS actually helping him, which is also
            // why reminders appeared "not to work" when testing in dry run.
            long id = store.add(task, senderName, dueMs, now);
            ReminderStore.schedule(context, id,
                    "You asked me to remind " + ReplyTemplates.OWNER + ": " + task, dueMs);
            store.clearAwaiting(conversationKey);
            store.setLastReminder(conversationKey, id, now);
            return "Noted \u2014 I'm IRIS, " + ReplyTemplates.OWNER + "'s assistant. I'll remind him "
                    + "to " + task + " " + whenPhrase(dueMs, now) + ".";
        }

        // No time. If this was already the answer to a time question, give up gracefully and store
        // it undated rather than asking in a loop.
        if (answeringTimeQuestion) {
            store.add(task, senderName, 0, now);
            store.clearAwaiting(conversationKey);
            return "I still couldn't make out a date or time, so I've saved it for "
                    + ReplyTemplates.OWNER + " to see: \u201C" + task + "\u201D. You can resend "
                    + "with something like \u201Ctomorrow 5pm\u201D or \u201C25/12 6pm\u201D. I'm "
                    + "IRIS, his assistant.";
        }

        // First time with no time: ask, stating the formats I understand so the reply is usable.
        store.setAwaitingTime(conversationKey, task);
        return "I'll remind " + ReplyTemplates.OWNER + " to " + task + " \u2014 when should I? You "
                + "can say a time like \u201Cin 2 hours\u201D, \u201Ctomorrow 5pm\u201D, "
                + "\u201CMonday 9am\u201D, or a date like \u201C25/12 6pm\u201D. I'm IRIS, his "
                + "assistant.";
    }

    /**
     * Edit or cancel the reminder just set in this conversation. Scheduling side effects happen
     * only when live, matching the reminder path; the reply is composed in both states.
     */
    private static String handleFollowUp(Context context, Incoming incoming, String conversationKey,
                                         String senderName, long now, int minuteOfDay,
                                         ConversationContext.FollowUp followUp, long lastId,
                                         boolean live) {
        ReminderStore store = new ReminderStore(context);
        String owner = ReplyTemplates.OWNER;

        if (followUp == ConversationContext.FollowUp.CANCEL_REMINDER) {
            if (live) {
                ReminderStore.cancel(context, lastId);
                store.remove(lastId);
            }
            store.clearLastReminder(conversationKey);
            return "Done \u2014 I've cancelled that reminder for " + owner + ". I'm IRIS, his "
                    + "assistant.";
        }

        // EDIT_REMINDER_TIME: re-read the new time and reschedule, keeping the original task.
        String task = store.taskOf(lastId);
        ReminderParser.Result parsed = ConversationContext.editTime(incoming.body);
        java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getDefault());
        cal.setTimeInMillis(now);
        int delay = ReminderParser.delayMinutes(parsed, minuteOfDay,
                cal.get(java.util.Calendar.DAY_OF_WEEK),
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.YEAR));
        if (delay <= 0 || task.isEmpty()) {
            // Shouldn't happen (the classifier required a time), but never silently mis-edit.
            return "I couldn't make out the new time, so I've left the reminder as it was. I'm "
                    + "IRIS, " + owner + "'s assistant.";
        }
        long dueMs = now + delay * 60_000L;
        if (live) {
            ReminderStore.cancel(context, lastId);
            store.remove(lastId);
            long id = store.add(task, senderName, dueMs, now);
            ReminderStore.schedule(context, id, "You asked me to remind " + owner + ": " + task,
                    dueMs);
            store.setLastReminder(conversationKey, id, now);
        }
        return "Updated \u2014 I'll remind " + owner + " to " + task + " " + whenPhrase(dueMs, now)
                + " instead. I'm IRIS, his assistant.";
    }

    /** A human phrase for when a reminder is due, e.g. "in about 2 hours" or "tomorrow". */
    private static String whenPhrase(long dueMs, long now) {
        long mins = Math.max(1, (dueMs - now) / 60_000L);
        if (mins < 90) return "in about " + mins + (mins == 1 ? " minute" : " minutes");
        long hours = mins / 60;
        if (hours < 20) return "in about " + hours + (hours == 1 ? " hour" : " hours");
        long days = Math.round(hours / 24.0);
        if (days <= 1) return "tomorrow";
        return "in about " + days + " days";
    }

    private static int weekday(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.DAY_OF_WEEK);
    }

    /** Local minutes since midnight. The only place the clock is converted. */
    private static int minuteOfDay(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    private static String dayName(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(millis);
        String[] names = {"", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
                "Friday", "Saturday"};
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        return day >= 1 && day < names.length ? names[day] : "";
    }

    /**
     * Whether Suvra is using the phone right now.
     *
     * Unknown state reports NOT in use, so a failure here cannot silently disable the responder.
     * This is the one place the uncertainty points toward acting, because the cost is a reply that
     * was not strictly needed rather than a wrong reply.
     */
    private static boolean phoneInUse(Context context) {
        try {
            android.os.PowerManager power =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) context.getSystemService(
                            Context.KEYGUARD_SERVICE);
            if (power == null) return false;
            boolean awake = power.isInteractive();
            boolean locked = keyguard != null && keyguard.isKeyguardLocked();
            return awake && !locked;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether this incoming text is actually a reply IRIS recently sent to this conversation,
     * echoed back by the app (common in groups). Compared on the normalised text against the
     * recent decision log for the same pkg and sender, so it's independent of how the sender is
     * labelled ("You", a name, blank) and cannot falsely substring match.
     */
    private static boolean isOwnEcho(Context context, String pkg, String resolvedSender, String body) {
        if (body == null || body.trim().isEmpty()) return false;
        String want = MessageCategory.normalise(body);
        if (want.isEmpty()) return false;
        int scanned = 0;
        for (ResponderLog.Entry entry : new ResponderLog(context).newestFirst()) {
            if (++scanned > 20) break;                 // only recent history
            if (entry.reply == null || entry.reply.isEmpty()) continue;
            if (entry.sender == null || entry.sender.isEmpty()) continue;
            if (entry.pkg != null && !entry.pkg.equals(pkg)) continue;
            if (!entry.sender.equals(resolvedSender)) continue;
            if (MessageCategory.normalise(entry.reply).equals(want)) return true;
        }
        return false;
    }

    private static String lastReplyText(Context context, String pkg, String resolvedSender) {
        for (ResponderLog.Entry entry : new ResponderLog(context).newestFirst()) {
            if (entry.reply == null || entry.reply.isEmpty()) continue;
            if (entry.sender == null || entry.sender.isEmpty()) continue;
            if (entry.pkg != null && !entry.pkg.equals(pkg)) continue;
            if (entry.sender.equals(resolvedSender)) {
                return entry.reply;
            }
        }
        return "";
    }

    private static synchronized boolean alreadyAnswered(String notifKey) {
        return notifKey != null && ANSWERED_KEYS.contains(notifKey);
    }

    private static synchronized void rememberAnswered(String notifKey) {
        if (notifKey == null) return;
        ANSWERED_KEYS.add(notifKey);
        while (ANSWERED_KEYS.size() > ANSWERED_MAX) {
            ANSWERED_KEYS.remove(ANSWERED_KEYS.iterator().next());
        }
    }

    /** Forget a notification once it is gone, so a later conversation can be answered again. */
    public static synchronized void forget(String notifKey) {
        if (notifKey != null) ANSWERED_KEYS.remove(notifKey);
    }

    /**
     * Best-effort: fire the app's own "mark as read" action, used only when the owner has turned
     * OFF "keep messages unread". App-dependent and silently a no-op when no such plain action
     * exists; a reply action (one carrying a RemoteInput) is never fired here.
     */
    private static void tryMarkRead(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            android.app.Notification n = sbn.getNotification();
            if (n == null || n.actions == null) return;
            for (android.app.Notification.Action a : n.actions) {
                if (a == null || a.title == null || a.actionIntent == null) continue;
                if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                String t = a.title.toString().toLowerCase(java.util.Locale.ROOT);
                if (t.contains("mark as read") || t.contains("mark read")) {
                    a.actionIntent.send();
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Marking read is a convenience; never let it affect the reply decision or crash.
        }
    }
}
