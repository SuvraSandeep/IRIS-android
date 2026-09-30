package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-capability permission catalogue behind the Settings permission dashboard.
 *
 * SCOPE: this class is deliberately free of every Android import so it can be compiled and
 * tested offline (scripts/test-speech.sh). It holds only the HUMAN-READABLE half of the
 * dashboard: what each capability is called, what it unlocks, and what stops working without
 * it. It intentionally does NOT hold the "android.permission.*" strings.
 *
 * WHY THE PERMISSION STRINGS LIVE IN MainActivity: mapping {@link Capability} to the real
 * Manifest.permission constant is done in MainActivity, so the compiler checks every constant.
 * If the strings were duplicated here a typo would compile cleanly and then show a row that is
 * permanently "not allowed" and silently un-grantable. Keeping the mapping in one compiler-checked
 * place removes that failure mode entirely.
 *
 * TWO KINDS OF ACCESS, which is the reason this class exists rather than a flat list:
 *  - {@link Kind#RUNTIME} is a normal runtime permission. An Activity can request it with a
 *    system dialog, so the dashboard offers an "Allow" action.
 *  - {@link Kind#SPECIAL} is a special access that CANNOT be requested with a dialog
 *    (notification listening). It can only be granted by the user in a dedicated Settings
 *    screen, so the dashboard deep-links there instead of pretending to ask.
 * Reporting notification listening as if it were a runtime permission would tell the owner the
 * notification summary is ready to use when it is not.
 */
public final class PermissionCatalog {

    private PermissionCatalog() { }

    /** How the access is obtained, which decides what action the dashboard can offer. */
    public enum Kind {
        /** A normal runtime permission: requestable from an Activity with a system dialog. */
        RUNTIME,
        /** A special access grantable only from a dedicated Settings screen. */
        SPECIAL
    }

    /** A capability IRIS can have. Stable identifiers; MainActivity maps these to Manifest constants. */
    public enum Capability {
        MICROPHONE,
        CONTACTS,
        PHONE_CALLS,
        SMS_SEND,
        CALL_LOG,
        SMS_READ,
        NOTIFICATION_ACCESS,
        SHOW_NOTIFICATIONS,
        CAMERA,
        LOCATION
    }

    /** One dashboard row: what it is, what it buys, and the honest cost of leaving it off. */
    public static final class Entry {
        public final Capability capability;
        public final Kind kind;
        /** Short human name, e.g. "Call log". */
        public final String label;
        /** What IRIS can do when this is allowed. */
        public final String enables;
        /** What stops working when it is not allowed. */
        public final String withoutIt;
        /** True when IRIS cannot do its core job without it. */
        public final boolean core;

        Entry(Capability capability, Kind kind, String label, String enables, String withoutIt, boolean core) {
            this.capability = capability;
            this.kind = kind;
            this.label = label;
            this.enables = enables;
            this.withoutIt = withoutIt;
            this.core = core;
        }
    }

    /**
     * Every capability, core ones first so the owner sees what actually matters at the top.
     * A fresh list is returned each call so a caller cannot mutate the catalogue.
     */
    public static List<Entry> all() {
        List<Entry> entries = new ArrayList<>();

        entries.add(new Entry(Capability.MICROPHONE, Kind.RUNTIME, "Microphone",
                "Hear the wake phrase and your commands.",
                "IRIS cannot listen at all. Nothing else works.", true));

        entries.add(new Entry(Capability.CONTACTS, Kind.RUNTIME, "Contacts",
                "Call and text people by name or relationship.",
                "You can only reach digits you say out loud.", true));

        entries.add(new Entry(Capability.PHONE_CALLS, Kind.RUNTIME, "Place calls",
                "Start a call after you confirm it.",
                "IRIS opens the dialer for you to press call.", true));

        entries.add(new Entry(Capability.SMS_SEND, Kind.RUNTIME, "Send texts",
                "Send a text after reading it back to you.",
                "IRIS cannot send texts.", false));

        entries.add(new Entry(Capability.CALL_LOG, Kind.RUNTIME, "Call log",
                "Answer who called, missed calls and recent calls.",
                "IRIS only knows calls it placed itself.", false));

        entries.add(new Entry(Capability.SMS_READ, Kind.RUNTIME, "Read SMS inbox",
                "Read your last inbox message out loud.",
                "Inbox questions are declined. Notification summaries still work.", false));

        entries.add(new Entry(Capability.NOTIFICATION_ACCESS, Kind.SPECIAL, "Notification access",
                "Summarise and read WhatsApp, SMS and app notifications.",
                "Notification summaries have nothing to read.", false));

        entries.add(new Entry(Capability.SHOW_NOTIFICATIONS, Kind.RUNTIME, "Show notifications",
                "Show the listening status and reply notifications.",
                "IRIS runs without a visible status notification.", false));

        entries.add(new Entry(Capability.CAMERA, Kind.RUNTIME, "Camera",
                "Take a photo or start a video on request.",
                "Camera commands are declined.", false));

        entries.add(new Entry(Capability.LOCATION, Kind.RUNTIME, "Approximate location",
                "Answer weather and where-am-I questions.",
                "IRIS asks you to name the place instead.", false));

        return entries;
    }

    /** Look up a single capability, or null when it is not in the catalogue. */
    public static Entry find(Capability capability) {
        if (capability == null) return null;
        for (Entry entry : all()) {
            if (entry.capability == capability) return entry;
        }
        return null;
    }

    /**
     * One-line headline for the dashboard, e.g. "8 of 10 allowed." Counts are clamped so a
     * miscount can never render nonsense like "-1 of 10" or a figure above the total.
     */
    public static String summary(int granted, int total) {
        if (total <= 0) return "Nothing to show.";
        int safeTotal = total;
        int safeGranted = granted < 0 ? 0 : Math.min(granted, safeTotal);
        if (safeGranted == safeTotal) return "All " + safeTotal + " allowed.";
        return safeGranted + " of " + safeTotal + " allowed.";
    }

    /**
     * Headline for the core capabilities specifically. Missing core access is the only case
     * worth alarming the owner about, so it is reported separately from the total.
     */
    public static String coreWarning(int missingCore) {
        if (missingCore <= 0) return "";
        if (missingCore == 1) return "1 essential permission is missing, so IRIS is limited.";
        return missingCore + " essential permissions are missing, so IRIS is limited.";
    }
}
