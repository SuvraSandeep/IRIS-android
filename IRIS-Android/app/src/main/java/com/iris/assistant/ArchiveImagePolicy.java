package com.iris.assistant;

/**
 * Sizing and budget rules for captured images.
 *
 * Pure, with no Android imports, so the arithmetic that decides whether to spend storage and how
 * far to shrink a photo is covered offline. The Android half only does the decoding and writing.
 *
 * Images change the archive's storage behaviour completely. Text rows are tiny -- a 1GB ceiling
 * holds millions of them -- whereas a handful of modern phone photos is tens of megabytes. Left
 * unchecked, images would fill the archive and stop text capture, which would be a bad trade: the
 * text of a deleted message is the thing the owner actually asked to preserve, and a photo is a
 * bonus. Hence a separate, smaller budget for images inside the overall ceiling, so running out of
 * image space can never stop a message being saved.
 */
public final class ArchiveImagePolicy {

    /** Longest edge kept, in pixels. Enough to read a screenshot; far smaller than the original. */
    public static final int MAX_DIMENSION = 1280;

    /** JPEG quality for the stored copy. */
    public static final int JPEG_QUALITY = 80;

    /** Refuse a single source image larger than this, before decoding it. */
    public static final long MAX_SOURCE_BYTES = 24L * 1024 * 1024;

    /**
     * Share of the overall archive ceiling images may occupy.
     *
     * Half, so that even a vault stuffed with photos leaves room for a very large number of text
     * rows. Images yield before messages do, never the other way round.
     */
    public static final double IMAGE_BUDGET_FRACTION = 0.5;

    private ArchiveImagePolicy() { }

    /** Bytes available to images in total. */
    public static long imageBudgetBytes(long ceilingBytes) {
        if (ceilingBytes <= 0) return 0;
        return (long) (ceilingBytes * IMAGE_BUDGET_FRACTION);
    }

    /**
     * Whether another image may be stored.
     *
     * @param imageBytesUsed bytes already spent on stored images
     * @param sourceBytes    size of the candidate, or 0 when unknown
     * @param ceilingBytes   the archive's overall ceiling
     */
    public static boolean allowsImage(long imageBytesUsed, long sourceBytes, long ceilingBytes) {
        if (ceilingBytes <= 0) return false;
        if (sourceBytes > MAX_SOURCE_BYTES) return false;
        // A negative used figure means the measurement failed. Refusing is the safe answer here,
        // the opposite of the text-capture rule: failing to store a photo loses a bonus, whereas
        // filling the disk on a bad measurement would break the whole archive.
        if (imageBytesUsed < 0) return false;
        return imageBytesUsed < imageBudgetBytes(ceilingBytes);
    }

    /**
     * Power-of-two decode divisor that brings the longest edge under MAX_DIMENSION.
     *
     * Powers of two because that is what BitmapFactory's inSampleSize honours exactly; other
     * values are rounded down to one anyway. Computed before decoding so a large photo is never
     * fully loaded into memory, which is the usual cause of an out-of-memory crash when handling
     * camera images.
     */
    public static int sampleSize(int width, int height, int maxDimension) {
        if (width <= 0 || height <= 0) return 1;
        if (maxDimension <= 0) return 1;
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= maxDimension) {
            sample *= 2;
            // Guards against a pathological dimension producing an enormous divisor.
            if (sample >= 64) break;
        }
        return sample;
    }

    /** File extension for the stored media according to MIME type. */
    public static String extensionFor(String mimeType) {
        if (mimeType == null) return ".jpg";
        String lower = mimeType.split(";")[0].trim().toLowerCase(java.util.Locale.US);
        if ("video/mp4".equals(lower)) return ".mp4";
        if ("video/3gpp".equals(lower) || "video/3gp".equals(lower)) return ".3gp";
        if ("audio/ogg".equals(lower) || "audio/opus".equals(lower)) return ".ogg";
        if ("audio/mp4".equals(lower) || "audio/m4a".equals(lower)) return ".m4a";
        if ("audio/aac".equals(lower)) return ".aac";
        if ("audio/amr".equals(lower)) return ".amr";
        if ("audio/mpeg".equals(lower) || "audio/mp3".equals(lower)) return ".mp3";
        return ".jpg";
    }

    /** Stored filename for a row's image. Derived from the fingerprint, so it is already unique. */
    public static String fileName(String fingerprint) {
        return fileName(fingerprint, "image/jpeg");
    }

    /** Stored filename for a row's media. Derived from the fingerprint with appropriate extension. */
    public static String fileName(String fingerprint, String mimeType) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) return "";
        String safe = fingerprint.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) return "";
        // Trimmed because a fingerprint is a full SHA-256 hex string and the leading half is
        // already far beyond collision risk for one device's archive.
        if (safe.length() > 32) safe = safe.substring(0, 32);
        return safe + extensionFor(mimeType);
    }

    /** Whether this MIME type is an image worth trying to store. */
    public static boolean isStorableImage(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.trim().toLowerCase(java.util.Locale.US);
        if (!lower.startsWith("image/")) return false;
        // Animations would be stored as a single frame, which is misleading rather than useful,
        // so they are skipped instead of silently flattened.
        return !lower.contains("gif");
    }

    /** Whether this MIME type is a video worth storing. */
    public static boolean isStorableVideo(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.trim().toLowerCase(java.util.Locale.US);
        return "video/mp4".equals(lower) || "video/3gpp".equals(lower) || "video/3gp".equals(lower);
    }

    /** Whether this MIME type is a voice note or audio recording worth storing. */
    public static boolean isStorableAudio(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.split(";")[0].trim().toLowerCase(java.util.Locale.US);
        return "audio/ogg".equals(lower)
                || "audio/opus".equals(lower)
                || "audio/mp4".equals(lower)
                || "audio/m4a".equals(lower)
                || "audio/aac".equals(lower)
                || "audio/amr".equals(lower)
                || "audio/mpeg".equals(lower)
                || "audio/mp3".equals(lower);
    }

    /** Whether this MIME type is storable media (still image, supported video, or voice note). */
    public static boolean isStorableMedia(String mimeType) {
        return isStorableImage(mimeType) || isStorableVideo(mimeType) || isStorableAudio(mimeType);
    }

    /** Alias for allowsImage covering all media types (photos, videos, and voice notes). */
    public static boolean allowsMedia(long mediaBytesUsed, long sourceBytes, long ceilingBytes) {
        return allowsImage(mediaBytesUsed, sourceBytes, ceilingBytes);
    }
}
