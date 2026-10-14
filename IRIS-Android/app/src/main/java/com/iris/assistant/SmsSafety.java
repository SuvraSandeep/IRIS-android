package com.iris.assistant;

import java.util.Locale;

/**
 * Decides when an SMS must NEVER be auto-replied to. Pure and offline-testable.
 *
 * Two cases, both safety-critical:
 *
 *   1. One-time codes / 2FA / verification messages. Auto-replying leaks nothing useful and, worse,
 *      confirms to a bank gateway that the number is live and unattended. Detected by content, so
 *      it applies even to a saved sender who somehow forwards a code.
 *   2. Alphanumeric sender IDs / shortcodes (VM-HDFCBK, AX-SBIINB, AMAZON). On the carrier SMS
 *      channel a real person is always a plain phone number; an UNSAVED sender that contains
 *      letters is a brand/shortcode, never a person, so it must not be answered.
 *
 * Only ever applies to the SMS channel. Chat apps (WhatsApp, Instagram, …) are unaffected, and the
 * urgent path is untouched. This is additive: it can only ADD silence, never cause a send.
 */
public final class SmsSafety {

    private SmsSafety() { }

    /** Markers of a one-time-code / verification / 2FA message. Matched on the lower-cased body. */
    private static final String[] CODE_MARKERS = {
            "otp", "one time password", "one-time password", "verification code",
            "verification pin", "security code", "login code", "login otp", "is your otp",
            "otp is", "your code is", "code is", "2fa", "two factor", "two-factor", "passcode",
            "pin is", "auth code", "authentication code", "confirmation code", "do not share",
            "don't share", "dont share", "never share this", "never share your", "valid for",
            "otp for", "use this code", "enter this code"};

    /**
     * Whether IRIS must stay silent on this SMS.
     *
     * @param app          the messaging channel; non-SMS always returns false
     * @param sender       the raw sender address/ID as it arrived
     * @param body         the message text
     * @param savedContact whether the sender is a saved contact (gates the shortcode rule only)
     */
    public static boolean blocksAutoReply(MessagingApp app, String sender, String body,
                                          boolean savedContact) {
        if (app == null || !app.isSms()) return false;
        if (isCodeMessage(body)) return true;
        if (!savedContact && isShortcodeSender(sender)) return true;
        return false;
    }

    /** A one-line reason for the decision log, or empty when not blocked. */
    public static String reason(MessagingApp app, String sender, String body,
                                boolean savedContact) {
        if (app == null || !app.isSms()) return "";
        if (isCodeMessage(body)) return "a one-time code or verification SMS \u2014 never answered";
        if (!savedContact && isShortcodeSender(sender)) {
            return "an SMS sender ID / shortcode, not a person \u2014 never answered";
        }
        return "";
    }

    /** True if the body reads like a one-time code / verification / 2FA message. */
    public static boolean isCodeMessage(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : CODE_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * True if the sender is an alphanumeric sender ID / shortcode rather than a phone number.
     *
     * On SMS a real person's address is a phone number (digits, +, spaces, dashes). Any ASCII
     * letter in the sender means it is a brand/sender-ID like "VM-HDFCBK" or "AMAZON". The caller
     * gates this on the sender being UNSAVED, so a saved contact whose name the app put in the
     * sender field is never caught here.
     */
    public static boolean isShortcodeSender(String sender) {
        if (sender == null) return false;
        for (int i = 0; i < sender.length(); i++) {
            char c = sender.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }
}
