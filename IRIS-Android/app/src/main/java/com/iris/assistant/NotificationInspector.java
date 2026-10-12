package com.iris.assistant;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import java.io.InputStream;

/**
 * Reports what a notification actually contains on THIS device.
 *
 * Built because the image question was answered wrongly from documentation. An earlier check found
 * WhatsApp does not set EXTRA_PICTURE and concluded images were impossible -- but the owner's
 * smartwatch displays them, which proves something is there. The route that was never examined is
 * MessagingStyle, where an image arrives as a data URI on an individual message.
 *
 * Rather than guess a third time, this dumps the real extras so the answer comes from the owner's
 * own phone and apps rather than from a web search. It also reveals whether the stacked-message
 * problem is biting: when several messages are unread, many apps replace EXTRA_TEXT with a summary
 * like "3 new messages", and the per-message text survives only inside EXTRA_MESSAGES.
 *
 * Privacy: this report contains message text, so it is shown on screen and copyable but never
 * written to LogStore or CommandHistory. Routing intercepted content into a second store is the
 * exact defect fixed in 13.4.0 and must not be reintroduced by a diagnostic.
 */
public final class NotificationInspector {

    /** Keeps the report readable, and avoids pasting an entire conversation into a dialog. */
    private static final int MAX_NOTIFICATIONS = 6;
    private static final int MAX_VALUE_CHARS = 120;

    private NotificationInspector() { }

    /** Human-readable dump of the live notifications from the apps being archived. */
    public static String report(Context context) {
        if (context == null) return "No context, no report.";
        StatusBarNotification[] active = IrisNotificationListener.snapshotForInspection();
        if (active == null || active.length == 0) {
            return "Nothing to inspect. Either I am not connected, or there are no notifications "
                    + "on screen right now. Send yourself a photo on a ticked app and try again.";
        }

        AppSettings settings = new AppSettings(context);
        java.util.Set<String> targets = settings.archivedApps();
        boolean chosen = settings.archivedAppsChosen();

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (StatusBarNotification sbn : active) {
            if (shown >= MAX_NOTIFICATIONS) break;
            String pkg = sbn.getPackageName();
            if (pkg == null) continue;
            boolean watched = chosen ? targets.contains(pkg)
                    : ArchiveAppTargets.shouldPreTick(pkg);
            if (!watched) continue;

            out.append(shown == 0 ? "" : "\n\n").append(describe(context, sbn));
            shown++;
        }

        if (shown == 0) {
            return "None of the notifications on screen are from apps you tick. Send yourself a "
                    + "photo on WhatsApp or Instagram, then come straight back here.";
        }
        return out.toString();
    }

    private static String describe(Context context, StatusBarNotification sbn) {
        StringBuilder out = new StringBuilder();
        Notification n = sbn.getNotification();
        Bundle extras = n == null ? null : n.extras;

        out.append("=== ").append(sbn.getPackageName()).append(" ===\n");
        if (extras == null) return out.append("No extras at all.").toString();

        boolean hasPicture = extras.get(Notification.EXTRA_PICTURE) instanceof Bitmap;
        boolean hasLargeIcon = extras.get(Notification.EXTRA_LARGE_ICON) != null
                || extras.get(Notification.EXTRA_LARGE_ICON_BIG) != null;

        // The part the earlier conclusion never looked at. MessagingStyle keeps one bundle per
        // message, each of which may carry its own data URI for an attached image.
        boolean hasDataUri = false;
        boolean uriReadable = false;
        int messageCount = 0;
        StringBuilder messages = new StringBuilder();

        Parcelable[] raw = null;
        try { raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES); }
        catch (Throwable ignored) { }

        if (raw != null) {
            for (Parcelable item : raw) {
                if (!(item instanceof Bundle)) continue;
                Bundle msg = (Bundle) item;
                messageCount++;
                CharSequence text = msg.getCharSequence("text");
                CharSequence sender = msg.getCharSequence("sender");
                Object uriValue = msg.get("uri");
                String mime = msg.getString("type");

                messages.append("  [").append(messageCount).append("] ")
                        .append(sender == null ? "(no sender)" : clip(sender.toString()))
                        .append(": ").append(text == null ? "(no text)" : clip(text.toString()));

                if (uriValue instanceof Uri) {
                    hasDataUri = true;
                    Uri uri = (Uri) uriValue;
                    boolean readable = canOpen(context, uri);
                    if (readable) uriReadable = true;
                    messages.append("\n      ATTACHMENT mime=").append(mime == null ? "?" : mime)
                            .append(" readable=").append(readable)
                            .append("\n      uri=").append(clip(uri.toString()));
                }
                messages.append('\n');
            }
        }

        out.append("title: ").append(clipOrNone(extras.getCharSequence(Notification.EXTRA_TITLE)))
                .append("\ntext: ").append(clipOrNone(extras.getCharSequence(Notification.EXTRA_TEXT)))
                .append("\nbigText: ").append(clipOrNone(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)))
                // If this is set, the plain text above is probably a useless "3 new messages"
                // roll-up and the real content is only in the per-message list below.
                .append("\nsummary: ").append(clipOrNone(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)))
                .append("\nstyle: ").append(extras.getString(Notification.EXTRA_TEMPLATE, "(none)"))
                .append("\nEXTRA_PICTURE: ").append(hasPicture ? "BITMAP PRESENT" : "absent")
                .append("\nlarge icon: ").append(hasLargeIcon ? "present" : "absent")
                .append("\nEXTRA_MESSAGES: ").append(messageCount).append(" message(s)\n");

        if (messages.length() > 0) out.append(messages);

        NotificationImageSupport.Verdict verdict = NotificationImageSupport.verdict(
                hasPicture, hasDataUri, uriReadable, hasLargeIcon);
        out.append("\nVERDICT: ").append(NotificationImageSupport.explain(verdict));
        return out.toString();
    }

    /**
     * Whether the URI can actually be opened from here.
     *
     * Tested by opening it rather than by checking a permission, because the grant a notification
     * carries is specific and short-lived. "Present" and "readable" are genuinely different
     * answers, and promising a saved image on the strength of presence alone would be the same
     * class of mistake as the original EXTRA_PICTURE conclusion.
     */
    private static boolean canOpen(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null && in.read() != -2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String clipOrNone(CharSequence value) {
        return value == null || value.length() == 0 ? "(none)" : clip(value.toString());
    }

    private static String clip(String value) {
        if (value == null) return "(null)";
        String flat = value.replace('\n', ' ').trim();
        return flat.length() <= MAX_VALUE_CHARS ? flat
                : flat.substring(0, MAX_VALUE_CHARS) + "\u2026";
    }
}
