package com.iris.assistant;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

/**
 * Phase 3 helper — find the most recent capture IRIS made, so "send the last screenshot" and
 * "where did you save it" work from real files rather than remembered guesses.
 *
 * Looks in the IRIS folders first (Pictures/IRIS, Movies/IRIS, Recordings|Music/IRIS) and falls
 * back to the newest item of that type if the folder query finds nothing.
 */
public final class RecentMedia {
    private RecentMedia() { }

    public enum Kind { IMAGE, VIDEO, AUDIO }

    public static final class Item {
        public final Uri uri;
        public final String name;
        public final String mime;
        public final long added;
        Item(Uri uri, String name, String mime, long added) {
            this.uri = uri; this.name = name; this.mime = mime; this.added = added;
        }
    }

    /** Newest matching item, preferring files IRIS created. Null when nothing is found. */
    public static Item newest(Context ctx, Kind kind) {
        Item mine = query(ctx, kind, true);
        return mine != null ? mine : query(ctx, kind, false);
    }

    private static Item query(Context ctx, Kind kind, boolean irisOnly) {
        Uri base;
        String mimeCol, nameCol, addedCol;
        switch (kind) {
            case VIDEO:
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                mimeCol = MediaStore.Video.Media.MIME_TYPE;
                nameCol = MediaStore.Video.Media.DISPLAY_NAME;
                addedCol = MediaStore.Video.Media.DATE_ADDED;
                break;
            case AUDIO:
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                mimeCol = MediaStore.Audio.Media.MIME_TYPE;
                nameCol = MediaStore.Audio.Media.DISPLAY_NAME;
                addedCol = MediaStore.Audio.Media.DATE_ADDED;
                break;
            default:
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                mimeCol = MediaStore.Images.Media.MIME_TYPE;
                nameCol = MediaStore.Images.Media.DISPLAY_NAME;
                addedCol = MediaStore.Images.Media.DATE_ADDED;
        }
        String[] cols = { MediaStore.MediaColumns._ID, nameCol, mimeCol, addedCol };
        String sel = null;
        String[] args = null;
        if (irisOnly) {
            // Match either the IRIS relative path or our IRIS_ filename prefix.
            sel = nameCol + " LIKE ?";
            args = new String[]{ "IRIS%" };
        }
        try (Cursor c = ctx.getContentResolver().query(base, cols, sel, args, addedCol + " DESC")) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                String name = safe(c, nameCol);
                String mime = safe(c, mimeCol);
                long added = c.getLong(c.getColumnIndexOrThrow(addedCol));
                return new Item(Uri.withAppendedPath(base, String.valueOf(id)), name,
                        mime.isEmpty() ? defaultMime(kind) : mime, added);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String defaultMime(Kind kind) {
        switch (kind) {
            case VIDEO: return "video/*";
            case AUDIO: return "audio/*";
            default: return "image/*";
        }
    }

    private static String safe(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    /** Map spoken words to a media kind, or null when the phrase names no media. */
    public static Kind kindFrom(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("screenshot") || t.contains("screen shot") || t.contains("photo")
                || t.contains("picture") || t.contains("image")) return Kind.IMAGE;
        if (t.contains("video") || t.contains("clip") || t.contains("recording of the screen")
                || t.contains("screen recording")) return Kind.VIDEO;
        if (t.contains("voice") || t.contains("memo") || t.contains("audio")) return Kind.AUDIO;
        return null;
    }
}
