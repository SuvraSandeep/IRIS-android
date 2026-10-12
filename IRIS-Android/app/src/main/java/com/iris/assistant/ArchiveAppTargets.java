package com.iris.assistant;

import java.util.Locale;

/**
 * Which apps the archive targets, and how to label the account a notification arrived on.
 *
 * SCOPE: no Android imports, so the identification rules are offline-testable. The listener and
 * the app picker supply the package name, the app label, the Android user id and any account hint
 * from the notification extras; everything judgemental happens here.
 *
 * WHY LABELS ARE GENERIC RATHER THAN HARDCODED: clone and dual-app package names vary by OEM and by
 * which parallel-space wrapper is installed, and there is no reliable registry of them. Rather than
 * pretend to recognise a specific product, a non-primary variant is labelled by WHAT it is ("second
 * space", "work profile") and the owner can rename it. A hardcoded guess would be wrong on most
 * phones and would read as authoritative.
 *
 * THE CASE PACKAGE NAMES CANNOT SOLVE: a work-profile app has the IDENTICAL package name as the
 * personal one. Only the Android user id distinguishes them, which is why {@link #accountLabel}
 * takes it as a parameter. Two accounts inside ONE app (two Instagram profiles) share both package
 * and user id, and can only be told apart by an account hint in the notification extras, which apps
 * are not obliged to provide.
 */
public final class ArchiveAppTargets {

    private ArchiveAppTargets() { }

    /** Primary packages worth archiving out of the box. */
    public static final String WHATSAPP = "com.whatsapp";
    public static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    public static final String INSTAGRAM = "com.instagram.android";

    /** Pre-ticked on first run: the apps the owner named, plus WhatsApp Business as a near-certain. */
    private static final String[] DEFAULT_TARGETS = { WHATSAPP, WHATSAPP_BUSINESS, INSTAGRAM };

    /**
     * Package fragments used by parallel-space and dual-app wrappers. Matching is deliberately
     * loose because these products are numerous and rename themselves; a false "second space"
     * label is a cosmetic error, whereas missing one means the owner cannot tell two accounts
     * apart at all.
     */
    private static final String[] CLONE_MARKERS = {
            "parallel", "dualspace", "dual.space", "multiaccount", "multi.account",
            "cloneapp", "clone.app", "doubleapp", "dualapp", "二", "vmos", "virtualapp"
    };

    /** Fragments that identify a messaging app generically, for surfacing in the picker. */
    private static final String[] MESSAGING_MARKERS = {
            "whatsapp", "instagram", "telegram", "signal", "messenger", "facebook.orca",
            "snapchat", "discord", "slack", "skype", "viber", "line", "wechat", "threema",
            "google.android.apps.messaging", "samsung.android.messaging", "mms", "sms"
    };

    /** True for apps the picker should surface first, so the owner is not scrolling 300 entries. */
    public static boolean isMessagingCandidate(String pkg, String appLabel) {
        String p = lower(pkg);
        String l = lower(appLabel);
        if (p.isEmpty() && l.isEmpty()) return false;
        for (String marker : MESSAGING_MARKERS) {
            if (p.contains(marker)) return true;
        }
        // An app label can reveal a messaging app whose package name gives nothing away.
        return l.contains("message") || l.contains("chat") || l.contains("whatsapp")
                || l.contains("instagram") || l.contains("telegram");
    }

    /**
     * True for packages ticked automatically on first run.
     *
     * Only exact primary packages qualify. A loose match here would silently start archiving an
     * app the owner never chose, which for a store of other people's private messages is not an
     * acceptable default.
     */
    public static boolean shouldPreTick(String pkg) {
        if (pkg == null) return false;
        String p = pkg.trim();
        for (String target : DEFAULT_TARGETS) {
            if (target.equals(p)) return true;
        }
        return false;
    }

    public static String[] defaultTargets() {
        return DEFAULT_TARGETS.clone();
    }

    /**
     * True when the package looks like a cloned or parallel-space copy rather than the real app.
     *
     * WhatsApp Business is explicitly NOT a clone: it is a separate product with its own package,
     * and labelling it "second space" would be wrong.
     */
    public static boolean looksLikeClone(String pkg) {
        String p = lower(pkg);
        if (p.isEmpty()) return false;
        if (p.equals(WHATSAPP) || p.equals(WHATSAPP_BUSINESS) || p.equals(INSTAGRAM)) return false;
        for (String marker : CLONE_MARKERS) {
            if (p.contains(marker)) return true;
        }
        // A package that mentions a known app but is not that app is most likely a wrapper.
        return (p.contains("whatsapp") || p.contains("instagram"))
                && !p.equals(WHATSAPP) && !p.equals(WHATSAPP_BUSINESS) && !p.equals(INSTAGRAM);
    }

    /**
     * Human label for the account a notification arrived on.
     *
     * Order matters: the Android user id is checked first because it is the only reliable signal,
     * and it is the one case package names cannot express at all. A work-profile WhatsApp has the
     * same package name as the personal one, so without this the two would be indistinguishable in
     * the archive.
     *
     * @param userHandle  the Android user id; anything other than 0 is a secondary profile
     * @param accountHint an account name or handle from the notification extras, when present
     */
    public static String accountLabel(String pkg, String appLabel, int userHandle, String accountHint) {
        String base = appLabel == null || appLabel.trim().isEmpty()
                ? (pkg == null ? "Unknown app" : pkg.trim())
                : appLabel.trim();

        String qualifier = "";
        if (userHandle != 0) qualifier = "work profile";
        else if (looksLikeClone(pkg)) qualifier = "second space";

        StringBuilder label = new StringBuilder(base);
        if (!qualifier.isEmpty()) label.append(" \u00B7 ").append(qualifier);

        // An account hint is the only way to separate two profiles inside one app, so it is kept
        // whenever an app bothers to provide it.
        if (accountHint != null && !accountHint.trim().isEmpty()) {
            String hint = accountHint.trim();
            if (!hint.equalsIgnoreCase(base)) label.append(" \u00B7 ").append(hint);
        }
        return label.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
