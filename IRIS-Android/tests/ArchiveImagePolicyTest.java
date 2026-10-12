package com.iris.assistant;

/**
 * Offline checks for image sizing and budget.
 *
 * Two properties matter most here and are pinned deliberately.
 *
 * First, images must yield before messages do. A vault full of photos that stops a deleted message
 * being captured would invert the whole point of the feature, so the image budget is a fraction of
 * the ceiling rather than the whole of it.
 *
 * Second, the sample size must be computed from dimensions alone, before any decoding. Getting it
 * wrong means either loading a full camera photo into memory and crashing, or shrinking so far the
 * image is useless.
 */
public class ArchiveImagePolicyTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        imagesNeverConsumeTheWholeCeiling();
        budgetDecisionsAreCorrect();
        nonsenseInputRefusesRatherThanOverspends();
        sampleSizeKeepsImagesUnderTheLimit();
        sampleSizeIsAlwaysAUsablePowerOfTwo();
        fileNamesAreSafeAndUnique();
        onlyRealStillImagesAreStored();
        System.out.println("Passed " + checks + " archive image policy checks");
    }

    /** The core trade: a photo is a bonus, the text of a deleted message is the point. */
    private static void imagesNeverConsumeTheWholeCeiling() {
        long ceiling = 1024L * 1024 * 1024;
        long budget = ArchiveImagePolicy.imageBudgetBytes(ceiling);
        check(budget > 0, "images get a real budget");
        check(budget < ceiling, "images can never claim the entire ceiling");
        check(budget <= ceiling / 2, "the image budget is at most half the ceiling");
    }

    private static void budgetDecisionsAreCorrect() {
        long ceiling = 1000L;
        long budget = ArchiveImagePolicy.imageBudgetBytes(ceiling);
        check(ArchiveImagePolicy.allowsImage(0, 100, ceiling), "an empty archive accepts an image");
        check(ArchiveImagePolicy.allowsImage(budget - 1, 100, ceiling),
                "just under budget still accepts");
        check(!ArchiveImagePolicy.allowsImage(budget, 100, ceiling), "at budget it stops");
        check(!ArchiveImagePolicy.allowsImage(budget + 5000, 100, ceiling),
                "over budget it stops");
        check(!ArchiveImagePolicy.allowsImage(0, ArchiveImagePolicy.MAX_SOURCE_BYTES + 1, ceiling),
                "an absurdly large source is refused before decoding");
        check(ArchiveImagePolicy.allowsImage(0, 0, ceiling),
                "an unknown source size is still attempted");
    }

    /**
     * Note this is the OPPOSITE of the rule the text size policy follows, where bad input returns
     * OK so a failed measurement can never stop capture. Here a bad measurement must refuse:
     * losing a photo costs a bonus, while overspending on a wrong number could fill the disk.
     */
    private static void nonsenseInputRefusesRatherThanOverspends() {
        check(!ArchiveImagePolicy.allowsImage(-1, 100, 1000),
                "a negative used figure refuses rather than assuming space is free");
        check(!ArchiveImagePolicy.allowsImage(0, 100, 0), "a zero ceiling stores nothing");
        check(!ArchiveImagePolicy.allowsImage(0, 100, -500), "a negative ceiling stores nothing");
        equal(ArchiveImagePolicy.imageBudgetBytes(0), 0L, "a zero ceiling yields no budget");
        equal(ArchiveImagePolicy.imageBudgetBytes(-10), 0L, "a negative ceiling yields no budget");
    }

    private static void sampleSizeKeepsImagesUnderTheLimit() {
        int max = ArchiveImagePolicy.MAX_DIMENSION;
        int[][] realPhotoSizes = {
                {4000, 3000}, {3024, 4032}, {1920, 1080}, {2560, 1440}, {8000, 6000}
        };
        for (int[] size : realPhotoSizes) {
            int sample = ArchiveImagePolicy.sampleSize(size[0], size[1], max);
            int longest = Math.max(size[0], size[1]) / sample;
            check(longest < max * 2,
                    size[0] + "x" + size[1] + " shrinks to a sensible size (got " + longest + ")");
            check(longest > 0, size[0] + "x" + size[1] + " does not shrink to nothing");
        }
    }

    private static void sampleSizeIsAlwaysAUsablePowerOfTwo() {
        // Already small enough: no shrinking at all.
        equal(ArchiveImagePolicy.sampleSize(800, 600, 1280), 1,
                "an image already under the limit is not shrunk");
        equal(ArchiveImagePolicy.sampleSize(1280, 1280, 1280), 1,
                "an image exactly at the limit is not shrunk");

        for (int[] size : new int[][]{{4000, 3000}, {9999, 1}, {1, 9999}, {12000, 12000}}) {
            int sample = ArchiveImagePolicy.sampleSize(size[0], size[1], 1280);
            check(sample >= 1, "sample size is never zero or negative");
            check((sample & (sample - 1)) == 0, "sample size " + sample + " is a power of two");
            check(sample <= 64, "sample size is bounded so a silly dimension cannot run away");
        }

        // Degenerate input must not produce a divide-by-zero or a zero divisor.
        equal(ArchiveImagePolicy.sampleSize(0, 0, 1280), 1, "zero dimensions give a safe 1");
        equal(ArchiveImagePolicy.sampleSize(-5, 100, 1280), 1, "negative width gives a safe 1");
        equal(ArchiveImagePolicy.sampleSize(4000, 3000, 0), 1, "a zero limit gives a safe 1");
    }

    private static void fileNamesAreSafeAndUnique() {
        String a = ArchiveImagePolicy.fileName(
                "3f2a9c1b4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8");
        String b = ArchiveImagePolicy.fileName(
                "aaaa9c1b4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8");
        check(a.endsWith(".jpg"), "the stored name is a jpg");
        check(!a.equals(b), "different fingerprints give different names");
        check(a.length() <= 36, "the name is trimmed to a sane length");

        // Path traversal and separators must be impossible, since this is concatenated onto a
        // directory path. A fingerprint is machine-generated, but that is not a reason to trust it.
        for (String nasty : new String[]{"../../etc/passwd", "a/b/c", "a\\b", "..", "a:b"}) {
            String name = ArchiveImagePolicy.fileName(nasty);
            check(!name.contains("/") && !name.contains("\\") && !name.contains(".."),
                    "'" + nasty + "' cannot escape the archive directory (got '" + name + "')");
        }
        equal(ArchiveImagePolicy.fileName(null), "", "null yields no name");
        equal(ArchiveImagePolicy.fileName("  "), "", "blank yields no name");
        equal(ArchiveImagePolicy.fileName("///"), "", "pure separators yield no name");
    }

    private static void onlyRealStillImagesAreStored() {
        check(ArchiveImagePolicy.isStorableImage("image/jpeg"),
                "jpeg, which the on-device probe actually reported");
        check(ArchiveImagePolicy.isStorableImage("image/png"), "png");
        check(ArchiveImagePolicy.isStorableImage("IMAGE/JPEG"), "case is ignored");
        check(ArchiveImagePolicy.isStorableImage("image/webp"), "webp");
        check(!ArchiveImagePolicy.isStorableImage("image/gif"),
                "an animation is skipped rather than flattened to one misleading frame");
        check(!ArchiveImagePolicy.isStorableImage("video/mp4"), "video is not an image");
        check(!ArchiveImagePolicy.isStorableImage("audio/ogg"), "a voice note is not an image");
        check(!ArchiveImagePolicy.isStorableImage("text/plain"), "text is not an image");
        check(!ArchiveImagePolicy.isStorableImage(null), "null is not an image");
        check(!ArchiveImagePolicy.isStorableImage(""), "empty is not an image");
    }
}
