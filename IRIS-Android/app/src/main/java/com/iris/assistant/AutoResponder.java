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

        // ─── Tone first, because it decides whether some categories speak at all ───
        ReplyTone.Tone tone = ReplyTone.resolve(
                settings.contactTone(conversationKey),
                settings.effectiveContactRelation(conversationKey, resolvedSender),
                settings.toneForName(resolvedSender),
                ResponderWindow.toneFor(settings.toneWindows(), minuteOfDay),
                settings.defaultTone());

        // ─── Category ───
        MessageCategory.Result category = MessageCategory.classify(
                incoming.body, settings.mentionKeywords(),
                settings.irisSpokeLast(conversationKey, now));

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

        // If IRIS previously asked this sender for a reminder time, THIS message is the answer,
        // whatever it classified as -- a bare "at 5pm" is not a reminder phrase and would
        // otherwise be silenced before the reminder path could read it. Route it through, and
        // force it past the category-silence guard.
        boolean awaitingReminderTime =
                !new ReminderStore(context).awaitingTask(conversationKey).isEmpty();

        // ─── Guards ───
        ResponderGuards.Message message = new ResponderGuards.Message();
        message.category = category.kind;
        message.tone = tone;
        message.incomingText = incoming.body;
        message.lastSentReply = lastReplyText(context, conversationKey);
        message.group = incoming.group;
        message.mentionsOwner = category.kind == MessageCategory.Kind.MENTION;
        // Positive verification, not an inference from the sender's shape. An SMS shortcode such
        // as VM-HDFCBK contains letters, so "not a phone number" must never be read as "therefore
        // a saved contact".
        message.savedContact = ContactResolver.isSaved(context, incoming.sender);
        message.hasReplyAction = IrisNotificationListener.replyAction(incoming.sbn) != null;
        message.fromOwner = incoming.fromOwner;
        message.rollup = incoming.rollup;
        message.placeholder = incoming.placeholder;
        message.flaggedDeleted = false;
        message.forceReply = (!nameReply.isEmpty() || awaitingReminderTime
                        || confirmsRing || urgentFamily || ringRequest)
                && category.kind != MessageCategory.Kind.NONE;

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
        if (ResponderGuards.sends(verdict)) {
            if (confirmsRing || urgentFamily || ringRequest) {
                // Family ring takes precedence over every other interpretation of the message.
                reply = handleFamilyRing(context, settings, conversationKey, resolvedSender,
                        confirmsRing || ringRequest, state == ResponderGuards.State.LIVE);
            } else if (category.kind == MessageCategory.Kind.REMINDER || awaitingReminderTime) {
                // Reminders are their own path: parse, (in LIVE) store and schedule, and reply to
                // the sender confirming or asking for a time. The scope check is separate from the
                // reply scope, because "who may set an alarm on my phone" is a stricter question
                // than "who may get an auto-reply".
                if (!settings.reminderAllowed(message.savedContact,
                        settings.effectiveContactRelation(conversationKey, resolvedSender))) {
                    verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
                } else {
                    reply = handleReminder(context, settings, incoming, conversationKey,
                            resolvedSender, now, minuteOfDay,
                            state == ResponderGuards.State.LIVE);
                }
            } else {
                // A fixed reply set by name is the whole reply: no classification, no template, no
                // placeholders beyond the standard fill so {name} still works if the owner used it.
                reply = message.forceReply
                        ? ReplyTemplates.fill(nameReply, resolvedSender,
                                ResponderWindow.formatMinute(minuteOfDay),
                                ResponderWindow.partOfDay(minuteOfDay), -1, dayName(now))
                        : compose(settings, category, tone, resolvedSender, now, minuteOfDay);
            }
            // A category with no text left to send is a silence, not an empty message.
            if (reply.isEmpty()) verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
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
        // An active mode overrides both the time window and the category: the owner has stated the
        // situation outright, and that beats whatever the hour would otherwise imply. SLEEPING is
        // handled inside modeVariants by reusing the quiet-hours wording, so the hours-to-wake
        // placeholder still needs filling for it.
        ResponderMode mode = settings.mode();
        boolean quiet;
        List<String> variants;
        if (mode.isActive()) {
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

        int nowWeekday = weekday(now);
        int delay = ReminderParser.delayMinutes(parsed, minuteOfDay, nowWeekday);

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
            return "Noted \u2014 I'm IRIS, " + ReplyTemplates.OWNER + "'s assistant. I'll remind him "
                    + "to " + task + " " + whenPhrase(dueMs, now) + ".";
        }

        // No time. If this was already the answer to a time question, give up gracefully and store
        // it undated rather than asking in a loop.
        if (answeringTimeQuestion) {
            store.add(task, senderName, 0, now);
            store.clearAwaiting(conversationKey);
            return "I couldn't make out a time, so I've saved it for " + ReplyTemplates.OWNER
                    + " to see: \u201C" + task + "\u201D. I'm IRIS, his assistant.";
        }

        // First time with no time: ask.
        store.setAwaitingTime(conversationKey, task);
        return "I'll remind " + ReplyTemplates.OWNER + " to " + task + " \u2014 when would you "
                + "like him reminded? I'm IRIS, his assistant.";
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

    /** The last reply sent to this conversation, for loop detection. */
    private static String lastReplyText(Context context, String conversationKey) {
        for (ResponderLog.Entry entry : new ResponderLog(context).newestFirst()) {
            if (entry.reply.isEmpty()) continue;
            if (conversationKey.contains(entry.sender) && !entry.sender.isEmpty()) {
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
}
