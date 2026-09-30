package com.iris.assistant;

/**
 * Proactive suggestions (INTELLIGENCE-ROADMAP §2.3).
 *
 * Decides whether IRIS has something genuinely worth volunteering, and phrases it. Pure and
 * Android-free: the caller passes in the facts it already has (pending notifications, missed
 * calls, battery, charging state, hour) so the policy can be unit-tested offline.
 *
 * Design rules, chosen so this can never become annoying - which is the usual failure mode of
 * "proactive" assistant features:
 *   - OPT-IN. The caller only consults this when the owner enabled suggestions.
 *   - AT MOST ONE suggestion, highest priority only. Never a list.
 *   - Only facts that are ACTIONABLE right now; no "did you know" filler.
 *   - Silent during quiet hours (reuses CallTiming's window) so it never speaks up at night.
 *   - A cooldown is enforced by the caller passing minutesSinceLast; this class just respects
 *     it. Nothing here schedules or repeats anything on its own.
 */
public final class ProactiveSuggestion {

    /** Do not volunteer anything more often than this. */
    public static final int COOLDOWN_MINUTES = 90;
    /** Below this, a low-battery nudge is worth making. */
    public static final int LOW_BATTERY_PERCENT = 15;

    public enum Kind { NONE, MISSED_CALLS, NOTIFICATIONS, LOW_BATTERY }

    public final Kind kind;
    public final String text;

    private ProactiveSuggestion(Kind kind, String text) {
        this.kind = kind;
        this.text = text == null ? "" : text;
    }

    private static final ProactiveSuggestion NONE = new ProactiveSuggestion(Kind.NONE, "");

    public boolean any() { return kind != Kind.NONE; }

    /** Facts the policy needs. All independently optional - use -1 for "unknown". */
    public static final class Context {
        public int hourOfDay = -1;
        public int minutesSinceLast = -1;
        public int missedCalls = 0;
        public int pendingNotifications = 0;
        public int batteryPercent = -1;
        public boolean charging = false;
        public boolean screenJustUnlocked = false;
    }

    /**
     * The single most useful thing to say, or NONE.
     *
     * Priority order is deliberate: a missed call is time-sensitive and personal, a dying
     * battery is time-sensitive and practical, and a notification pile is neither, so it comes
     * last and needs a meaningful backlog before it is worth mentioning at all.
     */
    public static ProactiveSuggestion decide(Context c) {
        if (c == null) return NONE;
        // Respect the cooldown. minutesSinceLast < 0 means "never suggested", which is allowed.
        if (c.minutesSinceLast >= 0 && c.minutesSinceLast < COOLDOWN_MINUTES) return NONE;
        // Never volunteer anything during the quiet window.
        if (c.hourOfDay >= 0 && CallTiming.isQuietHour(c.hourOfDay)) return NONE;

        if (c.missedCalls > 0) {
            String t = c.missedCalls == 1
                    ? "You have a missed call. Want to hear who?"
                    : "You have " + c.missedCalls + " missed calls. Want to hear who?";
            return new ProactiveSuggestion(Kind.MISSED_CALLS, t);
        }
        // Only worth mentioning while NOT charging - plugged in, it is not actionable.
        if (!c.charging && c.batteryPercent >= 0 && c.batteryPercent <= LOW_BATTERY_PERCENT) {
            return new ProactiveSuggestion(Kind.LOW_BATTERY,
                    "Battery is at " + c.batteryPercent + " percent. Worth charging soon.");
        }
        // A backlog only counts as news if it is more than a couple of items.
        if (c.pendingNotifications >= 5) {
            return new ProactiveSuggestion(Kind.NOTIFICATIONS,
                    c.pendingNotifications + " notifications are waiting. Want a summary?");
        }
        return NONE;
    }

    @Override public String toString() { return kind + (text.isEmpty() ? "" : ": " + text); }
}
