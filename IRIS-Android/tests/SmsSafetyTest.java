package com.iris.assistant;

/** Offline checks for the SMS OTP / shortcode safety classifier. */
public final class SmsSafetyTest {

    private static int checks;

    private static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        MessagingApp sms = MessagingApp.SMS;
        MessagingApp wa = MessagingApp.WHATSAPP;

        // One-time codes are never answered, even from a saved sender.
        check(SmsSafety.blocksAutoReply(sms, "VM-HDFCBK",
                "Your OTP is 445566. Do not share it with anyone.", false),
                "OTP SMS from a shortcode is blocked");
        check(SmsSafety.blocksAutoReply(sms, "Mom", "Your login code is 1234", true),
                "a verification code is blocked even from a saved contact");
        check(SmsSafety.isCodeMessage("123456 is your verification code"),
                "verification code content detected");
        check(SmsSafety.isCodeMessage("Use this code to sign in: 9981"), "use-this-code detected");
        check(!SmsSafety.isCodeMessage("lets meet at 6 near the cafe"), "ordinary text is not a code");

        // Unsaved alphanumeric sender IDs / shortcodes are never answered.
        check(SmsSafety.blocksAutoReply(sms, "AX-SBIINB", "Your account statement is ready", false),
                "unsaved shortcode sender is blocked");
        check(SmsSafety.blocksAutoReply(sms, "AMAZON", "Your order has shipped", false),
                "unsaved brand sender id is blocked");
        check(SmsSafety.isShortcodeSender("VM-HDFCBK"), "lettered sender id is a shortcode");
        check(SmsSafety.isShortcodeSender("AMAZON"), "all-letters sender id is a shortcode");
        check(!SmsSafety.isShortcodeSender("+919812345678"), "a phone number is not a shortcode");
        check(!SmsSafety.isShortcodeSender("9812345678"), "a bare number is not a shortcode");

        // A real unsaved person (plain number, ordinary text) is NOT blocked by this layer.
        check(!SmsSafety.blocksAutoReply(sms, "+919812345678", "hey are you free this evening",
                false), "an ordinary SMS from a plain number is not blocked");
        // A saved contact with ordinary text is not blocked.
        check(!SmsSafety.blocksAutoReply(sms, "Mom", "call me when you are free", true),
                "a saved contact's ordinary SMS is not blocked");

        // Only the SMS channel is affected.
        check(!SmsSafety.blocksAutoReply(wa, "AX-SBIINB", "Your OTP is 1234", false),
                "WhatsApp is never affected by the SMS safety rule");
        check(!SmsSafety.blocksAutoReply(null, "AMAZON", "Your OTP is 1234", false),
                "a null channel is not blocked");

        System.out.println("Passed " + checks + " SMS safety checks");
    }
}
