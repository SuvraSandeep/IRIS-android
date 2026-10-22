package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pending reminders set by incoming messages, and the "waiting for a time" state.
 *
 * Its own preferences file, so clearing reminders never touches the responder's settings or its
 * decision log. Bounded: a cap on stored reminders stops a flood of "remind me" texts filling the
 * file. Dated and undated reminders live together; an undated one has dueMs == 0 and simply shows
 * in the list for the owner to see, which is the agreed behaviour when no time could be parsed.
 */
public final class ReminderStore {

    private static final String PREFS = "iris_reminders";
    private static final String KEY = "reminders";
    private static final String P_AWAIT = "await_";

    /** Enough to hold a realistic backlog; old fired ones are pruned first. */
    public static final int MAX = 100;

    private static final String FIELD = "\u001f";
    private static final String RECORD = "\u001e";

    /** One reminder. */
    public static final class Reminder {
        public long id;
        public String task = "";
        public String sender = "";
        public long dueMs;       // 0 == undated
        public long createdMs;
        public boolean fired;

        public boolean undated() { return dueMs <= 0; }
    }

    private final SharedPreferences prefs;

    public ReminderStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Add a reminder and return its id (its creation time). */
    public long add(String task, String sender, long dueMs, long nowMs) {
        List<Reminder> items = all();
        Reminder r = new Reminder();
        r.id = nowMs;
        r.task = task == null ? "" : task;
        r.sender = sender == null ? "" : sender;
        r.dueMs = Math.max(0, dueMs);
        r.createdMs = nowMs;
        r.fired = false;
        items.add(r);
        // Prune: drop the oldest fired reminders first, then the oldest of anything.
        while (items.size() > MAX) {
            int victim = -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).fired) { victim = i; break; }
            }
            items.remove(victim >= 0 ? victim : 0);
        }
        write(items);
        return r.id;
    }

    public List<Reminder> all() {
        List<Reminder> out = new ArrayList<>();
        String raw = prefs.getString(KEY, "");
        if (raw.isEmpty()) return out;
        for (String rec : raw.split(RECORD)) {
            String[] p = rec.split(FIELD, -1);
            if (p.length < 6) continue;
            try {
                Reminder r = new Reminder();
                r.id = Long.parseLong(p[0]);
                r.task = p[1];
                r.sender = p[2];
                r.dueMs = Long.parseLong(p[3]);
                r.createdMs = Long.parseLong(p[4]);
                r.fired = "1".equals(p[5]);
                out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Pending (not yet fired) reminders, soonest due first, undated last. */
    public List<Reminder> pending() {
        List<Reminder> out = new ArrayList<>();
        for (Reminder r : all()) if (!r.fired) out.add(r);
        Collections.sort(out, (a, b) -> {
            if (a.undated() != b.undated()) return a.undated() ? 1 : -1;
            return Long.compare(a.dueMs, b.dueMs);
        });
        return out;
    }

    public void remove(long id) {
        List<Reminder> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) { items.remove(i); break; }
        }
        write(items);
    }

    public void markFired(long id) {
        List<Reminder> items = all();
        for (Reminder r : items) if (r.id == id) r.fired = true;
        write(items);
    }

    public void clear() { prefs.edit().remove(KEY).apply(); }

    /**
     * Schedule the system alarm that will fire ReminderReceiver at the due time.
     *
     * Mirrors the scheduling the voice side already uses, kept as a static helper so the responder
     * and the voice path schedule reminders identically. The reminder id is the request code, so a
     * later cancel or reschedule of the same reminder replaces rather than duplicates. Falls back
     * to an inexact alarm when exact alarms aren't permitted, because a reminder that fires a few
     * minutes late is far better than one the OS refuses to set at all.
     */
    public static void schedule(Context context, long id, String task, long triggerAtMs) {
        if (context == null || triggerAtMs <= 0) return;
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            android.content.Intent i = new android.content.Intent(context, ReminderReceiver.class);
            i.putExtra(ReminderReceiver.EXTRA_TEXT, task == null ? "" : task);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    context, (int) (id & 0x7fffffff), i, flags);
            boolean exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
                    || am.canScheduleExactAlarms();
            if (exact) {
                try {
                    am.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                } catch (SecurityException se) {
                    am.setWindow(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, 60_000L, pi);
                }
            } else {
                try {
                    am.setWindow(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, 120_000L, pi);
                } catch (Throwable t) {
                    am.setAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                }
            }
        } catch (Throwable ignored) {
            // A failure to schedule leaves the reminder stored and visible in the list, so it is
            // not lost; it simply won't fire an alarm. The list is the backstop.
        }
    }

    /** Cancel a previously scheduled alarm for this reminder id. */
    public static void cancel(Context context, long id) {
        if (context == null) return;
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            android.content.Intent i = new android.content.Intent(context, ReminderReceiver.class);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    context, (int) (id & 0x7fffffff), i, flags);
            am.cancel(pi);
        } catch (Throwable ignored) { }
    }

    // ─── "Waiting for a time" state, keyed per conversation ───
    //
    // When a reminder arrives with no parseable time, IRIS asks the sender when, and remembers the
    // task against that conversation. The sender's next message is then read as the time. Kept
    // here rather than in the responder settings so a reminder's whole lifecycle lives in one
    // place.

    public void setAwaitingTime(String conversationKey, String task) {
        prefs.edit().putString(P_AWAIT + safe(conversationKey), task == null ? "" : task).apply();
    }

    public String awaitingTask(String conversationKey) {
        return prefs.getString(P_AWAIT + safe(conversationKey), "");
    }

    public void clearAwaiting(String conversationKey) {
        prefs.edit().remove(P_AWAIT + safe(conversationKey)).apply();
    }

    // ─── Last reminder set in a conversation, for edit/cancel follow-ups ("make it 6") ───

    private static final String P_LAST = "last_";

    /** Remember the reminder just set in this conversation, with the time, for follow-ups. */
    public void setLastReminder(String conversationKey, long id, long nowMs) {
        prefs.edit().putString(P_LAST + safe(conversationKey), id + "\u001f" + nowMs).apply();
    }

    /** The reminder id set in this conversation within the follow-up window, or 0. */
    public long lastReminderId(String conversationKey, long nowMs) {
        String v = prefs.getString(P_LAST + safe(conversationKey), "");
        if (v.isEmpty()) return 0;
        String[] p = v.split("\u001f");
        if (p.length < 2) return 0;
        try {
            long id = Long.parseLong(p[0]);
            long when = Long.parseLong(p[1]);
            if (nowMs - when > ConversationContext.FOLLOW_UP_WINDOW_MS) return 0;
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    public void clearLastReminder(String conversationKey) {
        prefs.edit().remove(P_LAST + safe(conversationKey)).apply();
    }

    /** The task text of a stored reminder by id, or empty. */
    public String taskOf(long id) {
        for (Reminder r : all()) {
            if (r.id == id) return r.task;
        }
        return "";
    }

    private void write(List<Reminder> items) {
        StringBuilder out = new StringBuilder();
        for (Reminder r : items) {
            if (out.length() > 0) out.append(RECORD);
            out.append(r.id).append(FIELD)
                    .append(clean(r.task)).append(FIELD)
                    .append(clean(r.sender)).append(FIELD)
                    .append(r.dueMs).append(FIELD)
                    .append(r.createdMs).append(FIELD)
                    .append(r.fired ? "1" : "0");
        }
        prefs.edit().putString(KEY, out.toString()).apply();
    }

    private static String clean(String v) {
        if (v == null) return "";
        return v.replace(FIELD, " ").replace(RECORD, " ").trim();
    }

    private static String safe(String raw) {
        if (raw == null) return "unknown";
        String c = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (c.isEmpty()) return "unknown";
        return c.length() <= 64 ? c : c.substring(0, 64);
    }
}
