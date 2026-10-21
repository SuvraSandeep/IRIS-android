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

    /** Functional supplier allowing ArchiveExport to stream media into ZIP backups without Android dependencies. */
    public interface MediaStreamSupplier {
        java.io.InputStream open(String storedName) throws java.io.IOException;
    }

    /** Suggested filename, dated so repeated exports do not overwrite one another. */
    public static String fileName(String extension, long nowMs, TimeZone zone) {
        return fileName(extension, false, nowMs, zone);
    }

    /** Suggested filename, reflecting whether the export was selective or comprehensive. */
    public static String fileName(String extension, boolean isFiltered, long nowMs, TimeZone zone) {
        java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US);
        stamp.setTimeZone(zone == null ? TimeZone.getTimeZone("UTC") : zone);
        String prefix = isFiltered ? "iris-archive-filtered-" : "iris-notification-archive-";
        return prefix + stamp.format(new java.util.Date(nowMs)) + "." + extension;
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
                            confidence, row.postTime, row.deletionTime, zone)).append('\n');
                }
                out.append(divider()).append('\n');
            }
        }
        return out.toString();
    }

    private static String divider() {
        return "------------------------------------------------------------";
    }

    /**
     * Export the archive as a self-contained ZIP file containing:
     * - transcript.csv (full CSV transcript)
     * - transcript.txt (human-readable text transcript)
     * - forensic_report.html (interactive offline cyber report)
     * - media/ (all stored photos, videos, voice notes, and documents)
     */
    public static void writeZip(java.io.OutputStream out, List<ArchiveRow> rows, TimeZone zone,
                                MediaStreamSupplier mediaSupplier) throws java.io.IOException {
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
        long nowMs = System.currentTimeMillis();

        // 1. transcript.csv
        zip.putNextEntry(new java.util.zip.ZipEntry("transcript.csv"));
        byte[] csvBytes = toCsv(rows, zone).getBytes("UTF-8");
        zip.write(csvBytes);
        zip.closeEntry();

        // 2. transcript.txt
        zip.putNextEntry(new java.util.zip.ZipEntry("transcript.txt"));
        byte[] txtBytes = toText(rows, nowMs, zone).getBytes("UTF-8");
        zip.write(txtBytes);
        zip.closeEntry();

        // 3. forensic_report.html
        zip.putNextEntry(new java.util.zip.ZipEntry("forensic_report.html"));
        byte[] htmlBytes = toHtmlReport(rows, nowMs, zone, null).getBytes("UTF-8");
        zip.write(htmlBytes);
        zip.closeEntry();

        // 4. media files
        if (rows != null && mediaSupplier != null) {
            java.util.Set<String> addedMedia = new java.util.HashSet<>();
            byte[] buf = new byte[8192];
            for (ArchiveRow row : rows) {
                if (row.hasImage() && !addedMedia.contains(row.imagePath)) {
                    addedMedia.add(row.imagePath);
                    try (java.io.InputStream in = mediaSupplier.open(row.imagePath)) {
                        if (in != null) {
                            zip.putNextEntry(new java.util.zip.ZipEntry("media/" + row.imagePath));
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                zip.write(buf, 0, len);
                            }
                            zip.closeEntry();
                        }
                    } catch (Exception ignored) {
                        // Skip deleted or missing media files gracefully
                    }
                }
            }
        }
        zip.finish();
    }

    /**
     * Standalone, self-contained offline HTML forensic report with dark mode cyber styling,
     * client-side search, category chips, media previews, and flagged message highlights.
     */
    public static String toHtmlReport(List<ArchiveRow> rows, long nowMs, TimeZone zone,
                                      java.util.Map<String, String> base64Thumbnails) {
        StringBuilder h = new StringBuilder(16384);
        int total = rows != null ? rows.size() : 0;
        int flaggedCount = 0;
        int mediaCount = 0;
        if (rows != null) {
            for (ArchiveRow r : rows) {
                if (r.isFlagged()) flaggedCount++;
                if (r.hasImage()) mediaCount++;
            }
        }

        h.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        h.append("<meta charset=\"UTF-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        h.append("<title>IRIS Forensic Notification Archive</title>\n");
        h.append("<style>\n");
        h.append("  :root { --bg: #070816; --panel: #0e1028; --border: #1e293b; --text: #e2e8f0; --dim: #94a3b8; --accent: #22d3ee; --flag: #f43f5e; --suspect: #ec4899; }\n");
        h.append("  body { background: var(--bg); color: var(--text); font-family: monospace, sans-serif; margin: 0; padding: 20px; }\n");
        h.append("  .header { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; }\n");
        h.append("  .header h1 { margin: 0 0 6px 0; font-size: 20px; color: var(--accent); letter-spacing: 0.05em; }\n");
        h.append("  .meta-sub { color: var(--dim); font-size: 12px; margin-bottom: 16px; }\n");
        h.append("  .search-bar { width: 100%; box-sizing: border-box; background: var(--bg); border: 1px solid var(--border); color: #fff; padding: 12px; border-radius: 8px; font-family: monospace; font-size: 14px; margin-bottom: 12px; }\n");
        h.append("  .search-bar:focus { outline: none; border-color: var(--accent); }\n");
        h.append("  .chips { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }\n");
        h.append("  .chip { background: var(--panel); border: 1px solid var(--border); color: var(--dim); padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: bold; }\n");
        h.append("  .chip.active { background: #1e1b4b; border-color: var(--accent); color: var(--accent); }\n");
        h.append("  .counter { color: var(--dim); font-size: 11px; margin-top: 8px; }\n");
        h.append("  .card { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 14px; margin-bottom: 12px; border-left: 4px solid var(--accent); transition: transform 0.1s; }\n");
        h.append("  .card.flagged { border-left-color: var(--flag); background: #190b14; }\n");
        h.append("  .card.suspected { border-left-color: var(--suspect); background: #170d1e; }\n");
        h.append("  .card.marker { border-left-color: #64748b; font-style: italic; }\n");
        h.append("  .head { font-weight: bold; font-size: 14px; color: var(--accent); margin-bottom: 4px; }\n");
        h.append("  .card.flagged .head { color: var(--flag); }\n");
        h.append("  .card.suspected .head { color: var(--suspect); }\n");
        h.append("  .row-meta { color: var(--dim); font-size: 11px; margin-bottom: 8px; }\n");
        h.append("  .body { font-size: 13px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }\n");
        h.append("  .status-badge { margin-top: 8px; padding: 6px 10px; border-radius: 4px; font-size: 11px; font-weight: bold; background: rgba(244,63,94,0.15); border: 1px solid var(--flag); color: var(--flag); }\n");
        h.append("  .status-badge.suspect { background: rgba(236,72,153,0.15); border-color: var(--suspect); color: var(--suspect); }\n");
        h.append("  .media-preview { margin-top: 10px; }\n");
        h.append("  .media-preview img { max-width: 100%; max-height: 280px; border-radius: 6px; border: 1px solid var(--border); display: block; }\n");
        h.append("  .media-link { display: inline-block; margin-top: 6px; color: var(--accent); font-size: 12px; text-decoration: none; }\n");
        h.append("  .media-link:hover { text-decoration: underline; }\n");
        h.append("  .caveat { color: var(--dim); font-size: 10px; margin-top: 4px; }\n");
        h.append("</style>\n</head>\n<body>\n");

        h.append("<div class=\"header\">\n");
        h.append("  <h1>IRIS FORENSIC NOTIFICATION ARCHIVE</h1>\n");
        h.append("  <div class=\"meta-sub\">Generated: ").append(DeletedMessageRules.formatTime(nowMs, zone))
                .append(" · Total: ").append(total).append(" · Flagged: ").append(flaggedCount)
                .append(" · Media: ").append(mediaCount).append("</div>\n");
        h.append("  <input type=\"text\" id=\"searchBox\" class=\"search-bar\" placeholder=\"Search sender, message, app...\" oninput=\"applyFilter()\">\n");
        h.append("  <div class=\"chips\">\n");
        h.append("    <button class=\"chip active\" id=\"chipAll\" onclick=\"setFilter('all')\">ALL (").append(total).append(")</button>\n");
        h.append("    <button class=\"chip\" id=\"chipFlagged\" onclick=\"setFilter('flagged')\">FLAGGED 🚩 (").append(flaggedCount).append(")</button>\n");
        h.append("    <button class=\"chip\" id=\"chipMedia\" onclick=\"setFilter('media')\">MEDIA 📷 (").append(mediaCount).append(")</button>\n");
        h.append("  </div>\n");
        h.append("  <div class=\"counter\" id=\"counter\">Showing ").append(total).append(" of ").append(total).append(" entries</div>\n");
        h.append("</div>\n");

        h.append("<div id=\"list\">\n");
        if (rows != null) {
            for (ArchiveRow row : rows) {
                DeletedMessageRules.Confidence conf = row.confidence();
                boolean isFlagged = row.isFlagged();
                boolean isMedia = row.hasImage();
                boolean isMarker = !row.isMessage();

                String cardClass = "card";
                if (conf == DeletedMessageRules.Confidence.CONFIRMED) cardClass += " flagged";
                else if (conf == DeletedMessageRules.Confidence.SUSPECTED) cardClass += " suspected";
                else if (isMarker) cardClass += " marker";

                String who = row.sender.isEmpty()
                        ? (row.rawTitle.isEmpty() ? "Unknown sender" : row.rawTitle)
                        : row.sender;
                String where = row.accountLabel.isEmpty() ? row.appLabel : row.accountLabel;

                h.append("  <div class=\"").append(cardClass).append("\" data-flagged=\"").append(isFlagged)
                        .append("\" data-media=\"").append(isMedia)
                        .append("\" data-search=\"").append(htmlEscape((who + " " + where + " " + row.group + " " + row.body).toLowerCase(Locale.US)))
                        .append("\">\n");

                h.append("    <div class=\"head\">")
                        .append(isMarker ? "— " + htmlEscape(where) : htmlEscape(who + (row.group.isEmpty() ? "" : " · " + row.group)))
                        .append("</div>\n");

                if (!isMarker) {
                    h.append("    <div class=\"row-meta\">").append(htmlEscape(where)).append(" · ")
                            .append(DeletedMessageRules.formatTime(row.postTime, zone)).append("</div>\n");
                }

                h.append("    <div class=\"body\">")
                        .append(row.body.isEmpty() ? "(no text)" : htmlEscape(row.body))
                        .append("</div>\n");

                if (isMedia) {
                    h.append("    <div class=\"media-preview\">\n");
                    if (base64Thumbnails != null && base64Thumbnails.containsKey(row.imagePath)) {
                        h.append("      <img src=\"data:image/jpeg;base64,").append(base64Thumbnails.get(row.imagePath))
                                .append("\" alt=\"thumbnail\">\n");
                    }
                    h.append("      <a class=\"media-link\" href=\"media/").append(htmlEscape(row.imagePath))
                            .append("\" target=\"_blank\">📁 media/").append(htmlEscape(row.imagePath)).append("</a>\n");
                    h.append("    </div>\n");
                }

                if (conf != DeletedMessageRules.Confidence.NONE) {
                    String badgeClass = conf == DeletedMessageRules.Confidence.CONFIRMED ? "status-badge" : "status-badge suspect";
                    h.append("    <div class=\"").append(badgeClass).append("\">\n");
                    h.append("      <div>").append(htmlEscape(DeletedMessageRules.statusLabel(conf, row.deletionTime, zone))).append("</div>\n");
                    String timeline = DeletedMessageRules.deletionTimeline(conf, row.postTime, row.deletionTime, zone);
                    if (!timeline.isEmpty()) {
                        h.append("      <div style=\"margin-top: 3px;\">").append(htmlEscape(timeline)).append("</div>\n");
                    }
                    h.append("    </div>\n");
                    h.append("    <div class=\"caveat\">").append(htmlEscape(DeletedMessageRules.flagCaveat(conf))).append("</div>\n");
                }

                h.append("  </div>\n");
            }
        }
        h.append("</div>\n");

        h.append("<script>\n");
        h.append("  var activeFilter = 'all';\n");
        h.append("  function setFilter(f) {\n");
        h.append("    activeFilter = f;\n");
        h.append("    document.getElementById('chipAll').classList.toggle('active', f === 'all');\n");
        h.append("    document.getElementById('chipFlagged').classList.toggle('active', f === 'flagged');\n");
        h.append("    document.getElementById('chipMedia').classList.toggle('active', f === 'media');\n");
        h.append("    applyFilter();\n");
        h.append("  }\n");
        h.append("  function applyFilter() {\n");
        h.append("    var q = document.getElementById('searchBox').value.toLowerCase().trim();\n");
        h.append("    var cards = document.querySelectorAll('.card');\n");
        h.append("    var visible = 0;\n");
        h.append("    for (var i = 0; i < cards.length; i++) {\n");
        h.append("      var c = cards[i];\n");
        h.append("      var isFlagged = c.getAttribute('data-flagged') === 'true';\n");
        h.append("      var isMedia = c.getAttribute('data-media') === 'true';\n");
        h.append("      var text = c.getAttribute('data-search') || '';\n");
        h.append("      var matchFilter = (activeFilter === 'all') || (activeFilter === 'flagged' && isFlagged) || (activeFilter === 'media' && isMedia);\n");
        h.append("      var matchSearch = !q || text.indexOf(q) >= 0;\n");
        h.append("      if (matchFilter && matchSearch) {\n");
        h.append("        c.style.display = '';\n");
        h.append("        visible++;\n");
        h.append("      } else {\n");
        h.append("        c.style.display = 'none';\n");
        h.append("      }\n");
        h.append("    }\n");
        h.append("    document.getElementById('counter').innerText = 'Showing ' + visible + ' of ' + cards.length + ' entries';\n");
        h.append("  }\n");
        h.append("</script>\n");

        h.append("</body>\n</html>\n");
        return h.toString();
    }

    /** Escapes string for safe insertion into HTML bodies and attributes. */
    public static String htmlEscape(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
