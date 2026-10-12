package com.iris.assistant;

/**
 * Offline checks for {@link ArchiveAppTargets}: which apps the archive targets, and how an account
 * is labelled.
 *
 * The pre-tick rule carries the most weight. Archiving an app the owner never chose would mean
 * silently storing other people's private messages, so an exact-match-only rule is pinned here
 * against anything looser creeping in later.
 */
public class ArchiveAppTargetsTest {

    private static int checks = 0;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(String actual, String expected, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        preTicksOnlyExactPrimaryPackages();
        recognisesMessagingCandidates();
        ignoresUnrelatedApps();
        identifiesClonesButNotSeparateProducts();
        labelsPrimaryAccountsPlainly();
        labelsWorkProfileFromUserId();
        labelsCloneAsSecondSpace();
        appendsAccountHintWhenPresent();
        handlesMissingLabelsAndNulls();
        defaultTargetsAreIsolated();
        System.out.println("Passed " + checks + " archive app target checks");
    }

    /**
     * Exact match only. A loose rule here would start archiving private messages from an app the
     * owner never ticked, which is not an acceptable default for this kind of store.
     */
    private static void preTicksOnlyExactPrimaryPackages() {
        check(ArchiveAppTargets.shouldPreTick("com.whatsapp"), "WhatsApp is pre-ticked");
        check(ArchiveAppTargets.shouldPreTick("com.whatsapp.w4b"), "WhatsApp Business is pre-ticked");
        check(ArchiveAppTargets.shouldPreTick("com.instagram.android"), "Instagram is pre-ticked");

        check(!ArchiveAppTargets.shouldPreTick("com.whatsapp.fake"), "a lookalike package is not pre-ticked");
        check(!ArchiveAppTargets.shouldPreTick("com.parallel.space.lite.whatsapp"),
                "a clone wrapper is not pre-ticked, since the owner never chose it");
        check(!ArchiveAppTargets.shouldPreTick("com.telegram.messenger"),
                "other messengers are offered but not pre-ticked");
        check(!ArchiveAppTargets.shouldPreTick(null), "a null package is not pre-ticked");
        check(!ArchiveAppTargets.shouldPreTick(""), "an empty package is not pre-ticked");
    }

    private static void recognisesMessagingCandidates() {
        check(ArchiveAppTargets.isMessagingCandidate("com.whatsapp", "WhatsApp"), "WhatsApp");
        check(ArchiveAppTargets.isMessagingCandidate("com.instagram.android", "Instagram"), "Instagram");
        check(ArchiveAppTargets.isMessagingCandidate("org.telegram.messenger", "Telegram"), "Telegram");
        check(ArchiveAppTargets.isMessagingCandidate("org.thoughtcrime.securesms", "Signal"),
                "Signal, matched on the sms fragment");
        check(ArchiveAppTargets.isMessagingCandidate("com.google.android.apps.messaging", "Messages"),
                "Google Messages");
        check(ArchiveAppTargets.isMessagingCandidate("com.facebook.orca", "Messenger"), "Messenger");
        // An unknown package whose label gives it away.
        check(ArchiveAppTargets.isMessagingCandidate("com.unknown.xyz", "Secret Chat"),
                "an unknown package with a chat label is surfaced");
    }

    private static void ignoresUnrelatedApps() {
        check(!ArchiveAppTargets.isMessagingCandidate("com.android.settings", "Settings"), "Settings");
        check(!ArchiveAppTargets.isMessagingCandidate("com.spotify.music", "Spotify"), "Spotify");
        check(!ArchiveAppTargets.isMessagingCandidate(null, null), "null inputs are not candidates");
        check(!ArchiveAppTargets.isMessagingCandidate("", ""), "empty inputs are not candidates");
    }

    /** WhatsApp Business is a separate product, not a clone; labelling it "second space" is wrong. */
    private static void identifiesClonesButNotSeparateProducts() {
        check(ArchiveAppTargets.looksLikeClone("com.parallel.space.lite.whatsapp"), "parallel space");
        check(ArchiveAppTargets.looksLikeClone("com.lbe.parallel.intl"), "LBE parallel");
        check(ArchiveAppTargets.looksLikeClone("com.ludashi.dualspace"), "dual space");
        check(ArchiveAppTargets.looksLikeClone("com.whatsapp.clone"), "a whatsapp wrapper");

        check(!ArchiveAppTargets.looksLikeClone("com.whatsapp"), "real WhatsApp is not a clone");
        check(!ArchiveAppTargets.looksLikeClone("com.whatsapp.w4b"),
                "WhatsApp Business is a separate product, not a clone");
        check(!ArchiveAppTargets.looksLikeClone("com.instagram.android"), "real Instagram is not a clone");
        check(!ArchiveAppTargets.looksLikeClone("com.spotify.music"), "an unrelated app is not a clone");
        check(!ArchiveAppTargets.looksLikeClone(null), "null is not a clone");
    }

    private static void labelsPrimaryAccountsPlainly() {
        equal(ArchiveAppTargets.accountLabel("com.whatsapp", "WhatsApp", 0, null),
                "WhatsApp", "a primary account carries no qualifier");
        equal(ArchiveAppTargets.accountLabel("com.instagram.android", "Instagram", 0, ""),
                "Instagram", "an empty hint adds nothing");
    }

    /**
     * The case package names cannot express: a work-profile app has the identical package name, so
     * only the Android user id separates it from the personal one.
     */
    private static void labelsWorkProfileFromUserId() {
        equal(ArchiveAppTargets.accountLabel("com.whatsapp", "WhatsApp", 10, null),
                "WhatsApp \u00B7 work profile", "a non-zero user id means a work profile");
        // The user id wins over clone detection, being the more reliable signal.
        equal(ArchiveAppTargets.accountLabel("com.parallel.space.lite.whatsapp", "WhatsApp", 10, null),
                "WhatsApp \u00B7 work profile", "the user id takes precedence over a clone package name");
    }

    private static void labelsCloneAsSecondSpace() {
        equal(ArchiveAppTargets.accountLabel("com.parallel.space.lite.whatsapp", "WhatsApp", 0, null),
                "WhatsApp \u00B7 second space", "a clone package is labelled as a second space");
    }

    /** An account hint is the only way to separate two profiles inside a single app. */
    private static void appendsAccountHintWhenPresent() {
        equal(ArchiveAppTargets.accountLabel("com.instagram.android", "Instagram", 0, "@second_acct"),
                "Instagram \u00B7 @second_acct", "an account hint is appended");
        equal(ArchiveAppTargets.accountLabel("com.whatsapp", "WhatsApp", 10, "Work"),
                "WhatsApp \u00B7 work profile \u00B7 Work", "a hint is appended after the qualifier");
        // A hint that merely repeats the app name adds nothing and is dropped.
        equal(ArchiveAppTargets.accountLabel("com.whatsapp", "WhatsApp", 0, "WhatsApp"),
                "WhatsApp", "a hint identical to the app name is not repeated");
    }

    private static void handlesMissingLabelsAndNulls() {
        equal(ArchiveAppTargets.accountLabel("com.unknown.app", null, 0, null),
                "com.unknown.app", "a missing label falls back to the package name");
        equal(ArchiveAppTargets.accountLabel("com.unknown.app", "   ", 0, null),
                "com.unknown.app", "a blank label falls back to the package name");
        equal(ArchiveAppTargets.accountLabel(null, null, 0, null),
                "Unknown app", "both missing yields a readable placeholder, not an empty string");
    }

    /** A caller must not be able to mutate the shared default list. */
    private static void defaultTargetsAreIsolated() {
        String[] first = ArchiveAppTargets.defaultTargets();
        check(first.length == 3, "three default targets");
        first[0] = "tampered";
        check(!ArchiveAppTargets.defaultTargets()[0].equals("tampered"),
                "mutating the returned array does not affect the defaults");
    }
}
