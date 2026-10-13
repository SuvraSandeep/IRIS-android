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

        long now = System.currentTimeMillis();
        int minuteOfDay = minuteOfDay(now);

        String resolvedSender = ContactResolver.display(context, incoming.sender);
        // Shared builder, so the contacts screen and the responder can never disagree about
        // which key a rule was stored under.
        String conversationKey = ResponderSettings.conversationKey(incoming.pkg, resolvedSender);

        // ─── Tone first, because it decides whether some categories speak at all ───
        ReplyTone.Tone tone = ReplyTone.resolve(
                settings.contactTone(conversationKey),
                settings.contactRelation(conversationKey),
                settings.toneForName(resolvedSender),
                ResponderWindow.toneFor(settings.toneWindows(), minuteOfDay),
                settings.defaultTone());

        // ─── Category ───
        MessageCategory.Result category =
                MessageCategory.classify(incoming.body, settings.mentionKeywords());

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

        ResponderGuards.Policy policy = new ResponderGuards.Policy();
        policy.state = state;
        policy.nowMs = now;
        policy.armedUntilMs = settings.armedUntilMs();
        policy.armedIndefinitely = settings.armedIndefinitely();
        policy.phoneInUse = phoneInUse(context);
        policy.replyToGroups = settings.replyToGroups();
        policy.replyToPersonal = settings.replyToPersonal();
        policy.groupsRequireMention = settings.groupsRequireMention();
        policy.replyToUnsaved = settings.replyToUnsaved();
        policy.contactRule = settings.contactRule(conversationKey);
        policy.allowlistOnly = settings.allowlistOnly();
        policy.cooldownMs = settings.cooldownMs();
        policy.lastReplyToConversationMs = settings.lastReplyTo(conversationKey);
        policy.burstAlreadyAnswered = alreadyAnswered(incoming.sbn.getKey());
        policy.repliesToContactToday = settings.repliesToContactToday(conversationKey, now);
        policy.contactCapPerDay = settings.contactCapPerDay();
        policy.repliesTodayTotal = settings.repliesToday(now);
        policy.dailyCapTotal = settings.dailyCapTotal();

        ResponderGuards.Verdict verdict = ResponderGuards.evaluate(message, policy);

        // An owner override on the category is applied after the guards, so the log still reports
        // the more fundamental reason when there is one.
        if (verdict == ResponderGuards.Verdict.SEND
                && !settings.categoryEnabled(category.kind, tone)) {
            verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
        }

        String reply = "";
        if (ResponderGuards.sends(verdict)) {
            reply = compose(settings, category, tone, resolvedSender, now, minuteOfDay);
            // A category with no text left to send is a silence, not an empty message.
            if (reply.isEmpty()) verdict = ResponderGuards.Verdict.CATEGORY_SILENT;
        }

        ResponderLog.Entry entry = new ResponderLog.Entry();
        entry.whenMs = now;
        entry.appLabel = incoming.appLabel;
        entry.sender = resolvedSender;
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
            rememberAnswered(incoming.sbn.getKey());

            if (state == ResponderGuards.State.LIVE) {
                NotificationReply.Result result = NotificationReply.send(
                        context, incoming.sbn, reply, "auto_reply");
                if (!NotificationReply.sent(result)) {
                    // The decision stands but the send did not happen, and the log must say so
                    // rather than claiming a message was delivered.
                    entry.verdict = "SEND_FAILED";
                    entry.reply = NotificationReply.explain(result);
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
     * Quiet hours are evaluated here rather than inside the guards because they change what is
     * said, not whether anything is said. The single exception is urgency, which selects a
     * different quiet-hours variant rather than escaping quiet hours.
     */
    private static String compose(ResponderSettings settings, MessageCategory.Result category,
                                  ReplyTone.Tone tone, String senderName, long nowMs,
                                  int minuteOfDay) {
        boolean quiet = ResponderWindow.isQuiet(
                settings.quietStart(), settings.quietEnd(), minuteOfDay);

        List<String> variants = settings.variantsFor(category.kind, tone, quiet);
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
