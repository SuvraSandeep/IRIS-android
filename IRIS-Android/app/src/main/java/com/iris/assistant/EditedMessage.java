package com.iris.assistant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises a WhatsApp "edited" message re-notification, so the responder doesn't fire a second
 * reply when someone edits a message it (or its original) already saw. Pure and offline-testable.
 *
 * An edit only happens AFTER a message was sent, so the original was already handled; the edited
 * re-notify is therefore a re-trigger to be skipped, not a fresh message. Detection is marker-based
 * and conservative: WhatsApp tags an edited message with a trailing "(edited)" / "<edited>" marker,
 * which a normal sentence containing the word "edited" won't match.
 *
 * NOTE: the exact marker is app/locale dependent; confirm on-device and extend if a variant slips.
 */
public final class EditedMessage {

    private EditedMessage() { }

    private static final Pattern EDIT_MARKER = Pattern.compile(
            "(?i)(\\(edited\\)|<\\s*edited\\s*>|\\bthis message was edited\\b|"
            + "[\\u2022\\u00b7\\u270e]\\s*edited|\\bedited\\s*[\\u2022\\u00b7])\\s*$");

    public static boolean looksEdited(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        return EDIT_MARKER.matcher(t).find();
    }
}
