package com.iris.assistant;

/** Offline checks for media-type labelling. */
public final class MediaTypeTest {

    private static int checks;

    private static void eq(Object a, Object b, String what) {
        checks++;
        if (a == null ? b != null : !a.equals(b)) {
            throw new AssertionError("FAILED: " + what + " expected " + b + " got " + a);
        }
    }

    public static void main(String[] args) {
        // From MIME.
        eq(MediaType.fromMime("image/jpeg"), MediaType.Kind.PHOTO, "image/jpeg -> photo");
        eq(MediaType.fromMime("image/gif"), MediaType.Kind.GIF, "image/gif -> gif");
        eq(MediaType.fromMime("video/mp4"), MediaType.Kind.VIDEO, "video -> video");
        eq(MediaType.fromMime("audio/ogg"), MediaType.Kind.VOICE_NOTE, "audio -> voice note");
        eq(MediaType.fromMime("application/pdf"), MediaType.Kind.DOCUMENT, "pdf -> document");
        eq(MediaType.fromMime(null), MediaType.Kind.NONE, "null mime -> none");

        // From placeholder text.
        eq(MediaType.fromText("sent a voice message"), MediaType.Kind.VOICE_NOTE, "voice note");
        eq(MediaType.fromText("\uD83D\uDCF7 Photo"), MediaType.Kind.PHOTO, "photo placeholder");
        eq(MediaType.fromText("sent a sticker"), MediaType.Kind.STICKER, "sticker");
        eq(MediaType.fromText("sent a GIF"), MediaType.Kind.GIF, "gif");
        eq(MediaType.fromText("sent a document"), MediaType.Kind.DOCUMENT, "document");
        eq(MediaType.fromText("shared a contact"), MediaType.Kind.CONTACT, "contact");
        eq(MediaType.fromText("live location"), MediaType.Kind.LOCATION, "location");
        eq(MediaType.fromText("hello there"), MediaType.Kind.NONE, "ordinary text -> none");

        // MIME wins over text.
        eq(MediaType.classify("audio/ogg", "sent a photo"), MediaType.Kind.VOICE_NOTE,
                "mime takes precedence over text");

        // Labels.
        eq(MediaType.label(MediaType.Kind.VOICE_NOTE), "a voice note", "voice note label");
        eq(MediaType.label(MediaType.Kind.PHOTO), "a photo", "photo label");
        eq(MediaType.label(MediaType.Kind.NONE), "", "none has no label");
        eq(MediaType.label("video/mp4", null), "a video", "convenience label");

        System.out.println("Passed " + checks + " media type checks");
    }
}
