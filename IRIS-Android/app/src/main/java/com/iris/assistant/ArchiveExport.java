package com.iris.assistant;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Formats the notification archive for export, as CSV and as readable plain text.
 *
 * SCOPE: no Android imports, so the formatting (and especially the CSV escaping) is offline
 * testable. MainActivity supplies the rows and writes the bytes through the document picker.
 *
 * WHY EXPORT MATTERS MORE HERE THAN USUAL: the archive is never included in a cloud backup or a
 * device transfer, and clearing app data or uninstalling destroys it. An export is therefore the
 * ONLY copy that can survive a factory reset or a new phone, which is why the app nags about it.
 *
 * CSV ESCAPING IS NOT OPTIONAL: message bodies routinely contain commas, quotes, newlines and
 * emoji. Unescaped, a single message with a comma would shift every later column and quietly
 * corrupt the file the owner is relying on as their only backup.
 */
public final class ArchiveExport {

    private ArchiveExport() { }

    /** Reminder thresholds. Either one trips the nudge. */
    public static final long REMIND_AFTER_MS = 30L * 24L * 60L * 60L * 1000L;
    public static final int REMIND_AFTER_ROWS = 1000;

    /**
     * Whether to nudge the owner to export.
     *
     * A never-exported archive with nothing in it is not worth mentioning, so an empty archive
     * never nags.
     */
    public static boolean shouldRemind(long lastExportAt, int rowsSinceExport, long nowMs) {
        if (rowsSinceExport <= 0) return false;
        if (rowsSinceExport >= REMIND_AFTER_ROWS) return true;
        if (lastExportAt <= 0) return true;
        return nowMs - lastExportAt >= REMIND_AFTER_MS;
    }

    public static String reminderText(int rowsSinceExport) {
        return "The archive has " + rowsSinceExport + " new "
                + (rowsSinceExport == 1 ? "entry" : "entries")
                + " since your last export. It is never included in a phone backup, so an export is "
                + "the only copy that would survive a reset or a new phone.";
    }

    /** Suggested filename, dated so repeated exports do not overwrite one another. */
    public static String fileName(String extension, long nowMs, TimeZone zone) {
        java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US);
        stamp.setTimeZone(zone == null ? TimeZone.getTimeZone("UTC") : zone);
        return "iris-notification-archive-" + stamp.format(new java.util.Date(nowMs)) + "." + extension;
    }

    // ─── CSV ───

    public static final String CSV_HEADER =
            "captured_at,posted_at,app,account,sender,group,message,status,unsent_at,entry_type";

    /** The whole archive as CSV, header first. */
    public static String toCsv(List<ArchiveRow> rows, TimeZone zone) {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        if (rows != null) {
            for (ArchiveRow row : rows) out.append(csvRow(row, zone)).append('\n');
        }
        return out.toString();
    }

    static String csvRow(ArchiveRow row, TimeZone zone) {
        StringBuilder line = new StringBuilder();
        append(line, DeletedMessageRules.formatTime(row.capturedTime, zone));
        append(line, DeletedMessageRules.formatTime(row.postTime, zone));
        append(line, row.appLabel);
        append(line, row.accountLabel);
        append(line, row.sender);
        append(line, row.group);
        append(line, row.body);
        append(line, statusWord(row));
        append(line, row.deletionTime > 0 ? DeletedMessageRules.formatTime(row.deletionTime, zone) : "");
        append(line, entryType(row));
        return line.toString();
    }

    private static void append(StringBuilder line, String value) {
        if (line.length() > 0) line.append(',');
        line.append(csvField(value));
    }

    /**
     * Quote a CSV field per RFC 4180: wrap in quotes when it contains a comma, quote, carriage
     * return or newline, and double any embedded quote.
     *
     * Always quoting would also be valid, but selective quoting keeps the file readable by eye,
     * which matters because the owner asked to be able to inspect it themselves.
     */
    static String csvField(String value) {
        if (value == null || value.isEmpty()) return "";
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String statusWord(ArchiveRow row) {
        if (row.deleted) return "UNSENT BY SENDER";
        if (row.suspected) return "POSSIBLY UNSENT";
        return "";
    }

    private static String entryType(ArchiveRow row) {
        switch (row.rowKind) {
            case ArchiveSchema.KIND_LISTENER_CONNECTED: return "capture started";
            case ArchiveSchema.KIND_LISTENER_DISCONNECTED: return "capture stopped";
            case ArchiveSchema.KIND_CAPTURE_PAUSED_FULL: return "capture paused, archive full";
            case ArchiveSchema.KIND_ORPHAN_DELETION: return "deletion with no saved original";
            default: return "message";
        }
    }

    // ─── Readable text ───

    /**
     * The archive as plain text, newest first.
     *
     * Deliberately separate from the CSV: a spreadsheet is for filtering, this is for reading. The
     * header states the archive's real limitations so a file found months later is not mistaken for
     * a complete record of everything that was ever sent.
     */
    public static String toText(List<ArchiveRow> rows, long nowMs, TimeZone zone) {
        StringBuilder out = new StringBuilder();
        out.append("IRIS notification archive\n");
        out.append("Exported ").append(DeletedMessageRules.formatTime(nowMs, zone)).append('\n');
        out.append("Entries: ").append(rows == null ? 0 : rows.size()).append('\n');
        out.append("\nMessages marked UNSENT were taken back by the sender; the text shown is what\n");
        out.append("arrived before they removed it. Entries marked POSSIBLY UNSENT are inferred, not\n");
        out.append("confirmed. Messages that never raised a notification could not be captured, and\n");
        out.append("capture gaps are listed as their own entries.\n");
        out.append("\n").append(divider()).append('\n');

        if (rows != null) {
            for (ArchiveRow row : rows) {
                if (!row.isMessage()) {
                    out.append('[').append(entryType(row).toUpperCase(Locale.US)).append("] ")
                            .append(DeletedMessageRules.formatTime(row.postTime, zone)).append('\n');
                    if (!row.body.isEmpty()) out.append("    ").append(row.body).append('\n');
                    out.append(divider()).append('\n');
                    continue;
                }
                String who = row.sender.isEmpty()
                        ? (row.rawTitle.isEmpty() ? "Unknown sender" : row.rawTitle)
                        : row.sender;
                out.append(who);
                if (!row.group.isEmpty()) out.append(" (in ").append(row.group).append(')');
                out.append('\n');
                out.append("    ").append(row.accountLabel.isEmpty() ? row.appLabel : row.accountLabel)
                        .append("  \u00b7  ").append(DeletedMessageRules.formatTime(row.postTime, zone))
                        .append('\n');
                out.append("    ").append(row.body.isEmpty() ? "(no text)" : row.body).append('\n');
                DeletedMessageRules.Confidence confidence = row.confidence();
                if (confidence != DeletedMessageRules.Confidence.NONE) {
                    out.append("    ").append(DeletedMessageRules.statusLabel(
                            confidence, row.deletionTime, zone)).append('\n');
                }
                out.append(divider()).append('\n');
            }
        }
        return out.toString();
    }

    private static String divider() {
        return "------------------------------------------------------------";
    }
}
