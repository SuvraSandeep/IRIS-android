package com.iris.assistant;

/**
 * Decides when to ask Android to rebind the notification reader.
 *
 * The reader is bound by the system, so it survives the app closing and a reboot. What it does not
 * reliably survive is an OEM battery manager deciding to kill the binding. Android reports a clean
 * disconnect through onListenerDisconnected, and that path already requests a rebind -- but a
 * process killed outright never gets to report anything. Until this class existed, such a kill went
 * unnoticed until the owner happened to open the Vault and read the status line.
 *
 * Deliberately pure, with no Android imports, so every timing and backoff decision is checked
 * offline. The Android side only supplies the clock and performs the rebind.
 *
 * Backoff matters: a rebind request the system refuses must not be retried in a tight loop, or the
 * watchdog becomes the battery problem it is trying to survive. Intervals grow from one minute to a
 * thirty-minute ceiling and stop growing there, so recovery is still attempted indefinitely but
 * cheaply.
 */
public final class ListenerWatchdog {

    /** How often the foreground service wakes to check. Coarse on purpose: this is not urgent. */
    public static final long CHECK_INTERVAL_MS = 15 * 60 * 1000L;

    /** Shortest gap between two rebind requests. */
    public static final long FIRST_RETRY_MS = 60 * 1000L;

    /** Longest gap between two rebind requests. Reached after a few failures and held. */
    public static final long MAX_RETRY_MS = 30 * 60 * 1000L;

    /** Below this, a disconnect is treated as a normal blip and not reported to the owner. */
    public static final long REPORT_AFTER_MS = 10 * 60 * 1000L;

    public enum Action {
        /** Connected, or waiting out the backoff. */
        NOTHING,
        /** Disconnected and the backoff has elapsed: ask the system to bind us again. */
        REQUEST_REBIND
    }

    private ListenerWatchdog() { }

    /**
     * Whether to request a rebind right now.
     *
     * @param connected       whether the reader is currently bound
     * @param accessGranted   whether the owner has granted notification access
     * @param nowMs           current time
     * @param lastRequestAtMs when a rebind was last requested, or 0 if never
     * @param failures        consecutive checks that have found us disconnected
     */
    public static Action decide(boolean connected, boolean accessGranted,
                                long nowMs, long lastRequestAtMs, int failures) {
        if (connected) return Action.NOTHING;

        // Without the grant a rebind request is ignored by the system. Skipping it avoids
        // pointless work and, more importantly, avoids looking like an app trying to nag its way
        // back into a permission the owner removed on purpose.
        if (!accessGranted) return Action.NOTHING;

        // Never requested before, so there is no backoff to respect.
        if (lastRequestAtMs <= 0) return Action.REQUEST_REBIND;

        long since = nowMs - lastRequestAtMs;

        // A clock that moved backwards (timezone change, NTP correction, owner editing the date)
        // would otherwise make `since` negative and freeze the watchdog forever. Treat it as due:
        // an extra rebind request is harmless, a permanently disabled watchdog is not.
        if (since < 0) return Action.REQUEST_REBIND;

        return since >= backoffMs(failures) ? Action.REQUEST_REBIND : Action.NOTHING;
    }

    /**
     * Required gap before the next rebind request, doubling per failure up to the ceiling.
     *
     * Negative or zero failure counts clamp to the shortest gap rather than producing a nonsense
     * interval, on the same principle used elsewhere in the archive: bad input must never disable
     * the safety behaviour.
     */
    public static long backoffMs(int failures) {
        if (failures <= 1) return FIRST_RETRY_MS;
        long delay = FIRST_RETRY_MS;
        for (int i = 1; i < failures; i++) {
            delay *= 2;
            if (delay >= MAX_RETRY_MS) return MAX_RETRY_MS;
        }
        return Math.min(delay, MAX_RETRY_MS);
    }

    /** Whether a disconnect has lasted long enough to be worth telling the owner about. */
    public static boolean shouldReport(long blindSinceMs, long nowMs) {
        if (blindSinceMs <= 0) return false;
        long blind = nowMs - blindSinceMs;
        return blind >= REPORT_AFTER_MS;
    }

    /**
     * How long capture has been down, in IRIS's voice.
     *
     * Reads as a duration rather than a timestamp because the useful question is "how much did I
     * miss", not "when exactly did Android lose interest".
     */
    public static String blindMessage(long blindSinceMs, long nowMs) {
        if (!shouldReport(blindSinceMs, nowMs)) return "";
        long minutes = (nowMs - blindSinceMs) / 60000L;
        String span;
        if (minutes < 120) {
            span = minutes + " minutes";
        } else {
            long hours = minutes / 60;
            span = hours < 48 ? hours + " hours" : (hours / 24) + " days";
        }
        return "I have been unplugged for " + span + " and I am still asking to be let back in. "
                + "Something on this phone is killing me off \u2014 check that IRIS is excluded "
                + "from battery optimisation.";
    }
}
