package com.iris.assistant;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline checks for {@link PermissionCatalog}, the data behind the Settings permission dashboard.
 *
 * These run on a plain JVM (no Android, no Robolectric) because PermissionCatalog is deliberately
 * Android-free. Registered in scripts/test-speech.sh.
 */
public class PermissionCatalogTest {

    private static int checks = 0;

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + what);
    }

    private static void equal(String actual, String expected, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        catalogueIsCompleteAndWellFormed();
        coreCapabilitiesAreTheOnesIrisCannotWorkWithout();
        notificationAccessIsSpecialNotRuntime();
        everyCapabilityIsInTheCatalogue();
        findResolvesAndFailsSafely();
        summaryReadsCorrectly();
        summaryClampsNonsenseCounts();
        coreWarningReadsCorrectly();
        listIsDefensivelyCopied();
        System.out.println("Passed " + checks + " permission catalogue checks");
    }

    /** Every row must be renderable: no blank label or copy, and no duplicate capability. */
    private static void catalogueIsCompleteAndWellFormed() {
        List<PermissionCatalog.Entry> entries = PermissionCatalog.all();
        check(!entries.isEmpty(), "catalogue is not empty");

        Set<PermissionCatalog.Capability> seen = new HashSet<>();
        for (PermissionCatalog.Entry entry : entries) {
            String name = String.valueOf(entry.capability);
            check(entry.capability != null, name + " has a capability");
            check(entry.kind != null, name + " has a kind");
            check(entry.label != null && !entry.label.trim().isEmpty(), name + " has a label");
            check(entry.enables != null && !entry.enables.trim().isEmpty(), name + " says what it enables");
            check(entry.withoutIt != null && !entry.withoutIt.trim().isEmpty(), name + " says what breaks without it");
            // The dashboard shows these as sentences; a missing full stop reads as truncated text.
            check(entry.enables.endsWith("."), name + " enables-copy ends in a full stop");
            check(entry.withoutIt.endsWith("."), name + " without-it copy ends in a full stop");
            check(seen.add(entry.capability), name + " appears only once");
        }
    }

    /**
     * Core must mean "IRIS cannot do its job", not "nice to have". If this drifts, the dashboard
     * starts alarming the owner about optional extras and the warning becomes noise.
     */
    private static void coreCapabilitiesAreTheOnesIrisCannotWorkWithout() {
        int core = 0;
        for (PermissionCatalog.Entry entry : PermissionCatalog.all()) {
            if (entry.core) core++;
        }
        check(core == 3, "exactly three capabilities are core (microphone, contacts, place calls)");

        check(PermissionCatalog.find(PermissionCatalog.Capability.MICROPHONE).core, "microphone is core");
        check(PermissionCatalog.find(PermissionCatalog.Capability.CONTACTS).core, "contacts is core");
        check(PermissionCatalog.find(PermissionCatalog.Capability.PHONE_CALLS).core, "placing calls is core");

        // Explicitly NOT core: the owner can use IRIS fully without any of these.
        check(!PermissionCatalog.find(PermissionCatalog.Capability.CALL_LOG).core, "call log is optional");
        check(!PermissionCatalog.find(PermissionCatalog.Capability.SMS_READ).core, "reading SMS is optional");
        check(!PermissionCatalog.find(PermissionCatalog.Capability.CAMERA).core, "camera is optional");
        check(!PermissionCatalog.find(PermissionCatalog.Capability.LOCATION).core, "location is optional");
        check(!PermissionCatalog.find(PermissionCatalog.Capability.NOTIFICATION_ACCESS).core,
                "notification access is optional");
    }

    /**
     * The whole reason Kind exists. Notification listening cannot be granted by a runtime dialog,
     * so if it were marked RUNTIME the dashboard would offer an "Allow" button that does nothing.
     * Everything else must stay RUNTIME so it keeps offering the real dialog.
     */
    private static void notificationAccessIsSpecialNotRuntime() {
        for (PermissionCatalog.Entry entry : PermissionCatalog.all()) {
            if (entry.capability == PermissionCatalog.Capability.NOTIFICATION_ACCESS) {
                check(entry.kind == PermissionCatalog.Kind.SPECIAL, "notification access is SPECIAL");
            } else {
                check(entry.kind == PermissionCatalog.Kind.RUNTIME,
                        entry.capability + " is RUNTIME so it can be requested with a dialog");
            }
        }
    }

    /** A capability added to the enum but not the list would render as a silently missing row. */
    private static void everyCapabilityIsInTheCatalogue() {
        for (PermissionCatalog.Capability capability : PermissionCatalog.Capability.values()) {
            check(PermissionCatalog.find(capability) != null, capability + " has a catalogue entry");
        }
        check(PermissionCatalog.all().size() == PermissionCatalog.Capability.values().length,
                "catalogue size matches the number of capabilities");
    }

    private static void findResolvesAndFailsSafely() {
        PermissionCatalog.Entry callLog = PermissionCatalog.find(PermissionCatalog.Capability.CALL_LOG);
        check(callLog != null, "call log resolves");
        equal(callLog.label, "Call log", "call log label");
        check(PermissionCatalog.find(null) == null, "a null capability returns null rather than throwing");
    }

    private static void summaryReadsCorrectly() {
        equal(PermissionCatalog.summary(10, 10), "All 10 allowed.", "all granted");
        equal(PermissionCatalog.summary(7, 10), "7 of 10 allowed.", "some granted");
        equal(PermissionCatalog.summary(0, 10), "0 of 10 allowed.", "none granted");
        equal(PermissionCatalog.summary(1, 1), "All 1 allowed.", "single capability granted");
    }

    /** A bad count must never render "-2 of 10" or "12 of 10" in the owner's face. */
    private static void summaryClampsNonsenseCounts() {
        equal(PermissionCatalog.summary(-2, 10), "0 of 10 allowed.", "negative granted clamps to zero");
        equal(PermissionCatalog.summary(12, 10), "All 10 allowed.", "over-count clamps to the total");
        equal(PermissionCatalog.summary(0, 0), "Nothing to show.", "zero total is handled");
        equal(PermissionCatalog.summary(3, -1), "Nothing to show.", "negative total is handled");
    }

    private static void coreWarningReadsCorrectly() {
        equal(PermissionCatalog.coreWarning(0), "", "no warning when nothing core is missing");
        equal(PermissionCatalog.coreWarning(-1), "", "no warning for a negative count");
        equal(PermissionCatalog.coreWarning(1),
                "1 essential permission is missing, so IRIS is limited.", "singular reads correctly");
        equal(PermissionCatalog.coreWarning(2),
                "2 essential permissions are missing, so IRIS is limited.", "plural reads correctly");
    }

    /** all() must hand back a fresh list; a shared list could be mutated by one caller for all. */
    private static void listIsDefensivelyCopied() {
        List<PermissionCatalog.Entry> first = PermissionCatalog.all();
        int size = first.size();
        first.clear();
        check(PermissionCatalog.all().size() == size, "clearing a returned list does not affect the catalogue");
    }
}
