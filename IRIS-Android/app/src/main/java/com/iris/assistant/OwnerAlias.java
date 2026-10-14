package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rewrites the owner's own name into third-person pronouns, so a message that names the owner
 * ("where is Suvra", "is Suvra free", "Suvra's email") is understood by the same rules written for
 * "he / his". Pure and offline-testable.
 *
 * Used only to match intent; the chosen reply is still built from templates with the real name,
 * so the slightly ungrammatical "tell he I called" that this can produce is never sent anywhere.
 */
public final class OwnerAlias {

    private OwnerAlias() { }

    public static String toThirdPerson(String body, String firstName) {
        if (body == null) return "";
        if (firstName == null || firstName.trim().isEmpty()) return body;
        String fn = firstName.trim();
        String q = Pattern.quote(fn);
        String out = body;
        // Possessive first ("Suvra's email" -> "his email"), both apostrophe styles.
        out = out.replaceAll("(?i)\\b" + q + "(?:'s|s')\\b", "his");
        // Bare name as a standalone word -> "he".
        out = out.replaceAll("(?i)\\b" + q + "\\b", "he");
        return out;
    }

    /** True when the message mentions the owner by name (used to decide whether to alias). */
    public static boolean mentionsOwner(String body, String firstName) {
        if (body == null || firstName == null || firstName.trim().isEmpty()) return false;
        String q = Pattern.quote(firstName.trim());
        return Pattern.compile("(?i)\\b" + q + "\\b").matcher(body).find();
    }
}
