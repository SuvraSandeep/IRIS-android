package com.iris.assistant;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

/**
 * The one place a reply is actually submitted to another app.
 *
 * Extracted verbatim from NotificationReplyActivity so the manual reply screen and the
 * auto-responder share a single implementation. Duplicating a security-sensitive send would mean
 * two sets of guards to keep in step, and the second copy would be the one that fell behind.
 *
 * What this does NOT do is decide whether a reply is allowed. Authorisation differs between the
 * two callers and deliberately stays with them:
 *
 *   - The manual screen requires the device to be unlocked, so that a model which can open the
 *     screen still cannot press Send.
 *   - The responder is authorised in advance when the owner arms it, and must work while the phone
 *     is locked, which is the entire point of it.
 *
 * Folding either rule in here would quietly grant it to the other caller.
 */
public final class NotificationReply {

    /** Outcome of an attempted send. */
    public enum Result {
        /** Handed to the app. Delivery is not and cannot be verified from here. */
        SENT,
        /** Nothing to send. */
        EMPTY_TEXT,
        /**
         * The notification is gone, or is no longer the one that was reviewed.
         *
         * This is the expected outcome rather than an error: the reply handle lives on the
         * notification, so once it is dismissed or replaced there is nothing left to fire.
         */
        NOT_FOUND,
        /** The notification offers no usable reply action. */
        NO_ACTION,
        /** The app refused the handle, usually because the PendingIntent was cancelled. */
        FAILED
    }

    private NotificationReply() { }

    /**
     * Submit a reply to the app that posted a notification.
     *
     * The verification is the point of this method and is unchanged from the original: the live
     * notification list is re-read, and the key, the post time AND the action intent must all
     * still match the notification that was reviewed. Any mismatch abandons the send. That is what
     * stops a reply landing in a conversation other than the one it was written for, if the app
     * has replaced its notification in the meantime.
     *
     * @param selected   the notification as it was when the reply was composed
     * @param body       the text to send
     * @param ledgerKind audit label, so a manual reply and an automatic one are distinguishable
     */
    public static Result send(Context context, StatusBarNotification selected, String body,
                              String ledgerKind) {
        if (context == null || selected == null) return Result.NOT_FOUND;
        if (body == null || body.trim().isEmpty()) return Result.EMPTY_TEXT;

        // Guarded explicitly rather than relied upon. The original dereferenced this inside the
        // comparison below, which was safe only because the caller had already filtered the list;
        // a second caller with different filtering would have hit a null pointer.
        Notification.Action expected = IrisNotificationListener.replyAction(selected);
        if (expected == null || expected.actionIntent == null) return Result.NO_ACTION;

        for (StatusBarNotification current : IrisNotificationListener.activeForReply()) {
            if (current.getKey() == null || !current.getKey().equals(selected.getKey())) continue;

            Notification.Action action = IrisNotificationListener.replyAction(current);
            if (action == null || action.actionIntent == null) return Result.NO_ACTION;

            // Same conversation, but a newer notification: the content the owner reviewed is not
            // what this handle now points at, so it is abandoned rather than sent.
            if (current.getPostTime() != selected.getPostTime()) return Result.NOT_FOUND;
            if (!action.actionIntent.equals(expected.actionIntent)) return Result.NOT_FOUND;

            try {
                RemoteInput field = action.getRemoteInputs()[0];
                Bundle result = new Bundle();
                result.putCharSequence(field.getResultKey(), body);
                Intent intent = new Intent();
                RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, result);
                action.actionIntent.send(context, 0, intent);

                // Honest wording, preserved from the original: the app has been handed the text,
                // and whether it reaches anyone is beyond anything observable from here.
                try (ActionLedger ledger = new ActionLedger(context)) {
                    ledger.record(ledgerKind, ActionLedger.OK,
                            "Reply submitted to " + current.getPackageName()
                                    + "; delivery unverified", "", "", false);
                } catch (Throwable ignored) {
                    // A failed audit write must not be reported as a failed send; the message has
                    // already gone.
                }
                return Result.SENT;
            } catch (Exception failed) {
                return Result.FAILED;
            }
        }
        return Result.NOT_FOUND;
    }

    /** Whether the reply actually left. */
    public static boolean sent(Result result) {
        return result == Result.SENT;
    }

    /** Plain explanation, for a toast or a decision log entry. */
    public static String explain(Result result) {
        if (result == null) return "Nothing happened.";
        switch (result) {
            case SENT:
                return "Reply submitted to the app. Delivery is not verified.";
            case EMPTY_TEXT:
                return "There was nothing to send.";
            case NOT_FOUND:
                return "The notification changed or the reply action expired.";
            case NO_ACTION:
                return "That app offers no reply action I can use.";
            case FAILED:
                return "The app refused the reply. Its reply handle had probably expired.";
            default:
                return result.name();
        }
    }
}
