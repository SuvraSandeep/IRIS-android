package com.iris.assistant;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Captures posted notifications so IRIS can read them back by voice.
 * Requires the user to grant "Notification access" in system settings
 * (Settings → Apps → Special access → Notification access).
 *
 * Filters out ongoing/system-noise notifications (foreground services,
 * media transport, IRIS's own listening notification).
 *
 * TWO INDEPENDENT CONSUMERS, deliberately kept separate:
 *  1. {@link NotificationStore} -- the original rolling 100-item store that backs voice read-out,
 *     notification summaries and proactive suggestions. Its behaviour is unchanged.
 *  2. {@link NotificationArchive} -- the append-only archive that can flag messages a sender later
 *     deleted. Opt-outable, limited to apps the owner ticked, and additive: if archiving fails or
 *     is switched off, path 1 behaves exactly as it always has.
 * Archive work is wrapped so that a storage failure can never stop a notification reaching
 * NotificationStore, because this service is system-bound and a crash here would take out
 * notification capture entirely.
 */
public class IrisNotificationListener extends NotificationListenerService {

    private static volatile IrisNotificationListener instance;

    /**
     * Per-notification state needed to judge a later removal.
     *
     * Bounded, because a long-lived listener would otherwise accumulate an entry for every
     * notification the phone has ever shown. The oldest entries are dropped; losing them only
     * costs the ability to classify a removal as suspected, which fails safe to "not a deletion".
     */
    private static final int TRACKED_MAX = 300;

    private static final class Tracked {
        long postTime;
        boolean screenOffAtPost;
        /** When a deletion placeholder was last seen for this conversation, 0 if never. */
        long placeholderAt;
    }

    private final Map<String, Tracked> tracked =
            new LinkedHashMap<String, Tracked>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
                    return size() > TRACKED_MAX;
                }
            };

    /**
     * Last time the screen became interactive or the device was unlocked.
     *
     * This is what separates a usable deletion signal from noise: an app withdrawing its own
     * notification also happens when the owner reads the message, so a removal only counts as a
     * suspected deletion if the screen never came on while the notification was showing.
     */
    private volatile long lastInteractiveAt;

    private BroadcastReceiver screenWatcher;

    /**
     * Whether the system has the notification reader bound.
     *
     * Reported in the archive UI because a lost binding is the most common reason capture silently
     * stops after an app update, and it is otherwise indistinguishable from "no notifications have
     * arrived".
     */
    public static boolean isConnected() { return instance != null; }

    /**
     * When capture went down, or 0 if it is up or has never been up.
     *
     * Zero rather than "now" on first start on purpose: before the very first connection there is
     * nothing to complain about, and claiming hours of blindness the moment the app installs would
     * be a lie.
     */
    public static long blindSince() { return instance != null ? 0L : blindSince; }

    private static volatile long blindSince;

    /**
     * Archive whatever is currently showing in the notification shade.
     *
     * Capture is event-driven, so ticking an app only affects notifications that arrive AFTER that
     * point. Opening the archive straight after choosing apps would therefore show an empty list
     * even though everything is working, which reads as a bug. This pulls in what is on screen now
     * so there is immediate evidence capture is live. Duplicate suppression makes it safe to call
     * repeatedly.
     *
     * @return how many notifications were offered to the archive, not how many were new
     */
    public static int captureVisibleNow() {
        IrisNotificationListener service = instance;
        if (service == null) return 0;
        int offered = 0;
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    service.onNotificationPosted(sbn);
                    offered++;
                }
            }
        } catch (Throwable ignored) { }
        return offered;
    }

    /**
     * Live notifications, for the diagnostic inspector only.
     *
     * Separate from activeForReply because that method's contract is specifically about never
     * using cached history to send a reply. Keeping the inspector on its own entry point means
     * that guarantee is not quietly widened by a diagnostic.
     */
    public static StatusBarNotification[] snapshotForInspection() {
        IrisNotificationListener service = instance;
        if (service == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] items = service.getActiveNotifications();
            return items == null ? new StatusBarNotification[0] : items;
        } catch (Exception e) { return new StatusBarNotification[0]; }
    }

    /**
     * Packages observed posting a notification that offered a usable reply action.
     *
     * Recorded as notifications arrive rather than guessed from the app list, because whether an
     * app supports replying is not discoverable any other way: it depends on what that app chooses
     * to attach. The Responder app picker uses this to show which apps it can actually answer
     * through, so the owner is not left wondering why a ticked app never speaks.
     *
     * An app absent from this set has simply not been seen yet, which is NOT the same as being
     * incapable. The picker is careful to present it that way.
     */
    public static java.util.Set<String> packagesSeenWithReplyAction() {
        synchronized (REPLY_CAPABLE) {
            return new java.util.LinkedHashSet<>(REPLY_CAPABLE);
        }
    }

    private static final java.util.Set<String> REPLY_CAPABLE = new java.util.LinkedHashSet<>();
    private static final int REPLY_CAPABLE_MAX = 60;

    private static void noteReplyCapable(String pkg) {
        if (pkg == null) return;
        synchronized (REPLY_CAPABLE) {
            REPLY_CAPABLE.add(pkg);
            while (REPLY_CAPABLE.size() > REPLY_CAPABLE_MAX) {
                REPLY_CAPABLE.remove(REPLY_CAPABLE.iterator().next());
            }
        }
    }

    /** Active notifications only; cached history must never be used to send a reply. */
    public static StatusBarNotification[] activeForReply() {
        IrisNotificationListener service = instance;
        if (service == null) return new StatusBarNotification[0];
        try {
            StatusBarNotification[] items = service.getActiveNotifications();
            return items == null ? new StatusBarNotification[0] : items;
        } catch (Exception e) { return new StatusBarNotification[0]; }
    }

    public static android.app.Notification.Action replyAction(StatusBarNotification item) {
        if (item == null || item.getNotification().actions == null) return null;
        if ((item.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        for (Notification.Action action : item.getNotification().actions) {
            if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
            if (android.os.Build.VERSION.SDK_INT >= 28
                    && action.getSemanticAction() != Notification.Action.SEMANTIC_ACTION_REPLY) continue;
            // Multiple text fields are ambiguous; support only an explicit single reply field.
            if (action.getRemoteInputs().length == 1
                    && action.getRemoteInputs()[0].getAllowFreeFormInput()) return action;
        }
        return null;
    }

    @Override public void onListenerConnected() {
        instance = this;
        blindSince = 0L;
        startScreenWatch();
        // Records that capture resumed. A gap between a disconnect marker and this one is a window
        // the archive genuinely did not see, which the owner can then judge for themselves rather
        // than being shown an apparently continuous record.
        archiveMark(ArchiveSchema.KIND_LISTENER_CONNECTED, "Notification capture started.");
        // Seed with notifications already showing in the shade
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
            }
        } catch (Exception ignored) { }
    }

    @Override public void onListenerDisconnected() {
        archiveMark(ArchiveSchema.KIND_LISTENER_DISCONNECTED,
                "Notification capture stopped. Messages arriving now are not being saved.");
        stopScreenWatch();
        if (instance == this) instance = null;
        // Stamped only on the first disconnect of a run, so repeated failed rebinds report the
        // true total outage rather than resetting the clock each attempt.
        if (blindSince == 0L) blindSince = System.currentTimeMillis();
        // Ask the system to bind us again. Android drops listeners on low memory, on app update
        // and during some OEM battery sweeps, and without this the binding can stay dead until the
        // owner manually toggles notification access -- which is exactly the silent-stop failure
        // the gap markers above were only able to record after the fact.
        //
        // Safe if access was revoked: the system ignores a rebind request from an unapproved
        // listener, so this cannot recover access the owner deliberately withdrew.
        requestRebindSafely(this);
    }

    /**
     * Ask the system to rebind the listener if it is not currently connected.
     *
     * Called when the archive screen opens, so a dropped binding is repaired by using the app
     * rather than requiring the owner to find the notification access screen and toggle it. The
     * rebind is asynchronous, so capture resumes shortly after rather than immediately.
     */
    public static void ensureConnected(Context context) {
        if (instance != null) return;
        requestRebindSafely(context);
    }

    private static void requestRebindSafely(Context context) {
        if (context == null) return;
        try {
            requestRebind(new android.content.ComponentName(
                    context.getApplicationContext(), IrisNotificationListener.class));
        } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        stopScreenWatch();
        if (responderWorker != null) {
            try { responderWorker.shutdownNow(); } catch (Throwable ignored) { }
            responderWorker = null;
        }
        if (imageWorker != null) {
            // Queued copies are abandoned rather than awaited: the rows are already saved, and
            // blocking service teardown on image decoding would risk an ANR.
            try { imageWorker.shutdownNow(); } catch (Throwable ignored) { }
            imageWorker = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /**
     * Watch for the screen coming on.
     *
     * ACTION_SCREEN_ON cannot be declared in the manifest, so it is registered dynamically for as
     * long as the listener is connected. Without it the suspected-deletion heuristic would have to
     * guess from two point-in-time samples and would misread "the owner woke the phone, read the
     * message and locked it again" as a deletion.
     */
    private void startScreenWatch() {
        if (screenWatcher != null) return;
        try {
            if (isScreenInteractive()) lastInteractiveAt = System.currentTimeMillis();
            screenWatcher = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    lastInteractiveAt = System.currentTimeMillis();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenWatcher, filter);
        } catch (Exception e) {
            // Without the watcher the heuristic simply never fires, because lastInteractiveAt stays
            // put and cannot prove the screen was off. Failing closed is correct here: no false
            // "suspected" flags is better than guessing.
            screenWatcher = null;
        }
    }

    private void stopScreenWatch() {
        if (screenWatcher == null) return;
        try { unregisterReceiver(screenWatcher); } catch (Exception ignored) { }
        screenWatcher = null;
    }

    private boolean isScreenInteractive() {
        try {
            android.os.PowerManager power =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return true;
            boolean on = power.isInteractive();
            if (!on) return false;
            // An interactive screen still showing the lock screen means the message could not have
            // been read in the app, so it counts as unseen.
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            return keyguard == null || !keyguard.isKeyguardLocked();
        } catch (Exception e) {
            // Unknown screen state must not manufacture evidence: report interactive so the
            // heuristic stays silent.
            return true;
        }
    }

    /** Dismiss all clearable system notifications, if access is granted. Returns true if attempted. */
    public static boolean dismissAll() {
        if (instance == null) return false;
        try { instance.cancelAllNotifications(); return true; }
        catch (Throwable t) { return false; }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            String pkg = sbn.getPackageName();
            if (pkg == null) return;
            // Skip our own notifications
            if (pkg.equals(getPackageName())) return;

            Notification n = sbn.getNotification();
            if (n == null) return;
            // Skip ongoing (foreground service, persistent) and group summaries
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

            Bundle extras = n.extras;
            if (extras == null) return;
            String title = charSeq(extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = charSeq(extras.getCharSequence(Notification.EXTRA_TEXT));
            if ((text == null || text.isEmpty())) {
                CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (big != null) text = big.toString();
            }

            String appLabel = resolveAppLabel(pkg);
            NotificationStore.add(getApplicationContext(), pkg, appLabel, title, text, sbn.getPostTime());

            // Archiving is strictly additive and runs AFTER the store above, so a failure here
            // cannot stop a notification reaching the existing read-out and summary features.
            try {
                archive(sbn, pkg, appLabel, title, text, extras);
            } catch (Throwable ignored) { }
        } catch (Exception ignored) { }
    }

    /**
     * Archive a notification, and flag an earlier message if this one is a deletion placeholder.
     *
     * The placeholder itself is never stored as a message: the owner asked for the original text to
     * be preserved and the placeholder is pure noise. It is used only to flag, and to record the
     * moment the deletion was detected.
     */
    private void archive(StatusBarNotification sbn, String pkg, String appLabel,
                         String title, String text, Bundle extras) {
        Context context = getApplicationContext();
        AppSettings settings = new AppSettings(context);
        if (!settings.notificationArchive()) return;

        Set<String> targets = settings.archivedApps();
        if (!settings.archivedAppsChosen()) {
            // First run: fall back to the known messaging apps rather than archiving everything.
            if (!ArchiveAppTargets.shouldPreTick(pkg)) return;
        } else if (!targets.contains(pkg)) {
            return;
        }

        String key = sbn.getKey() == null ? pkg + "|" + title : sbn.getKey();
        long now = System.currentTimeMillis();

        boolean groupHint = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        DeletedMessageRules.Parsed parsed = DeletedMessageRules.parseSender(title, text, groupHint);

        // A deletion placeholder flags an existing row instead of becoming one.
        if (DeletedMessageRules.isAnyDeletionPlaceholder(text)) {
            synchronized (tracked) {
                Tracked state = tracked.get(key);
                if (state == null) { state = new Tracked(); tracked.put(key, state); }
                state.placeholderAt = now;
            }
            // The owner's own deletions are recognised so the placeholder is not archived as a
            // message, but they are never reported as somebody taking a message back.
            if (!DeletedMessageRules.isSenderDeletion(text)) return;

            String who = parsed.sender.isEmpty() ? parsed.rawTitle : parsed.sender;
            long flagged = NotificationArchive.flagMostRecentFromSender(
                    context, pkg, who, DeletedMessageRules.Confidence.CONFIRMED, now);
            if (flagged <= 0) {
                // Nothing to flag: the original predates capture or arrived during a gap. Recorded
                // rather than dropped, so a real deletion does not vanish without trace.
                NotificationArchive.recordOrphanDeletion(context, appLabel, who, now);
            }
            ArchiveAlerts.deletionCaught(context, appLabel, who, now,
                    DeletedMessageRules.Confidence.CONFIRMED);
            return;
        }

        // Per-message capture. An on-device probe proved the archive was losing most messages:
        // a notification holding seven messages from one chat put only the LAST of them in
        // EXTRA_TEXT, so six were silently dropped. The full set lives in EXTRA_MESSAGES, with a
        // real sender and timestamp per message, which is also better attribution than parsing a
        // sender out of the title.
        java.util.List<Bundle> messages = messageBundles(extras);

        if (messages.isEmpty()) {
            // No per-message detail. Skip a pure roll-up ("14 messages from 3 chats"), which
            // would otherwise become a row whose entire content is a number, then fall back to
            // single-row capture so non-messaging apps still archive normally.
            if (MessageRollup.isSummaryOnly(text, 0)) return;
            recordOne(context, settings, sbn, pkg, appLabel, key, extras,
                    parsed.rawTitle, parsed.sender, parsed.group, parsed.body, sbn.getPostTime());
            offerToResponder(context, sbn, pkg, appLabel, parsed.rawTitle,
                    parsed.sender, parsed.body, !parsed.group.isEmpty(), 0);
            return;
        }

        String groupName = MessageRollup.groupFromTitle(title);
        String newestSender = "";
        String newestBody = "";
        for (Bundle msg : messages) {
            String body = charSeq(msg.getCharSequence("text"));
            String sender = charSeq(msg.getCharSequence("sender"));
            if (body == null) body = "";
            if (sender == null) sender = "";
            if (body.isEmpty() && sender.isEmpty()) continue;

            // Each message keeps its own timestamp, which is what makes the existing unique
            // fingerprint index do the deduplication for free: when the same chat posts again
            // with one more message, the ones already stored collide and only the new one is
            // inserted. Falling back to the notification's time would make every message in a
            // burst share one timestamp and collapse into a single row.
            long when = msg.getLong("time", 0L);
            if (when <= 0) when = sbn.getPostTime();

            // The group is whatever is left of the title once the count and latest-sender
            // decoration is stripped. Cleared when it just repeats the sender, which is how a
            // one-to-one chat presents.
            String group = groupName.equalsIgnoreCase(sender) ? "" : groupName;

            long rowId = recordOne(context, settings, sbn, pkg, appLabel, key, extras,
                    parsed.rawTitle, sender, group, body, when);

            // An attached photo, confirmed present on this device by the PROBE diagnostic:
            // WhatsApp supplies a content URI into its own media provider. The read permission
            // granted with a notification is narrow and short-lived, so the bytes are copied now
            // rather than the link being stored for later.
            if (rowId > 0) captureImage(context, msg, rowId);

            // Remember the newest message, for the responder below.
            newestSender = sender;
            newestBody = body;
        }

        // The auto-responder runs AFTER capture and on its own thread, so it can neither delay
        // notification delivery nor prevent a message being archived. Considered once per
        // notification using the newest message: deciding separately for each of seven messages
        // would put seven entries in the log for one conversation.
        offerToResponder(context, sbn, pkg, appLabel, title, newestSender, newestBody,
                groupHint || !groupName.isEmpty(), messages.size());
    }

    /**
     * Hand a notification to the auto-responder.
     *
     * Wrapped completely: the responder is the newest and least proven part of this file, and a
     * fault in it must not be able to stop notifications being captured.
     */
    private void offerToResponder(final Context context, final StatusBarNotification sbn,
                                  final String pkg, final String appLabel, final String title,
                                  final String sender, final String body, final boolean group,
                                  final int messageCount) {
        try {
            final AutoResponder.Incoming incoming = new AutoResponder.Incoming();
            incoming.sbn = sbn;
            incoming.pkg = pkg;
            incoming.appLabel = appLabel;
            incoming.sender = sender == null || sender.isEmpty() ? title : sender;
            incoming.body = body == null ? "" : body;
            incoming.group = group;
            incoming.rollup = MessageRollup.isSummaryOnly(body, messageCount);
            incoming.placeholder = DeletedMessageRules.isAnyDeletionPlaceholder(body);
            incoming.fromOwner = false;

            // Remember that this app can be replied to, so the picker can show it.
            if (IrisNotificationListener.replyAction(sbn) != null) noteReplyCapable(pkg);

            responderWorker().execute(new Runnable() {
                @Override public void run() {
                    try {
                        AutoResponder.consider(context, incoming);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /**
     * Worker for responder decisions.
     *
     * Separate from the image worker on purpose. Decoding a large photo can take seconds, and
     * queueing a reply decision behind one would delay it for no reason.
     */
    private synchronized java.util.concurrent.ExecutorService responderWorker() {
        if (responderWorker == null) {
            responderWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        return responderWorker;
    }

    private java.util.concurrent.ExecutorService responderWorker;

    /**
     * Copy an attached image on a background thread, then record its filename.
     *
     * Off-thread because decoding even a downscaled photo is slow enough to stall notification
     * delivery, and this runs inside onNotificationPosted. The row is already saved by this point,
     * so a failed or slow copy costs the picture and never the message.
     */
    private void captureImage(final Context context, Bundle msg, final long rowId) {
        final Object uriValue = msg.get("uri");
        if (!(uriValue instanceof android.net.Uri)) return;
        final String mime = msg.getString("type");
        if (!ArchiveImagePolicy.isStorableImage(mime)) return;

        final String fingerprint = NotificationArchive.fingerprintOf(context, rowId);
        if (fingerprint.isEmpty()) return;

        imageWorker().execute(new Runnable() {
            @Override public void run() {
                try {
                    String stored = ArchiveImages.store(
                            context, (android.net.Uri) uriValue, mime, fingerprint);
                    if (!stored.isEmpty()) {
                        NotificationArchive.setImagePath(context, rowId, stored);
                    }
                } catch (Throwable ignored) {
                    // Losing a photo must never take down the notification listener.
                }
            }
        });
    }

    /**
     * Single-threaded worker for image copies.
     *
     * One thread on purpose: a burst of photo messages should copy them one after another rather
     * than decoding several large bitmaps at once, which is how this process would get killed for
     * memory. Created lazily so a text-only install never starts a thread at all.
     */
    private synchronized java.util.concurrent.ExecutorService imageWorker() {
        if (imageWorker == null) {
            imageWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        return imageWorker;
    }

    private java.util.concurrent.ExecutorService imageWorker;

    /**
     * Every per-message bundle in EXTRA_MESSAGES, oldest first.
     *
     * Returns an empty list rather than null for anything unexpected, so the caller's fallback to
     * single-row capture is the natural path when an app does not use MessagingStyle at all.
     */
    private java.util.List<Bundle> messageBundles(Bundle extras) {
        java.util.List<Bundle> out = new java.util.ArrayList<>();
        try {
            android.os.Parcelable[] raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (raw == null) return out;
            for (android.os.Parcelable item : raw) {
                if (item instanceof Bundle) out.add((Bundle) item);
            }
        } catch (Throwable ignored) {
            // A malformed extras bundle must not stop the notification being archived at all.
        }
        return out;
    }

    /**
     * Writes one archive row and updates the tracking state used by deletion detection.
     *
     * @return the new row id, 0 if nothing was written (a duplicate, a full archive, or an error)
     */
    private long recordOne(Context context, AppSettings settings, StatusBarNotification sbn,
                           String pkg, String appLabel, String key, Bundle extras,
                           String rawTitle, String sender, String group, String body, long when) {
        NotificationArchive.Capture capture = new NotificationArchive.Capture();
        capture.pkg = pkg;
        capture.appLabel = appLabel;
        capture.userHandle = userIdOf(sbn);
        capture.accountLabel = ArchiveAppTargets.accountLabel(
                pkg, appLabel, capture.userHandle, charSeq(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        capture.notifKey = key;
        capture.rawTitle = rawTitle;
        capture.sender = sender;
        capture.group = group;
        capture.body = body;
        capture.postTime = when;

        long id = NotificationArchive.record(context, capture);
        if (id > 0) {
            settings.setArchiveRowsSinceExport(settings.archiveRowsSinceExport() + 1);
            synchronized (tracked) {
                Tracked state = tracked.get(key);
                if (state == null) { state = new Tracked(); tracked.put(key, state); }
                state.postTime = when;
                state.screenOffAtPost = !isScreenInteractive();
            }
        }
        return id > 0 ? id : 0;
    }

    /**
     * Whether the notification arrived on a different Android profile than IRIS itself.
     *
     * Returns 0 for our own profile and 1 for any other. This is the only way to tell a
     * work-profile app from the personal one, because they share a package name.
     *
     * Implemented by comparing UserHandles rather than reading a numeric id: getUserId() is
     * deprecated in favour of getUser(), and UserHandle.getIdentifier() is not public API. The
     * label logic only needs "same profile or not", so a boolean-as-int avoids depending on
     * anything hidden.
     */
    private int userIdOf(StatusBarNotification sbn) {
        try {
            android.os.UserHandle user = sbn.getUser();
            if (user == null) return 0;
            return user.equals(android.os.Process.myUserHandle()) ? 0 : 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void archiveMark(int kind, String note) {
        try {
            Context context = getApplicationContext();
            if (!new AppSettings(context).notificationArchive()) return;
            NotificationArchive.mark(context, kind, note);
        } catch (Throwable ignored) { }
    }

    /**
     * A notification left the shade.
     *
     * The reason code is what makes this usable: it tells us whether the OWNER removed it (swipe
     * or tap, in which case they saw it and it is not a deletion) or whether the APP withdrew it,
     * which is what an unsend looks like. Mapping happens here against the real compiler-checked
     * REASON_* constants, so {@link DeletedMessageRules} never has to hardcode their numeric values.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        try {
            super.onNotificationRemoved(sbn, rankingMap, reason);
        } catch (Throwable ignored) { }
        try {
            handleRemoval(sbn, classify(reason));
        } catch (Throwable ignored) { }
    }

    private DeletedMessageRules.Removal classify(int reason) {
        if (reason == REASON_CLICK) return DeletedMessageRules.Removal.USER_CLICKED;
        if (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
                || reason == REASON_LISTENER_CANCEL || reason == REASON_LISTENER_CANCEL_ALL) {
            return DeletedMessageRules.Removal.USER_DISMISSED;
        }
        if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            return DeletedMessageRules.Removal.APP_CANCELLED;
        }
        return DeletedMessageRules.Removal.OTHER;
    }

    /**
     * Decide whether a removal was probably a sender unsending a message.
     *
     * This is the Instagram path: it posts no "unsent" placeholder, so the only available evidence
     * is that the app withdrew its own notification while the owner could not have been reading it.
     * The result is always recorded as SUSPECTED and shown differently from a confirmed deletion.
     */
    private void handleRemoval(StatusBarNotification sbn, DeletedMessageRules.Removal removal) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        // The notification is gone, so this conversation may be considered again when it next
        // appears. Without this, one answered conversation would stay blocked for the life of the
        // process rather than only until its notification cleared.
        AutoResponder.forget(sbn.getKey());

        Context context = getApplicationContext();
        AppSettings settings = new AppSettings(context);
        if (!settings.notificationArchive()) return;
        if (settings.archivedAppsChosen()) {
            if (!settings.archivedApps().contains(pkg)) return;
        } else if (!ArchiveAppTargets.shouldPreTick(pkg)) {
            return;
        }

        String key = sbn.getKey();
        if (key == null) return;

        Tracked state;
        synchronized (tracked) { state = tracked.get(key); }
        // No record of it being posted means it predates this listener session, so there is no
        // basis for judging the removal. Silence is the correct answer.
        if (state == null || state.postTime <= 0) return;

        long now = System.currentTimeMillis();
        boolean screenOffThroughout = state.screenOffAtPost && lastInteractiveAt < state.postTime;
        long msSincePlaceholder = state.placeholderAt > 0 ? now - state.placeholderAt : -1L;

        DeletedMessageRules.Confidence confidence = DeletedMessageRules.classifyRemoval(
                removal, screenOffThroughout, now - state.postTime, msSincePlaceholder);
        if (confidence != DeletedMessageRules.Confidence.SUSPECTED) return;

        Bundle extras = sbn.getNotification() == null ? null : sbn.getNotification().extras;
        String title = extras == null ? "" : charSeq(extras.getCharSequence(Notification.EXTRA_TITLE));
        boolean groupHint = extras != null
                && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        DeletedMessageRules.Parsed parsed = DeletedMessageRules.parseSender(
                title, extras == null ? "" : charSeq(extras.getCharSequence(Notification.EXTRA_TEXT)),
                groupHint);
        String who = parsed.sender.isEmpty() ? parsed.rawTitle : parsed.sender;

        long flagged = NotificationArchive.flagMostRecentFromSender(
                context, pkg, who, DeletedMessageRules.Confidence.SUSPECTED, now);
        if (flagged > 0) {
            ArchiveAlerts.deletionCaught(context, resolveAppLabel(pkg), who, now,
                    DeletedMessageRules.Confidence.SUSPECTED);
        }
    }

    /** Older platforms route here; forwarded so one code path handles removals. */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Deliberately does nothing. Without a reason code there is no way to tell an owner
        // dismissal from an app withdrawal, and guessing would produce exactly the false
        // "suspected deletion" noise the reason code exists to avoid. The three-argument overload
        // above is available from API 26 and minSdk is 26, so this is never the only callback.
    }

    private String resolveAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String charSeq(CharSequence cs) {
        return cs == null ? null : cs.toString().trim();
    }
}
