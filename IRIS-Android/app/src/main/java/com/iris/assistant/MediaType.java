package com.iris.assistant;

import java.util.Locale;

/**
 * Names the KIND of a media attachment from its MIME type and/or the app's placeholder text, so
 * the archive, the log and the digest can say "a voice note" or "a document" instead of a generic
 * "media". Pure and offline-testable.
 *
 * Used only to LABEL something already recognised as media (by MessageCategory), so its text
 * matching can be broad without risking false positives on ordinary messages.
 */
public final class MediaType {

    public enum Kind { NONE, PHOTO, VIDEO, VOICE_NOTE, AUDIO, DOCUMENT, STICKER, GIF, CONTACT,
        LOCATION }

    private MediaType() { }

    /** Classify from a MIME type (e.g. "image/jpeg"), or NONE. */
    public static Kind fromMime(String mime) {
        if (mime == null) return Kind.NONE;
        String m = mime.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty()) return Kind.NONE;
        if (m.equals("image/gif")) return Kind.GIF;
        if (m.startsWith("image/")) return Kind.PHOTO;
        if (m.startsWith("video/")) return Kind.VIDEO;
        if (m.startsWith("audio/")) return Kind.VOICE_NOTE;
        if (m.startsWith("application/") || m.startsWith("text/")) return Kind.DOCUMENT;
        if (m.contains("vcard") || m.contains("contact")) return Kind.CONTACT;
        return Kind.NONE;
    }

    /** Classify from a placeholder text (e.g. "sent a voice message"), or NONE. */
    public static Kind fromText(String text) {
        if (text == null) return Kind.NONE;
        String f = " " + MessageCategory.normalise(text) + " ";
        if (f.isEmpty()) return Kind.NONE;
        if (has(f, "voice message", "voice note", "sent an audio", "audio message")) {
            return Kind.VOICE_NOTE;
        }
        if (has(f, "gif")) return Kind.GIF;
        if (has(f, "sticker")) return Kind.STICKER;
        if (has(f, "sent a video", "video message", "sent you a video")) return Kind.VIDEO;
        if (has(f, "sent a photo", "sent a picture", "sent an image", "photo", "picture",
                "image")) return Kind.PHOTO;
        if (has(f, "sent a document", "document", "sent a file", "pdf")) return Kind.DOCUMENT;
        if (has(f, "shared a contact", "sent a contact", "contact card")) return Kind.CONTACT;
        if (has(f, "live location", "shared location", "sent location",
                "shared live location")) return Kind.LOCATION;
        return Kind.NONE;
    }

    /** Classify from MIME first, then fall back to the placeholder text. */
    public static Kind classify(String mime, String text) {
        Kind k = fromMime(mime);
        return k != Kind.NONE ? k : fromText(text);
    }

    /** A human label like "a photo" / "a voice note", or "" for NONE. */
    public static String label(Kind kind) {
        if (kind == null) return "";
        switch (kind) {
            case PHOTO: return "a photo";
            case VIDEO: return "a video";
            case VOICE_NOTE: return "a voice note";
            case AUDIO: return "an audio clip";
            case DOCUMENT: return "a document";
            case STICKER: return "a sticker";
            case GIF: return "a GIF";
            case CONTACT: return "a contact";
            case LOCATION: return "a location";
            default: return "";
        }
    }

    /** Convenience: label straight from MIME and/or text. */
    public static String label(String mime, String text) {
        return label(classify(mime, text));
    }

    private static boolean has(String paddedFlat, String... phrases) {
        for (String p : phrases) {
            if (paddedFlat.contains(" " + p + " ")) return true;
        }
        return false;
    }
}
