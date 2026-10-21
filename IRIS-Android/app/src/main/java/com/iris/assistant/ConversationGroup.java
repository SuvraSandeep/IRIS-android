package com.iris.assistant;

/**
 * Aggregated summary of a conversation thread in the notification vault.
 *
 * Groups messages by chat participant or group channel, surfacing volume,
 * unread status, incriminating/deleted counts, and attachment breakdown.
 */
public final class ConversationGroup {

    public final String pkg;
    public final String appLabel;
    public final String groupName;
    public final String threadTitle;
    public final int totalCount;
    public final int flaggedCount;
    public final int unreadCount;
    public final int photoCount;
    public final int videoCount;
    public final int audioCount;
    public final int docCount;
    public final long lastPostTime;
    public final String lastBody;
    public final String lastSender;

    public ConversationGroup(String pkg, String appLabel, String groupName, String threadTitle,
                             int totalCount, int flaggedCount, int unreadCount,
                             int photoCount, int videoCount, int audioCount, int docCount,
                             long lastPostTime, String lastBody, String lastSender) {
        this.pkg = pkg != null ? pkg : "";
        this.appLabel = appLabel != null ? appLabel : "";
        this.groupName = groupName != null ? groupName : "";
        this.threadTitle = threadTitle != null ? threadTitle : "";
        this.totalCount = totalCount;
        this.flaggedCount = flaggedCount;
        this.unreadCount = unreadCount;
        this.photoCount = photoCount;
        this.videoCount = videoCount;
        this.audioCount = audioCount;
        this.docCount = docCount;
        this.lastPostTime = lastPostTime;
        this.lastBody = lastBody != null ? lastBody : "";
        this.lastSender = lastSender != null ? lastSender : "";
    }

    /** Whether this conversation represents a group chat. */
    public boolean isGroup() {
        return groupName != null && !groupName.isEmpty();
    }

    /** Whether this conversation contains flagged / deleted evidence. */
    public boolean hasFlagged() {
        return flaggedCount > 0;
    }

    /** Whether this conversation has unread / unseen messages. */
    public boolean hasUnread() {
        return unreadCount > 0;
    }

    /** Display title for this conversation thread. */
    public String displayName() {
        if (!threadTitle.isEmpty()) return threadTitle;
        if (!groupName.isEmpty()) return groupName;
        return "Unknown Contact";
    }

    /** Display name for the source messaging application. */
    public String displayApp() {
        if (!appLabel.isEmpty()) return appLabel;
        if (pkg.contains("whatsapp")) return "WhatsApp";
        if (pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")) return "Messages";
        return "App";
    }
}
