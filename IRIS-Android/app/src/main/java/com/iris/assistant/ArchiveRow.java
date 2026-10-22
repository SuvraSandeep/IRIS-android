package com.iris.assistant;

/**
 * One archived notification, exactly as stored.
 *
 * SCOPE: a plain value type with no Android imports, deliberately a top-level class rather than
 * nested inside {@link NotificationArchive}. Nesting it there would drag the SQLite and Context
 * imports into anything that merely wants to read a row, which would make {@link ArchiveExport}
 * and its CSV escaping impossible to test offline. The same separation is used for
 * NotificationSummary against NotificationStore.
 */
public final class ArchiveRow {

    public long id;
    public String pkg = "";
    public String appLabel = "";
    public String accountLabel = "";
    public int userHandle;
    public String notifKey = "";
    /** The notification title exactly as it arrived. */
    public String rawTitle = "";
    /** Best guess at the individual sender; equals rawTitle for one-to-one chats. */
    public String sender = "";
    /** Group name when this arrived in a group conversation, otherwise empty. */
    public String group = "";
    public String body = "";
    public long postTime;
    public long capturedTime;
    public int rowKind;
    /** The app stated outright that the sender removed a message. */
    public boolean deleted;
    /** Inferred from an app-initiated withdrawal while the screen was off. A guess, not a fact. */
    public boolean suspected;
    public long deletionTime;

    /**
     * Filename of the captured image, or empty.
     *
     * A bare name, not a path: ArchiveImages resolves it against the archive directory and
     * re-sanitises it on the way, so a value read back from the database can never point outside.
     */
    public String imagePath = "";

    /** Whether this row has a stored picture or video to show. */
    public boolean hasImage() { return imagePath != null && !imagePath.isEmpty(); }

    /** Whether this row has a stored photo/picture to show. */
    public boolean isPhoto() {
        return hasImage() && !isVideo() && !isAudio() && !isDocument();
    }

    /** Whether this row has a stored video to show. */
    public boolean isVideo() {
        if (imagePath == null || imagePath.isEmpty()) return false;
        String lower = imagePath.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".3gp");
    }

    /** Whether this row has a stored voice note or audio recording to play. */
    public boolean isAudio() {
        if (imagePath == null || imagePath.isEmpty()) return false;
        String lower = imagePath.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".ogg")
                || lower.endsWith(".opus")
                || lower.endsWith(".m4a")
                || lower.endsWith(".aac")
                || lower.endsWith(".amr")
                || lower.endsWith(".mp3");
    }

    /** Whether this row has a stored document or file attachment. */
    public boolean isDocument() {
        if (imagePath == null || imagePath.isEmpty()) return false;
        String lower = imagePath.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".pdf")
                || lower.endsWith(".docx")
                || lower.endsWith(".doc")
                || lower.endsWith(".xlsx")
                || lower.endsWith(".xls")
                || lower.endsWith(".pptx")
                || lower.endsWith(".ppt")
                || lower.endsWith(".txt")
                || lower.endsWith(".csv")
                || lower.endsWith(".rtf")
                || lower.endsWith(".zip");
    }

    /** Clean uppercase extension for this row's document (e.g. "PDF", "DOCX"). */
    public String documentExtension() {
        if (imagePath == null || imagePath.isEmpty()) return "DOC";
        int dot = imagePath.lastIndexOf('.');
        if (dot >= 0 && dot < imagePath.length() - 1) {
            return imagePath.substring(dot + 1).toUpperCase(java.util.Locale.US);
        }
        return "DOC";
    }

    /** High-contrast short badge for this row's document type. */
    public String documentBadge() {
        String ext = documentExtension();
        if ("DOCX".equals(ext) || "DOC".equals(ext)) return "DOC";
        if ("XLSX".equals(ext) || "XLS".equals(ext)) return "XLS";
        if ("PPTX".equals(ext) || "PPT".equals(ext)) return "PPT";
        return ext;
    }

    /** Representative icon for document type. */
    public String documentIcon() {
        String badge = documentBadge();
        if ("PDF".equals(badge)) return "\uD83D\uDCC4"; // 📄
        if ("DOC".equals(badge)) return "\uD83D\uDCDD"; // 📝
        if ("XLS".equals(badge)) return "\uD83D\uDCCA"; // 📊
        if ("PPT".equals(badge)) return "\uD83D\uDCD1"; // 📑
        if ("ZIP".equals(badge)) return "\uD83E\uDDF0"; // 🗜️
        return "\uD83D\uDCCB"; // 📋
    }

    /** Confidence that this message was taken back by its sender. */
    public DeletedMessageRules.Confidence confidence() {
        if (deleted) return DeletedMessageRules.Confidence.CONFIRMED;
        if (suspected) return DeletedMessageRules.Confidence.SUSPECTED;
        return DeletedMessageRules.Confidence.NONE;
    }

    /** False for capture-gap and orphan-deletion markers, which share the table with messages. */
    public boolean isMessage() { return rowKind == ArchiveSchema.KIND_MESSAGE; }

    public boolean isFlagged() { return deleted || suspected; }

    /** Best available name for the sender, falling back to the raw title then a placeholder. */
    public String displayName() {
        if (!sender.isEmpty()) return sender;
        if (!rawTitle.isEmpty()) return rawTitle;
        return "Unknown sender";
    }

    /** Account label if the app provided one, otherwise the plain app label. */
    public String displayAccount() {
        return accountLabel.isEmpty() ? appLabel : accountLabel;
    }
}
