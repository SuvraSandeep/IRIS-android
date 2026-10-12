package com.iris.assistant;

/**
 * Turns "what did we find in the notification extras" into a verdict about image capture.
 *
 * Exists because this question was answered wrongly once already. An earlier check looked only at
 * EXTRA_PICTURE, found WhatsApp did not set it, and concluded images were unavailable. That ignored
 * MessagingStyle, where an image arrives as a data URI attached to an individual message
 * (Notification.MessagingStyle.Message.setData) -- which is the route a smartwatch uses, and the
 * reason the owner sees images on a watch while the archive saw none.
 *
 * Pure, with no Android imports, so the reasoning is covered offline. The Android side only reads
 * the extras and reports which flags were true.
 *
 * The distinction this class draws and the earlier conclusion missed:
 *   - a bitmap in EXTRA_PICTURE is already in memory and can be saved directly
 *   - a data URI must be read through the content resolver, which can fail even when present
 *   - a large icon is almost always the sender's avatar, not the photo they sent, so finding one
 *     must never be reported as "images are available"
 */
public final class NotificationImageSupport {

    /** What the archive could do with this notification's imagery, if anything. */
    public enum Verdict {
        /** Nothing image-like present at all. */
        NONE,
        /** Only an avatar. Present on nearly every message, and never the sent photo. */
        AVATAR_ONLY,
        /** A full bitmap is attached and already in memory. */
        BITMAP_AVAILABLE,
        /** A data URI is attached. Readable only if the grant holds. */
        URI_ATTACHED,
        /** A URI was attached but could not be opened, so capture would fail. */
        URI_UNREADABLE
    }

    private NotificationImageSupport() { }

    /**
     * @param hasPicture    EXTRA_PICTURE held a bitmap
     * @param hasDataUri    at least one MessagingStyle message carried a data URI
     * @param uriReadable   that URI could actually be opened through the content resolver
     * @param hasLargeIcon  a large icon was present
     */
    public static Verdict verdict(boolean hasPicture, boolean hasDataUri,
                                  boolean uriReadable, boolean hasLargeIcon) {
        // A real bitmap outranks everything: nothing can fail between finding it and saving it.
        if (hasPicture) return Verdict.BITMAP_AVAILABLE;

        if (hasDataUri) return uriReadable ? Verdict.URI_ATTACHED : Verdict.URI_UNREADABLE;

        // Checked last and reported distinctly on purpose. An avatar is attached to essentially
        // every message notification, so treating its presence as image support would report
        // success on every single notification and be wrong every time.
        if (hasLargeIcon) return Verdict.AVATAR_ONLY;

        return Verdict.NONE;
    }

    /** Whether the archive could actually store a picture from this notification. */
    public static boolean canCapture(Verdict verdict) {
        return verdict == Verdict.BITMAP_AVAILABLE || verdict == Verdict.URI_ATTACHED;
    }

    /** Plain-language explanation of a verdict, for the inspector report. */
    public static String explain(Verdict verdict) {
        if (verdict == null) return "No verdict.";
        switch (verdict) {
            case BITMAP_AVAILABLE:
                return "IMAGE AVAILABLE (bitmap). The picture is attached to the notification "
                        + "itself and could be saved as-is.";
            case URI_ATTACHED:
                return "IMAGE AVAILABLE (uri). The picture is attached as a link and I was able "
                        + "to open it, so it could be saved.";
            case URI_UNREADABLE:
                return "IMAGE ATTACHED BUT UNREADABLE. A link is present but opening it was "
                        + "refused, so saving it would fail. This is usually a permission grant "
                        + "that did not extend to me.";
            case AVATAR_ONLY:
                return "AVATAR ONLY. The only image here is the sender's profile picture, not "
                        + "anything they sent. Nearly every message has one, so this does not "
                        + "mean photos can be captured.";
            default:
                return "NO IMAGE. This notification carries no picture of any kind.";
        }
    }
}
