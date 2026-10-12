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
