package com.iris.assistant;

/**
 * Offline checks for phone-number recognition used by contact name resolution.
 *
 * The risk being pinned here is a false positive. If a real name were classified as a number, the
 * archive would query the contacts provider with it and, on a match, overwrite a name the
 * messaging app had already supplied correctly. False negatives only mean a number stays
 * unresolved, which is the pre-existing behaviour and harmless.
 */
public class ContactNumberTest {

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
        realNumbersAreRecognised();
        prettyFormattingIsRecognised();
        namesAreNeverTreatedAsNumbers();
        edgeInputsFailSafe();
        lengthBoundsAreEnforced();
        normalisationCollapsesFormatting();
        normalisationKeepsTheCountryPlus();
        System.out.println("Passed " + checks + " contact number checks");
    }

    private static void realNumbersAreRecognised() {
        check(ContactNumber.looksLikePhoneNumber("+919876543210"), "international, no spaces");
        check(ContactNumber.looksLikePhoneNumber("9876543210"), "plain ten digits");
        check(ContactNumber.looksLikePhoneNumber("+1 555 0199"), "spaced international");
        check(ContactNumber.looksLikePhoneNumber("020 7946 0958"), "spaced local");
    }

    /**
     * Contact apps and notification titles group digits with assorted dashes and spaces. Rejecting
     * those would leave exactly the nicely formatted numbers unresolved.
     */
    private static void prettyFormattingIsRecognised() {
        check(ContactNumber.looksLikePhoneNumber("+91 98765-43210"), "hyphenated");
        check(ContactNumber.looksLikePhoneNumber("(020) 7946 0958"), "parenthesised area code");
        check(ContactNumber.looksLikePhoneNumber("555.0199.22"), "dot separated");
        check(ContactNumber.looksLikePhoneNumber("+91\u00a098765\u00a043210"),
                "non-breaking spaces are separators");
        check(ContactNumber.looksLikePhoneNumber("+91\u201198765\u201143210"),
                "non-breaking hyphens are separators");
    }

    /** The important direction: a name must never be sent to the contacts provider as a number. */
    private static void namesAreNeverTreatedAsNumbers() {
        check(!ContactNumber.looksLikePhoneNumber("Mum"), "a short name is not a number");
        check(!ContactNumber.looksLikePhoneNumber("Bob 2"), "a name with a digit is not a number");
        check(!ContactNumber.looksLikePhoneNumber("O2"), "a brand with a digit is not a number");
        check(!ContactNumber.looksLikePhoneNumber("WhatsApp Support"),
                "a multi-word name is not a number");
        check(!ContactNumber.looksLikePhoneNumber("Flat 12345"),
                "digits attached to a word are still a name");
        check(!ContactNumber.looksLikePhoneNumber("Group: 12345"),
                "a colon means this is not a dialled number");
        check(!ContactNumber.looksLikePhoneNumber("12345@s.whatsapp.net"),
                "a raw JID is not treated as a dialled number");
    }

    private static void edgeInputsFailSafe() {
        check(!ContactNumber.looksLikePhoneNumber(null), "null is not a number");
        check(!ContactNumber.looksLikePhoneNumber(""), "empty is not a number");
        check(!ContactNumber.looksLikePhoneNumber("   "), "whitespace is not a number");
        check(!ContactNumber.looksLikePhoneNumber("+"), "a lone plus is not a number");
        check(!ContactNumber.looksLikePhoneNumber("--- --"), "separators alone are not a number");
        equal(ContactNumber.normalise(null), "", "null normalises to empty, not a crash");
    }

    private static void lengthBoundsAreEnforced() {
        check(!ContactNumber.looksLikePhoneNumber("1234"),
                "four digits is too short to be a number");
        check(ContactNumber.looksLikePhoneNumber("12345"), "five digits is the shortest accepted");
        check(ContactNumber.looksLikePhoneNumber("12345678901234567"),
                "seventeen digits is the longest accepted");
        check(!ContactNumber.looksLikePhoneNumber("123456789012345678"),
                "eighteen digits is beyond any real number, so not treated as one");
    }

    private static void normalisationCollapsesFormatting() {
        String canonical = ContactNumber.normalise("+91 98765-43210");
        equal(canonical, "+919876543210", "formatting is stripped");
        equal(ContactNumber.normalise("+91\u00a098765\u201143210"), canonical,
                "exotic separators normalise to the same key as plain ones");
        equal(ContactNumber.normalise("(020) 7946 0958"), "02079460958",
                "brackets and spaces are stripped");
        equal(ContactNumber.normalise("  9876543210  "), "9876543210", "outer spaces are trimmed");
    }

    /**
     * The plus must survive: it is what separates an international number from a local one, and
     * PhoneLookup matches more reliably when it is present.
     */
    private static void normalisationKeepsTheCountryPlus() {
        check(ContactNumber.normalise("+919876543210").startsWith("+"), "leading plus is kept");
        check(!ContactNumber.normalise("919876543210").startsWith("+"),
                "no plus is not invented");
        check(!ContactNumber.normalise("98765+43210").startsWith("+"),
                "a plus in the middle is dropped rather than promoted to the front");
    }
}
