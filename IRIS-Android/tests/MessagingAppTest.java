package com.iris.assistant;

/** Offline checks for the messaging-channel classifier. */
public final class MessagingAppTest {

    private static int checks;

    private static void equal(Object actual, Object expected, String what) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected
                    + "\n  actual:   " + actual);
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        chatAppsAreNamedExactly();
        smsCoversTheCommonDefaults();
        smsIsNeverConfusedWithAChatApp();
        unknownsAndNullsFallToOther();
        System.out.println("Passed " + checks + " messaging-app checks");
    }

    private static void chatAppsAreNamedExactly() {
        equal(MessagingApp.fromPackage("com.whatsapp"), MessagingApp.WHATSAPP, "whatsapp");
        equal(MessagingApp.fromPackage("com.whatsapp.w4b"), MessagingApp.WHATSAPP,
                "whatsapp business");
        equal(MessagingApp.fromPackage("org.telegram.messenger"), MessagingApp.TELEGRAM,
                "telegram");
        equal(MessagingApp.fromPackage("org.thoughtcrime.securesms"), MessagingApp.SIGNAL,
                "signal");
        equal(MessagingApp.fromPackage("com.instagram.android"), MessagingApp.INSTAGRAM,
                "instagram");
        equal(MessagingApp.fromPackage("com.facebook.orca"), MessagingApp.MESSENGER,
                "messenger");
    }

    private static void smsCoversTheCommonDefaults() {
        equal(MessagingApp.fromPackage("com.google.android.apps.messaging"), MessagingApp.SMS,
                "google messages");
        equal(MessagingApp.fromPackage("com.samsung.android.messaging"), MessagingApp.SMS,
                "samsung messages");
        equal(MessagingApp.fromPackage("com.android.mms"), MessagingApp.SMS, "aosp mms");
        equal(MessagingApp.fromPackage("com.android.messaging"), MessagingApp.SMS,
                "aosp messaging");
        check(MessagingApp.fromPackage("com.google.android.apps.messaging").isSms(),
                "google messages reports as SMS");
        check(!MessagingApp.fromPackage("com.whatsapp").isSms(), "whatsapp is not SMS");
    }

    private static void smsIsNeverConfusedWithAChatApp() {
        // Signal's package is securesms, which contains "sms" and ends oddly; it must stay SIGNAL,
        // because the chat-app checks run first.
        equal(MessagingApp.fromPackage("org.thoughtcrime.securesms"), MessagingApp.SIGNAL,
                "securesms is Signal, not SMS, despite the name");
        // Telegram's package contains "messenger" but must not read as Messenger or SMS.
        equal(MessagingApp.fromPackage("org.telegram.messenger"), MessagingApp.TELEGRAM,
                "telegram.messenger is Telegram, not Messenger");
    }

    private static void unknownsAndNullsFallToOther() {
        equal(MessagingApp.fromPackage(null), MessagingApp.OTHER, "null is OTHER");
        equal(MessagingApp.fromPackage(""), MessagingApp.OTHER, "empty is OTHER");
        equal(MessagingApp.fromPackage("com.some.game"), MessagingApp.OTHER, "a game is OTHER");
        equal(MessagingApp.fromPackage("com.linkedin.android"), MessagingApp.OTHER,
                "linkedin is OTHER, not a known channel");
    }
}
