package com.iris.assistant;

/**
 * Which messaging channel a notification came through, worked out from its package name.
 *
 * Pure and offline-testable. The responder already had the package in hand; it simply never named
 * it, so to the owner a reply looked the same whether it went out over SMS or WhatsApp. This gives
 * every decision a channel, which the log can show and which later rules can switch on ("never
 * auto-reply to SMS", "SMS shortcodes are never people").
 *
 * SMS matters most to get right, because it behaves differently from the chat apps: shortcodes
 * carry letters, there are no group semantics the way WhatsApp has them, and a bank one-time-code
 * is the classic thing that must never be answered. Naming the channel is the first step to
 * treating it differently.
 */
public enum MessagingApp {

    SMS("SMS"),
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram"),
    SIGNAL("Signal"),
    INSTAGRAM("Instagram"),
    MESSENGER("Messenger"),
    OTHER("message");

    private final String label;

    MessagingApp(String label) { this.label = label; }

    public String label() { return label; }

    /** True for the carrier SMS/MMS channel, which the others are not. */
    public boolean isSms() { return this == SMS; }

    /**
     * Classify a package name.
     *
     * Matched on stable, well-known package substrings rather than an exact list, because every
     * manufacturer ships a different default SMS app (Samsung, Google, the OEM dialer) and an
     * exact list would miss most phones. The SMS set is deliberately broad for that reason; the
     * chat apps have single canonical packages.
     */
    public static MessagingApp fromPackage(String pkg) {
        if (pkg == null) return OTHER;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);

        // Chat apps first: their packages are canonical and must not be mistaken for SMS even
        // though some contain the word "messaging".
        if (p.equals("com.whatsapp") || p.equals("com.whatsapp.w4b")) return WHATSAPP;
        if (p.equals("org.telegram.messenger") || p.startsWith("org.telegram")) return TELEGRAM;
        if (p.equals("org.thoughtcrime.securesms")) return SIGNAL;
        if (p.equals("com.instagram.android")) return INSTAGRAM;
        if (p.equals("com.facebook.orca")) return MESSENGER;

        // SMS/MMS. The known defaults, then a cautious fallback on the "mms"/"messaging" shape
        // that OEM SMS apps share and the chat apps above have already been excluded from.
        if (p.equals("com.google.android.apps.messaging")
                || p.equals("com.samsung.android.messaging")
                || p.equals("com.android.mms")
                || p.equals("com.android.messaging")
                || p.contains(".mms")
                || p.endsWith(".messaging")) {
            return SMS;
        }
        return OTHER;
    }
}
