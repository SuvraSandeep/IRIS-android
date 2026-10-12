package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Offline checks for {@link ArchiveExport}.
 *
 * The CSV escaping cases matter most. The export is the only copy of the archive that survives a
 * factory reset or a new phone, so a message containing a comma silently shifting every later
 * column would corrupt the owner's only backup.
 */
public class ArchiveExportTest {

    private static int checks = 0;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

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
        escapesCsvFields();
        csvHasAStableHeader();
        csvRowHoldsEveryColumn();
        csvSurvivesAwkwardMessageText();
        textExportStatesItsLimits();
        textExportShowsFlaggedStatus();
        textExportIncludesCaptureGaps();
        remindsOnlyWhenThereIsSomethingToLose();
        namesFilesWithADate();
        System.out.println("Passed " + checks + " archive export checks");
    }

    /** RFC 4180: quote when the field contains a comma, quote or line break; double the quotes. */
    private static void escapesCsvFields() {
        equal(ArchiveExport.csvField("plain"), "plain", "a plain field is not quoted");
        equal(ArchiveExport.csvField(""), "", "an empty field stays empty");
        equal(ArchiveExport.csvField(null), "", "a null field becomes empty");
        equal(ArchiveExport.csvField("a,b"), "\"a,b\"", "a comma forces quoting");
        equal(ArchiveExport.csvField("say \"hi\""), "\"say \"\"hi\"\"\"", "quotes are doubled");
        equal(ArchiveExport.csvField("line1\nline2"), "\"line1\nline2\"", "a newline forces quoting");
        equal(ArchiveExport.csvField("line1\r\nline2"), "\"line1\r\nline2\"",
                "a carriage return forces quoting");
        equal(ArchiveExport.csvField("emoji \uD83D\uDE00"), "emoji \uD83D\uDE00",
                "emoji need no quoting and are preserved");
    }

    private static void csvHasAStableHeader() {
        check(ArchiveExport.CSV_HEADER.startsWith("captured_at,posted_at,"), "header starts with timestamps");
        check(ArchiveExport.CSV_HEADER.contains("message"), "header names the message column");
        check(ArchiveExport.CSV_HEADER.contains("status"), "header names the status column");
        check(ArchiveExport.CSV_HEADER.contains("unsent_at"), "header names the deletion time");
        check(ArchiveExport.CSV_HEADER.split(",").length == 10, "header has ten columns");
        check(ArchiveExport.toCsv(null, UTC).startsWith(ArchiveExport.CSV_HEADER),
                "an empty export still carries the header");
    }

    private static void csvRowHoldsEveryColumn() {
        ArchiveRow row = message("Ana", "see you at 8");
        String line = ArchiveExport.csvRow(row, UTC);
        check(line.split(",").length >= 10 || line.contains("\""),
                "a row produces the full column set");
        check(line.contains("Ana"), "the sender is present");
        check(line.contains("see you at 8"), "the message is present");
        check(line.contains("WhatsApp"), "the app is present");
        check(line.endsWith("message"), "the entry type is last");
    }

    /** The realistic failure: a message body containing the delimiter. */
    private static void csvSurvivesAwkwardMessageText() {
        ArchiveRow row = message("Ana", "hey, are you free? \"maybe\" later\nor tomorrow");
        String line = ArchiveExport.csvRow(row, UTC);
        // The body must be a single quoted field, so the column count outside quotes is unchanged.
        check(line.contains("\"hey, are you free? \"\"maybe\"\" later\nor tomorrow\""),
                "a body with a comma, quotes and a newline is quoted and escaped as one field");

        ArchiveRow flagged = message("Bob", "deleted, probably");
        flagged.deleted = true;
        flagged.deletionTime = 1760000500000L;
        String flaggedLine = ArchiveExport.csvRow(flagged, UTC);
        check(flaggedLine.contains("UNSENT BY SENDER"), "a confirmed deletion reports its status");
        check(flaggedLine.contains(DeletedMessageRules.formatTime(1760000500000L, UTC)),
                "the deletion time is formatted into its own column");
    }

    /**
     * A file found months later must not be mistaken for a complete record of everything ever
     * sent. The header states the limits explicitly.
     */
    private static void textExportStatesItsLimits() {
        List<ArchiveRow> rows = new ArrayList<>();
        rows.add(message("Ana", "hello"));
        String text = ArchiveExport.toText(rows, 1760000000000L, UTC);
        check(text.contains("IRIS notification archive"), "the export is identified");
        check(text.contains("Exported"), "the export date is stated");
        check(text.contains("never raised a notification"),
                "states that uncaptured messages exist, so the file is not read as complete");
        check(text.contains("inferred, not"), "states that suspected entries are inferred");
        check(text.contains("capture gaps"), "mentions capture gaps");
        check(text.contains("Ana"), "the sender appears");
        check(text.contains("hello"), "the message appears");
    }

    private static void textExportShowsFlaggedStatus() {
        List<ArchiveRow> rows = new ArrayList<>();
        ArchiveRow confirmed = message("Ana", "original text");
        confirmed.deleted = true;
        confirmed.deletionTime = 1760000500000L;
        rows.add(confirmed);
        ArchiveRow suspected = message("Bob", "vanished text");
        suspected.suspected = true;
        suspected.deletionTime = 1760000600000L;
        rows.add(suspected);

        String text = ArchiveExport.toText(rows, 1760000000000L, UTC);
        check(text.contains("SENDER UNSENT THIS MESSAGE AT"), "a confirmed status line is included");
        check(text.contains("POSSIBLY UNSENT"), "a suspected status line is included");
        // The original text is the entire point and must always survive the export.
        check(text.contains("original text"), "the original text of a deleted message is preserved");
        check(text.contains("vanished text"), "the text of a suspected deletion is preserved");
    }

    private static void textExportIncludesCaptureGaps() {
        List<ArchiveRow> rows = new ArrayList<>();
        ArchiveRow gap = new ArchiveRow();
        gap.rowKind = ArchiveSchema.KIND_LISTENER_DISCONNECTED;
        gap.body = "Notification capture stopped.";
        gap.postTime = 1760000000000L;
        rows.add(gap);
        String text = ArchiveExport.toText(rows, 1760000000000L, UTC);
        check(text.contains("CAPTURE STOPPED"), "a gap marker is labelled in the export");
        check(text.contains("Notification capture stopped."), "the gap note is included");
    }

    private static void remindsOnlyWhenThereIsSomethingToLose() {
        long now = 1760000000000L;
        check(!ArchiveExport.shouldRemind(0, 0, now),
                "an empty archive never nags, however long since the last export");
        check(ArchiveExport.shouldRemind(0, 5, now),
                "a never-exported archive with entries does nag");
        check(ArchiveExport.shouldRemind(now - ArchiveExport.REMIND_AFTER_MS, 5, now),
                "a month since the last export nags");
        check(!ArchiveExport.shouldRemind(now - 1000, 5, now),
                "a recent export does not nag");
        check(ArchiveExport.shouldRemind(now - 1000, ArchiveExport.REMIND_AFTER_ROWS, now),
                "enough new rows nags even straight after an export");
        check(ArchiveExport.reminderText(1).contains("1 new entry"), "singular reminder");
        check(ArchiveExport.reminderText(5).contains("5 new entries"), "plural reminder");
    }

    private static void namesFilesWithADate() {
        String name = ArchiveExport.fileName("csv", 0L, UTC);
        equal(name, "iris-notification-archive-1970-01-01-0000.csv", "dated filename");
        check(ArchiveExport.fileName("txt", 0L, UTC).endsWith(".txt"), "extension is honoured");
        // Dated so a second export does not silently overwrite the first.
        check(!ArchiveExport.fileName("csv", 0L, UTC)
                .equals(ArchiveExport.fileName("csv", 3600_000L, UTC)),
                "a later export gets a different name");
    }

    private static ArchiveRow message(String sender, String body) {
        ArchiveRow row = new ArchiveRow();
        row.pkg = "com.whatsapp";
        row.appLabel = "WhatsApp";
        row.accountLabel = "WhatsApp";
        row.sender = sender;
        row.rawTitle = sender;
        row.body = body;
        row.postTime = 1760000000000L;
        row.capturedTime = 1760000000001L;
        row.rowKind = ArchiveSchema.KIND_MESSAGE;
        return row;
    }
}
