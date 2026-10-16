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

    /**
     * A witty, per-tone reply acknowledging the media kind, or "" for NONE. Tone order:
     * PROFESSIONAL, CASUAL, SARCASTIC, BRIEF. {owner} is left literal for the caller's fill().
     * Every variant names IRIS.
     */
    public static String reply(Kind kind, ReplyTone.Tone tone) {
        String[] variants = repliesFor(kind);
        if (variants == null) return "";
        int idx = tone == null ? 0 : tone.ordinal();
        if (idx < 0 || idx >= variants.length || variants[idx] == null || variants[idx].isEmpty()) {
            return variants[0];
        }
        return variants[idx];
    }

    private static String[] repliesFor(Kind kind) {
        if (kind == null) return null;
        switch (kind) {
            case VOICE_NOTE:
            case AUDIO:
                return new String[]{
                        "Got the voice note \u2014 I'm IRIS, {owner}'s assistant. I don't have ears, so "
                                + "I've saved it untouched for him to hear.",
                        "Voice note saved for {owner}! No ears on me, so it's safe and unheard. "
                                + "I'm IRIS.",
                        "Audio received. I process text, so your voice is safe from my bot ears. "
                                + "{owner} will listen later. I'm IRIS.",
                        "Voice note saved for {owner}. I'm IRIS."};
            case PHOTO:
            case VIDEO:
                return new String[]{
                        "Got the media \u2014 I'm IRIS, {owner}'s assistant. Saved at the top for him to "
                                + "view.",
                        "Media saved for {owner} to look at when he's back! I'm IRIS.",
                        "Media logged. If it's a meme {owner} will love it; if it's work he'll hate "
                                + "that I saved it. I'm IRIS.",
                        "Media saved for {owner}. I'm IRIS."};
            case STICKER:
            case GIF:
                return new String[]{
                        "Sticker/GIF saved for {owner} \u2014 I'm IRIS, his assistant.",
                        "Cute one! Saved for {owner}. I'm IRIS.",
                        "A sticker \u2014 the pinnacle of human communication. Logged for {owner}. I'm "
                                + "IRIS.",
                        "Sticker saved for {owner}. I'm IRIS."};
            case DOCUMENT:
                return new String[]{
                        "Got the file \u2014 I'm IRIS, {owner}'s assistant. Logged so he can open it on "
                                + "his screen later.",
                        "File saved for {owner} to pull up when he's back! I'm IRIS.",
                        "File received. Let's hope it's not a 50-page PDF for his weekend. Logged "
                                + "for {owner}. I'm IRIS.",
                        "File saved for {owner}. I'm IRIS."};
            case CONTACT:
                return new String[]{
                        "Got the contact card \u2014 I'm IRIS, {owner}'s assistant. Saved so he has the "
                                + "number.",
                        "Contact saved for {owner}! I'm IRIS.",
                        "New human data acquired. Contact card saved for {owner}. I'm IRIS.",
                        "Contact saved for {owner}. I'm IRIS."};
            case LOCATION:
                return new String[]{
                        "Got the location \u2014 I'm IRIS, {owner}'s assistant. Pinned at the top for "
                                + "him.",
                        "Location saved for {owner} \u2014 pinned it! I'm IRIS.",
                        "Coordinates logged. I won't leak {owner}'s, but I've pinned yours for him. "
                                + "I'm IRIS.",
                        "Location pinned for {owner}. I'm IRIS."};
            default:
                return null;
        }
    }

    private static boolean has(String paddedFlat, String... phrases) {
        for (String p : phrases) {
            if (paddedFlat.contains(" " + p + " ")) return true;
        }
        return false;
    }
}
