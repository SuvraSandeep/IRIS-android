package com.iris.assistant;

/**
 * Deciding what counts as a phone number, and flattening it to one canonical form.
 *
 * Split out from ContactResolver with no Android imports so these rules are covered offline. The
 * judgement here is the risky part: misclassifying a real name as a number would send the archive
 * off to the contacts provider and, if it matched something, replace a name the messaging app had
 * already given us correctly. The lookup itself is mechanical by comparison.
 */
public final class ContactNumber {

    /** Shortest run of digits worth treating as a number rather than a nickname like "A1". */
    public static final int MIN_DIGITS = 5;

    /** Longest sensible number: E.164 allows 15 digits, plus slack for stray formatting. */
    public static final int MAX_DIGITS = 17;

    private ContactNumber() { }

    /**
     * Whether this sender string is a dialled number rather than a person's name.
     *
     * Deliberately conservative. Any letter at all means it is a name and is left alone, so
     * "Bob 2", "O2" and "WhatsApp Support" are never mistaken for numbers.
     */
    public static boolean looksLikePhoneNumber(String sender) {
        if (sender == null) return false;
        String trimmed = sender.trim();
        if (trimmed.isEmpty()) return false;

        int digits = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (Character.isLetter(c)) {
                return false;
            } else if (!isNumberPunctuation(c)) {
                return false;
            }
        }
        return digits >= MIN_DIGITS && digits <= MAX_DIGITS;
    }

    /**
     * Separators that legitimately appear inside a dialled number.
     *
     * Includes the non-breaking space, non-breaking hyphen and en dash because contact apps and
     * notification titles use them for grouping, and rejecting them would leave exactly the
     * prettily-formatted numbers unresolved.
     */
    private static boolean isNumberPunctuation(char c) {
        return c == '+' || c == '-' || c == ' ' || c == '(' || c == ')' || c == '.'
                || c == '\u00a0' || c == '\u2011' || c == '\u2013';
    }

    /**
     * Strip formatting so one number in several styles maps to a single cache key.
     *
     * The leading plus is kept: it distinguishes an international number from a local one, and
     * PhoneLookup matches more reliably with it present.
     */
    public static String normalise(String number) {
        if (number == null) return "";
        String trimmed = number.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        if (trimmed.startsWith("+")) out.append('+');
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }
}
