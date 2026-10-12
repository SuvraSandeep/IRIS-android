package com.iris.assistant;

/**
 * Offline checks for the image-capture verdict.
 *
 * These pin the mistake that was actually made: concluding "no images" from EXTRA_PICTURE alone,
 * while a MessagingStyle data URI -- the route a smartwatch uses -- went unexamined. They also pin
 * the opposite error, which would be worse: reporting "images available" because an avatar was
 * found, since nearly every message notification carries one.
 */
public class NotificationImageSupportTest {

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
        bitmapOutranksEverything();
        dataUriIsReportedSeparatelyFromReadability();
        avatarIsNeverMistakenForAnImage();
        nothingPresentMeansNone();
        onlyRealOptionsCountAsCapturable();
        everyVerdictExplainsItself();
        System.out.println("Passed " + checks + " notification image support checks");
    }

    /** A bitmap is already in memory, so nothing can fail between finding it and saving it. */
    private static void bitmapOutranksEverything() {
        equal(NotificationImageSupport.verdict(true, false, false, false),
                NotificationImageSupport.Verdict.BITMAP_AVAILABLE, "a bare bitmap is available");
        equal(NotificationImageSupport.verdict(true, true, false, true),
                NotificationImageSupport.Verdict.BITMAP_AVAILABLE,
                "a bitmap wins even alongside an unreadable uri and an avatar");
    }

    /**
     * The case the earlier conclusion missed entirely: no EXTRA_PICTURE, but an image attached to
     * a MessagingStyle message as a data URI.
     */
    private static void dataUriIsReportedSeparatelyFromReadability() {
        equal(NotificationImageSupport.verdict(false, true, true, false),
                NotificationImageSupport.Verdict.URI_ATTACHED,
                "a readable uri is available even with no EXTRA_PICTURE");
        equal(NotificationImageSupport.verdict(false, true, false, false),
                NotificationImageSupport.Verdict.URI_UNREADABLE,
                "an unreadable uri is reported as such, not as available");
        check(NotificationImageSupport.verdict(false, true, false, false)
                        != NotificationImageSupport.verdict(false, true, true, false),
                "readable and unreadable uris never collapse to the same verdict");
    }

    /**
     * The dangerous false positive. An avatar rides along with essentially every message, so
     * counting it would report success on every notification and be wrong every time.
     */
    private static void avatarIsNeverMistakenForAnImage() {
        equal(NotificationImageSupport.verdict(false, false, false, true),
                NotificationImageSupport.Verdict.AVATAR_ONLY, "an avatar alone is avatar-only");
        check(!NotificationImageSupport.canCapture(
                        NotificationImageSupport.Verdict.AVATAR_ONLY),
                "an avatar is not capturable content");
        // uriReadable true with no uri present must not promote an avatar to available.
        equal(NotificationImageSupport.verdict(false, false, true, true),
                NotificationImageSupport.Verdict.AVATAR_ONLY,
                "a readable flag with no uri does not invent an image");
    }

    private static void nothingPresentMeansNone() {
        equal(NotificationImageSupport.verdict(false, false, false, false),
                NotificationImageSupport.Verdict.NONE, "nothing present is NONE");
        equal(NotificationImageSupport.verdict(false, false, true, false),
                NotificationImageSupport.Verdict.NONE,
                "a readable flag on its own is meaningless");
    }

    private static void onlyRealOptionsCountAsCapturable() {
        check(NotificationImageSupport.canCapture(
                NotificationImageSupport.Verdict.BITMAP_AVAILABLE), "a bitmap is capturable");
        check(NotificationImageSupport.canCapture(
                NotificationImageSupport.Verdict.URI_ATTACHED), "a readable uri is capturable");
        check(!NotificationImageSupport.canCapture(
                        NotificationImageSupport.Verdict.URI_UNREADABLE),
                "an unreadable uri is NOT capturable, so it cannot promise a saved image");
        check(!NotificationImageSupport.canCapture(NotificationImageSupport.Verdict.NONE),
                "nothing is not capturable");
    }

    /** The report is the whole point of the inspector, so every verdict must say something. */
    private static void everyVerdictExplainsItself() {
        for (NotificationImageSupport.Verdict v : NotificationImageSupport.Verdict.values()) {
            String text = NotificationImageSupport.explain(v);
            check(text != null && text.length() > 20, "verdict " + v + " has a real explanation");
        }
        check(NotificationImageSupport.explain(null).length() > 0,
                "a null verdict still produces text rather than crashing the report");
        check(NotificationImageSupport.explain(NotificationImageSupport.Verdict.AVATAR_ONLY)
                        .contains("not"),
                "the avatar explanation explicitly says it is not what was sent");
    }
}
