package com.iris.assistant;

/**
 * Offline checks for {@link ArchiveSchema}'s size policy.
 *
 * The DDL itself is verified separately by tests/check-archive-schema.py, which executes it against
 * a real SQLite engine. This file covers the pure arithmetic and the capture decisions, where an
 * inverted comparison would either stop all capture or silently ignore the ceiling.
 */
public class ArchiveSchemaTest {

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

    private static void equalInt(int actual, int expected, String what) {
        checks++;
        if (actual != expected) {
            throw new AssertionError("FAILED: " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    public static void main(String[] args) {
        classifiesUsageBands();
        badInputsReportOkNotFull();
        percentIsClamped();
        onlyFullStopsMessageCapture();
        markersAndFlaggingAlwaysSurvive();
        formatsSizesReadably();
        usageMessagesExplainTheConsequence();
        schemaConstantsAreCoherent();
        System.out.println("Passed " + checks + " archive schema policy checks");
    }

    private static void classifiesUsageBands() {
        long max = 1000L;
        check(ArchiveSchema.usageFor(0, max) == ArchiveSchema.Usage.OK, "empty is OK");
        check(ArchiveSchema.usageFor(799, max) == ArchiveSchema.Usage.OK, "just under the warn mark");
        check(ArchiveSchema.usageFor(800, max) == ArchiveSchema.Usage.WARN, "at the warn mark");
        check(ArchiveSchema.usageFor(949, max) == ArchiveSchema.Usage.WARN, "just under critical");
        check(ArchiveSchema.usageFor(950, max) == ArchiveSchema.Usage.CRITICAL, "at the critical mark");
        check(ArchiveSchema.usageFor(999, max) == ArchiveSchema.Usage.CRITICAL, "just under full");
        check(ArchiveSchema.usageFor(1000, max) == ArchiveSchema.Usage.FULL, "at the ceiling");
        check(ArchiveSchema.usageFor(5000, max) == ArchiveSchema.Usage.FULL, "over the ceiling");
    }

    /**
     * Getting this backwards would be the worst possible failure: a size check that errors would
     * report FULL and silently stop capturing everything. Nonsense input must fail open.
     */
    private static void badInputsReportOkNotFull() {
        check(ArchiveSchema.usageFor(-1, 1000) == ArchiveSchema.Usage.OK, "a negative size fails open");
        check(ArchiveSchema.usageFor(500, 0) == ArchiveSchema.Usage.OK, "a zero ceiling fails open");
        check(ArchiveSchema.usageFor(500, -1) == ArchiveSchema.Usage.OK, "a negative ceiling fails open");
    }

    private static void percentIsClamped() {
        equalInt(ArchiveSchema.percentUsed(0, 1000), 0, "empty is 0%");
        equalInt(ArchiveSchema.percentUsed(500, 1000), 50, "half is 50%");
        equalInt(ArchiveSchema.percentUsed(1000, 1000), 100, "exactly full is 100%");
        equalInt(ArchiveSchema.percentUsed(99999, 1000), 100, "over-full clamps to 100%");
        equalInt(ArchiveSchema.percentUsed(-5, 1000), 0, "a negative size clamps to 0%");
        equalInt(ArchiveSchema.percentUsed(500, 0), 0, "a zero ceiling yields 0%");
    }

    private static void onlyFullStopsMessageCapture() {
        check(ArchiveSchema.allowsMessageInsert(ArchiveSchema.Usage.OK), "OK allows capture");
        check(ArchiveSchema.allowsMessageInsert(ArchiveSchema.Usage.WARN), "WARN still allows capture");
        check(ArchiveSchema.allowsMessageInsert(ArchiveSchema.Usage.CRITICAL),
                "CRITICAL still allows capture, it only prompts");
        check(!ArchiveSchema.allowsMessageInsert(ArchiveSchema.Usage.FULL), "FULL stops new messages");
    }

    /**
     * What actually survives a full archive. Flagging is an update of three small columns, and it
     * is the entire point of the feature, so it must never be gated on size. Markers must survive
     * too, otherwise the fact that capture stopped would itself be unrecorded.
     */
    private static void markersAndFlaggingAlwaysSurvive() {
        for (ArchiveSchema.Usage usage : ArchiveSchema.Usage.values()) {
            check(ArchiveSchema.allowsMarkerInsert(usage), "markers allowed at " + usage);
        }
        check(ArchiveSchema.allowsFlagUpdate(), "flagging an existing row is always allowed");
    }

    private static void formatsSizesReadably() {
        equal(ArchiveSchema.readableSize(0), "0 B", "zero bytes");
        equal(ArchiveSchema.readableSize(512), "512 B", "bytes");
        equal(ArchiveSchema.readableSize(2048), "2 KB", "kilobytes");
        equal(ArchiveSchema.readableSize(5L * 1024 * 1024), "5 MB", "megabytes");
        equal(ArchiveSchema.readableSize(1024L * 1024 * 1024), "1.0 GB", "exactly one gigabyte");
        equal(ArchiveSchema.readableSize((long) (1.5 * 1024 * 1024 * 1024)), "1.5 GB", "fractional gigabytes");
        equal(ArchiveSchema.readableSize(-100), "0 B", "a negative size renders as zero");
    }

    private static void usageMessagesExplainTheConsequence() {
        String full = ArchiveSchema.usageMessage(ArchiveSchema.Usage.FULL, 1000, 1000);
        check(full.contains("full"), "full message says so");
        check(full.contains("no longer being saved"), "full message states capture stopped");
        check(full.contains("still be flagged"),
                "full message explains that already-saved messages can still be flagged");

        String critical = ArchiveSchema.usageMessage(ArchiveSchema.Usage.CRITICAL, 960, 1000);
        check(critical.contains("96%"), "critical message reports the percentage");
        check(critical.contains("capture will stop"), "critical message states the consequence");

        String warn = ArchiveSchema.usageMessage(ArchiveSchema.Usage.WARN, 850, 1000);
        check(warn.contains("85%"), "warn message reports the percentage");
        check(!warn.contains("stop"), "warn message does not threaten, it only informs");

        check(!ArchiveSchema.usageMessage(ArchiveSchema.Usage.OK, 10, 1000).isEmpty(),
                "OK still reports a size");
    }

    private static void schemaConstantsAreCoherent() {
        check(ArchiveSchema.MAX_BYTES == 1024L * 1024L * 1024L, "ceiling is one gigabyte");
        check(ArchiveSchema.WARN_PERCENT < ArchiveSchema.CRITICAL_PERCENT,
                "the warn mark comes before the critical mark");
        check(ArchiveSchema.CRITICAL_PERCENT < 100, "critical is below full");
        check(ArchiveSchema.CREATE_STATEMENTS.length >= 9, "all DDL statements are present");
        check(ArchiveSchema.KIND_MESSAGE == 0, "message rows are kind zero, the column default");

        // Every row kind must be distinct, or a marker would be indistinguishable from a message.
        int[] kinds = {ArchiveSchema.KIND_MESSAGE, ArchiveSchema.KIND_LISTENER_CONNECTED,
                ArchiveSchema.KIND_LISTENER_DISCONNECTED, ArchiveSchema.KIND_CAPTURE_PAUSED_FULL};
        for (int i = 0; i < kinds.length; i++) {
            for (int j = i + 1; j < kinds.length; j++) {
                check(kinds[i] != kinds[j], "row kinds " + i + " and " + j + " are distinct");
            }
        }

        // Each DDL statement should be a single statement; a stray semicolon would mean the
        // Python extractor and SQLiteOpenHelper.execSQL disagree about what they are running.
        for (String statement : ArchiveSchema.CREATE_STATEMENTS) {
            check(!statement.trim().endsWith(";"), "no trailing semicolon: " + brief(statement));
            check(statement.indexOf(';') < 0 || statement.contains("RAISE"),
                    "no embedded statement separator outside a trigger body: " + brief(statement));
        }
    }

    private static String brief(String statement) {
        return statement.length() <= 40 ? statement : statement.substring(0, 40) + "...";
    }
}
