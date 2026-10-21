package com.iris.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Copies an image out of a notification and into the archive.
 *
 * The copy has to happen promptly rather than storing the URI for later. An on-device probe showed
 * WhatsApp attaches images as content URIs into its own provider
 * (content://com.whatsapp.provider.media/item/...), and the read permission that arrives with a
 * notification is narrow and short-lived: once the notification is dismissed, or WhatsApp removes
 * its own copy, the URI is dead. Storing the link rather than the bytes would mean the archive
 * appeared to hold a photo and then could not produce it -- which for deleted messages is the
 * exact moment it matters most.
 *
 * Sizing and budget decisions live in ArchiveImagePolicy and are covered offline. This class only
 * performs the IO.
 */
public final class ArchiveImages {

    private static final int CACHE_SIZE_KB;
    static {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        // Use up to 1/8th of available runtime memory, safely bounded between 4MB and 32MB
        CACHE_SIZE_KB = Math.max(4096, Math.min(32768, maxMemoryKb / 8));
    }

    /** In-memory thumbnail cache to prevent duplicate disk reads and decodes. */
    private static final LruCache<String, Bitmap> THUMB_CACHE =
            new LruCache<String, Bitmap>(CACHE_SIZE_KB) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap != null ? Math.max(1, bitmap.getByteCount() / 1024) : 1;
                }
            };

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** Background worker pool for off-main-thread image decoding. */
    private static final ExecutorService DECODER_POOL =
            Executors.newFixedThreadPool(2, new ThreadFactory() {
                private int count = 1;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "iris-archive-img-" + (count++));
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    public interface ThumbnailCallback {
        void onLoaded(Bitmap bitmap);
    }

    private ArchiveImages() { }

    /** The directory holding archived images, created on demand. */
    public static File directory(Context context) {
        File dir = new File(context.getFilesDir(), ArchiveSchema.IMAGE_DIR);
        if (!dir.exists()) {
            // Result ignored deliberately: a failure here surfaces as the copy failing, which is
            // handled, rather than as an exception on the notification path.
            dir.mkdirs();
        }
        return dir;
    }

    /** Absolute file for a stored image name, or null when the name is empty. */
    public static File fileFor(Context context, String storedName) {
        if (context == null || storedName == null || storedName.trim().isEmpty()) return null;
        // Re-derived through the policy so a value read back from the database cannot contain a
        // path separator and reach outside the archive directory.
        String safe = ArchiveImagePolicy.fileName(storedName.replace(".jpg", ""));
        if (safe.isEmpty()) return null;
        return new File(directory(context), safe);
    }

    /** Total bytes currently held in stored images. */
    public static long bytesUsed(Context context) {
        try {
            File[] files = directory(context).listFiles();
            if (files == null) return 0;
            long total = 0;
            for (File file : files) total += file.length();
            return total;
        } catch (Throwable ignored) {
            // Reported as -1 so the policy refuses further images rather than assuming space is
            // free when the measurement failed.
            return -1;
        }
    }

    /**
     * Copy, downscale and store one image.
     *
     * @return the stored filename, or empty if nothing was stored
     */
    public static String store(Context context, Uri source, String mimeType, String fingerprint) {
        if (context == null || source == null) return "";
        if (!ArchiveImagePolicy.isStorableImage(mimeType)) return "";

        String name = ArchiveImagePolicy.fileName(fingerprint);
        if (name.isEmpty()) return "";

        if (!ArchiveImagePolicy.allowsImage(
                bytesUsed(context), 0, ArchiveSchema.MAX_BYTES)) {
            return "";
        }

        File target = new File(directory(context), name);
        // Already stored, which happens when the same notification is seen again. Treated as
        // success so the caller records the path rather than retrying the decode.
        if (target.exists() && target.length() > 0) return name;

        Bitmap bitmap = decodeScaled(context, source);
        if (bitmap == null) return "";
        try (OutputStream out = new FileOutputStream(target)) {
            boolean ok = bitmap.compress(
                    Bitmap.CompressFormat.JPEG, ArchiveImagePolicy.JPEG_QUALITY, out);
            if (!ok) {
                target.delete();
                return "";
            }
            return name;
        } catch (Throwable ignored) {
            // A partial file would look like a valid image and then fail to display.
            try { target.delete(); } catch (Throwable alsoIgnored) { }
            return "";
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Decode at a reduced size.
     *
     * Dimensions are read first with inJustDecodeBounds, so a large camera photo is never fully
     * loaded. Decoding one at full size in the notification listener process would risk an
     * out-of-memory kill, which would take notification capture down with it.
     */
    private static Bitmap decodeScaled(Context context, Uri source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Throwable ignored) {
            return null;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = ArchiveImagePolicy.sampleSize(
                bounds.outWidth, bounds.outHeight, ArchiveImagePolicy.MAX_DIMENSION);
        // The stream cannot be rewound, so it is opened a second time for the real decode.
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Look up a decoded thumbnail from the in-memory LruCache without touching disk.
     * Returns null if not cached or already recycled.
     */
    public static Bitmap cachedThumbnail(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return null;
        Bitmap cached = THUMB_CACHE.get(storedName);
        if (cached != null && !cached.isRecycled()) return cached;
        return null;
    }

    /**
     * Decode a stored image small enough for a list row, caching the result in LruCache.
     *
     * Thumbnail-sized rather than full-size so memory is conserved. Returns null when the file
     * is missing, so the caller can say so instead of leaving a blank gap.
     */
    public static Bitmap thumbnail(Context context, String storedName, int targetPx) {
        if (context == null || storedName == null || storedName.trim().isEmpty()) return null;
        Bitmap cached = cachedThumbnail(storedName);
        if (cached != null) return cached;

        File file = fileFor(context, storedName);
        if (file == null || !file.exists() || file.length() == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = ArchiveImagePolicy.sampleSize(
                    bounds.outWidth, bounds.outHeight, Math.max(1, targetPx));
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (decoded != null) {
                THUMB_CACHE.put(storedName, decoded);
            }
            return decoded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Offload thumbnail decoding to a background worker pool and cache in LruCache.
     * The callback is always invoked on the main UI thread.
     */
    public static void loadThumbnailAsync(Context context, String storedName, int targetPx,
                                          ThumbnailCallback callback) {
        if (context == null || storedName == null || storedName.trim().isEmpty()) {
            if (callback != null) MAIN_HANDLER.post(() -> callback.onLoaded(null));
            return;
        }

        Bitmap cached = cachedThumbnail(storedName);
        if (cached != null) {
            if (callback != null) callback.onLoaded(cached);
            return;
        }

        Context appCtx = context.getApplicationContext();
        DECODER_POOL.execute(() -> {
            Bitmap decoded = thumbnail(appCtx, storedName, targetPx);
            if (callback != null) {
                MAIN_HANDLER.post(() -> callback.onLoaded(decoded));
            }
        });
    }

    /** Clear the in-memory thumbnail cache. */
    public static void clearCache() {
        THUMB_CACHE.evictAll();
    }

    /** Decode a stored image at full stored size, for the single-image viewer. */
    public static Bitmap full(Context context, String storedName) {
        File file = fileFor(context, storedName);
        if (file == null || !file.exists() || file.length() == 0) return null;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Throwable ignored) {
            // An OutOfMemoryError is a Throwable, not an Exception, and is a realistic outcome
            // here, so it is caught rather than allowed to take the activity down.
            return null;
        }
    }

    /**
     * Copy one stored image to a location the owner chose.
     *
     * The export stays text-only by the owner's decision, so this is the single-image escape
     * hatch: a photo leaves the archive only when explicitly saved.
     */
    public static boolean copyTo(Context context, String storedName, OutputStream destination) {
        File file = fileFor(context, storedName);
        if (file == null || !file.exists() || destination == null) return false;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) destination.write(buffer, 0, read);
            destination.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Remove one stored image. Safe to call when there is none. */
    public static void delete(Context context, String storedName) {
        if (storedName != null && !storedName.trim().isEmpty()) {
            THUMB_CACHE.remove(storedName);
        }
        File file = fileFor(context, storedName);
        if (file == null) return;
        try { file.delete(); } catch (Throwable ignored) { }
    }

    /**
     * Remove every stored image.
     *
     * Called when the archive is wiped. Without it the image files would survive a wipe and
     * silently occupy storage forever, with no row left pointing at them.
     */
    public static int deleteAll(Context context) {
        clearCache();
        int removed = 0;
        try {
            File[] files = directory(context).listFiles();
            if (files == null) return 0;
            for (File file : files) {
                if (file.delete()) removed++;
            }
        } catch (Throwable ignored) { }
        return removed;
    }

    /**
     * Delete image files that no longer have a row pointing at them.
     *
     * A row insert can succeed while the later path update fails, or a delete can remove a row
     * before its file. Either leaves an orphan that nothing will ever clean up, so the Vault
     * sweeps on open.
     */
    public static int deleteOrphans(Context context, java.util.Set<String> keepNames) {
        int removed = 0;
        try {
            File[] files = directory(context).listFiles();
            if (files == null) return 0;
            for (File file : files) {
                if (keepNames.contains(file.getName())) continue;
                THUMB_CACHE.remove(file.getName());
                if (file.delete()) removed++;
            }
        } catch (Throwable ignored) { }
        return removed;
    }
}
