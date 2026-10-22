package com.iris.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Stored state for the auto-responder: settings, per-contact rules, edited text and daily counters.
 *
 * Kept in its own preferences file rather than inside AppSettings for one concrete reason: the
 * owner asked for everything to be customisable, and anything customisable needs a way back.
 * Reset-to-defaults is a single clear() of this file, which is only safe because nothing else
 * lives in it.
 *
 * Every value here has a shipped default taken from the pure rule classes, which are the single
 * source of truth for what those defaults are. This class stores overrides; it does not invent
 * policy.
 */
public final class ResponderSettings {

    private static final String PREFS = "iris_responder";

    // Keys. Prefixed by purpose so a targeted reset can match on the prefix.
    private static final String K_STATE = "state";
    private static final String K_ARMED_UNTIL = "armed_until";
    private static final String K_ARMED_INDEFINITE = "armed_indefinite";
    private static final String K_DISARM_AFTER = "disarm_after_ms";
    private static final String K_GROUPS = "reply_groups";
    private static final String K_PERSONAL = "reply_personal";
    private static final String K_REQUIRE_MENTION = "groups_require_mention";
    private static final String K_UNSAVED = "reply_unsaved";
    private static final String K_REPLY_IN_USE = "reply_in_use";
    private static final String K_REPLY_EVERY = "reply_every";
    private static final String K_KEEP_UNREAD = "keep_unread";
    private static final String K_MODE = "mode";
    private static final String K_REMINDER_SCOPE = "reminder_scope";
    private static final String K_FAMILY_RING = "family_ring";
    private static final String K_APPS = "apps";
    private static final String K_QUIET_START = "quiet_start";
    private static final String K_QUIET_END = "quiet_end";
    private static final String K_WORK_START = "work_start";
    private static final String K_WORK_END = "work_end";
    private static final String K_WORK_TONE_ON = "work_tone_on";
    private static final String K_COOLDOWN = "cooldown_ms";
    private static final String K_CONTACT_CAP = "contact_cap";
    private static final String K_DAILY_CAP = "daily_cap";
    private static final String K_DEFAULT_TONE = "default_tone";
    private static final String K_MENTION_WORDS = "mention_words";
    private static final String K_NAME_PATTERNS = "name_patterns";
    private static final String K_ALLOWLIST_ONLY = "allowlist_only";
    private static final String K_URGENT_ONLY = "urgent_only";
    private static final String K_ROTATION = "rotation";
    private static final String K_COUNTER_DAY = "counter_day";
    private static final String K_REPLIES_TODAY = "replies_today";

    private static final String P_CATEGORY = "cat_";
    private static final String P_TEMPLATE = "tpl_";
    private static final String P_CONTACT_RULE = "crule_";
    private static final String P_CONTACT_RELATION = "crel_";
    private static final String P_CONTACT_LABEL = "clabel_";
    private static final String P_CONTACT_TONE = "ctone_";
    private static final String P_NAME_RULE = "nrule_";
    private static final String P_NAME_RELATION = "nrel_";
    private static final String P_NAME_LABEL = "nlabel_";
    private static final String P_CONV_LAST = "clast_";
    private static final String P_CONV_COUNT = "ccount_";
    private static final String P_CONV_IRIS = "ciris_";
    private static final String P_CONV_TEXT = "ctext_";
    // Rapid-incoming tracking (for implicit-urgency burst detection) and running banter count
    // (for the N-message auto-snooze). Both are per-conversation.
    private static final String P_CONV_INWINDOW = "cinwin_";   // "count:windowStartMs"
    private static final String P_CONV_BANTER = "cbanter_";    // consecutive answered banter msgs
    private static final String P_CONV_INTRO = "cintro_";
    private static final String P_RING_WORD = "cring_";

    /** Shipped default auto-disarm, 12 hours. */
    public static final long DISARM_AFTER_DEFAULT_MS = 12L * 60 * 60 * 1000;

    /** Shipped default cooldown between replies to one conversation, 2 hours. */
    public static final long COOLDOWN_DEFAULT_MS = 2L * 60 * 60 * 1000;

    public static final int CONTACT_CAP_DEFAULT = 3;
    public static final int DAILY_CAP_DEFAULT = 30;

    private final SharedPreferences prefs;

    public ResponderSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ─── Arming ───

    public ResponderGuards.State state() {
        String raw = prefs.getString(K_STATE, ResponderGuards.State.OFF.name());
        try {
            return ResponderGuards.State.valueOf(raw);
        } catch (Exception ignored) {
            // Corrupt value falls back to OFF, never to LIVE. A storage fault must not be able to
            // start IRIS sending messages.
            return ResponderGuards.State.OFF;
        }
    }

    /**
     * Change state, stamping the disarm time when arming.
     *
     * The disarm time is computed here rather than at read time so it survives the app being
     * killed: an armed period that restarted on every process launch would never expire, which
     * defeats the whole point of the timer.
     */
    public void setState(ResponderGuards.State newState, long nowMs) {
        SharedPreferences.Editor editor = prefs.edit().putString(K_STATE, newState.name());
        if (newState == ResponderGuards.State.OFF) {
            editor.putLong(K_ARMED_UNTIL, 0);
        } else if (!armedIndefinitely()) {
            editor.putLong(K_ARMED_UNTIL, nowMs + disarmAfterMs());
        }
        editor.apply();
    }

    public long armedUntilMs() { return prefs.getLong(K_ARMED_UNTIL, 0); }

    public boolean armedIndefinitely() { return prefs.getBoolean(K_ARMED_INDEFINITE, false); }

    public void setArmedIndefinitely(boolean value) {
        prefs.edit().putBoolean(K_ARMED_INDEFINITE, value).apply();
    }

    public long disarmAfterMs() {
        long stored = prefs.getLong(K_DISARM_AFTER, DISARM_AFTER_DEFAULT_MS);
        return stored > 0 ? stored : DISARM_AFTER_DEFAULT_MS;
    }

    public void setDisarmAfterMs(long value) {
        prefs.edit().putLong(K_DISARM_AFTER, Math.max(60_000L, value)).apply();
    }

    // ─── Scope ───

    public boolean replyToGroups() { return prefs.getBoolean(K_GROUPS, false); }
    public void setReplyToGroups(boolean v) { prefs.edit().putBoolean(K_GROUPS, v).apply(); }

    public boolean replyToPersonal() { return prefs.getBoolean(K_PERSONAL, true); }
    public void setReplyToPersonal(boolean v) { prefs.edit().putBoolean(K_PERSONAL, v).apply(); }

    public boolean groupsRequireMention() {
        return prefs.getBoolean(K_REQUIRE_MENTION, true);
    }

    public void setGroupsRequireMention(boolean v) {
        prefs.edit().putBoolean(K_REQUIRE_MENTION, v).apply();
    }

    /**
     * Whether senders not in the contact store are answered. Default false.
     *
     * The default matters more than it looks. An unknown sender is as likely to be a courier, a
     * bank shortcode or a spammer as a person, and replying tells a stranger the phone is live and
     * unattended.
     */
    public boolean replyToUnsaved() { return prefs.getBoolean(K_UNSAVED, false); }
    public void setReplyToUnsaved(boolean v) { prefs.edit().putBoolean(K_UNSAVED, v).apply(); }

    public boolean allowlistOnly() { return prefs.getBoolean(K_ALLOWLIST_ONLY, false); }
    public void setAllowlistOnly(boolean v) {
        prefs.edit().putBoolean(K_ALLOWLIST_ONLY, v).apply();
    }

    public boolean urgentOnly() { return prefs.getBoolean(K_URGENT_ONLY, false); }
    public void setUrgentOnly(boolean v) {
        prefs.edit().putBoolean(K_URGENT_ONLY, v).apply();
    }

    /**
     * Whether to answer even while Suvra is using the phone. Default false.
     *
     * Added after device testing. The default defers to him -- if he is holding the phone he can
     * answer better than IRIS can, and a reply landing while he is already typing is worse than
     * silence. But a phone that sits awake on a desk all day meant nothing was ever answered, and
     * that is his call to make, not a rule to enforce.
     */
    public boolean replyWhileInUse() { return prefs.getBoolean(K_REPLY_IN_USE, false); }
    public void setReplyWhileInUse(boolean v) {
        prefs.edit().putBoolean(K_REPLY_IN_USE, v).apply();
    }

    /**
     * Answer every message in a burst rather than one per burst. Default false.
     *
     * The safe default is one reply per flurry: a single notification can carry seven messages.
     * The owner asked for a switch to answer all of them, so this lifts the burst and same-text
     * guards; the cooldown and daily caps still bound it.
     */
    public boolean replyToEveryMessage() { return prefs.getBoolean(K_REPLY_EVERY, false); }
    public void setReplyToEveryMessage(boolean v) {
        prefs.edit().putBoolean(K_REPLY_EVERY, v).apply();
    }

    /**
     * Keep a message unread after IRIS replies, so the owner still sees which chats to open.
     *
     * ON by default, which matches what IRIS already does: it never marks a chat read and never
     * dismisses the notification. Turning it OFF opts in to triggering the app's own "mark as read"
     * action after a live reply, where the app offers one.
     */
    public boolean keepUnread() { return prefs.getBoolean(K_KEEP_UNREAD, true); }
    public void setKeepUnread(boolean v) {
        prefs.edit().putBoolean(K_KEEP_UNREAD, v).apply();
    }

    /**
     * A per-app default tone (e.g. professional on SMS, casual on WhatsApp), or null when the
     * owner has set none for that app. Keyed by the messaging channel, so it applies however the
     * sender is saved.
     */
    public ReplyTone.Tone appTone(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        String direct = prefs.getString(P_APP_TONE + pkg.trim(), "");
        if (!direct.isEmpty()) return ReplyTone.parseTone(direct);
        MessagingApp app = MessagingApp.fromPackage(pkg);
        if (app != MessagingApp.OTHER) {
            String v = prefs.getString(P_APP_TONE + app.name(), "");
            if (!v.isEmpty()) return ReplyTone.parseTone(v);
        }
        return null;
    }

    public ReplyTone.Tone appTone(MessagingApp app) {
        if (app == null) return null;
        String v = prefs.getString(P_APP_TONE + app.name(), "");
        return v.isEmpty() ? null : ReplyTone.parseTone(v);
    }

    public void setAppTone(String pkg, ReplyTone.Tone tone) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        String p = pkg.trim();
        MessagingApp app = MessagingApp.fromPackage(p);
        if (tone == null) {
            prefs.edit().remove(P_APP_TONE + p).apply();
            if (app != MessagingApp.OTHER) {
                prefs.edit().remove(P_APP_TONE + app.name()).apply();
            }
        } else {
            prefs.edit().putString(P_APP_TONE + p, tone.name()).apply();
            if (app != MessagingApp.OTHER) {
                prefs.edit().putString(P_APP_TONE + app.name(), tone.name()).apply();
            }
        }
    }

    public void setAppTone(MessagingApp app, ReplyTone.Tone tone) {
        if (app == null) return;
        if (tone == null) prefs.edit().remove(P_APP_TONE + app.name()).apply();
        else prefs.edit().putString(P_APP_TONE + app.name(), tone.name()).apply();
    }

    private static final String P_APP_TONE = "app_tone_";

    /**
     * Auto-activate SLEEPING mode during quiet hours, so urgent senders are told to call rather
     * than wait. Off by default; the one self-activating mode that needs no extra permission.
     */
    public boolean autoSleepInQuietHours() { return prefs.getBoolean(K_AUTO_SLEEP, false); }
    public void setAutoSleepInQuietHours(boolean v) {
        prefs.edit().putBoolean(K_AUTO_SLEEP, v).apply();
    }
    private static final String K_AUTO_SLEEP = "auto_sleep_quiet";

    /**
     * Auto-activate DRIVING mode when connected to vehicle Bluetooth head unit or car kit.
     * Off by default.
     */
    public boolean autoDriving() { return prefs.getBoolean(K_AUTO_DRIVING, false); }
    public void setAutoDriving(boolean v) {
        prefs.edit().putBoolean(K_AUTO_DRIVING, v).apply();
    }
    private static final String K_AUTO_DRIVING = "auto_driving_bt";

    /**
     * Auto-activate MEETING mode when the owner has a calendar event marked BUSY in progress.
     * Off by default.
     */
    public boolean autoMeeting() { return prefs.getBoolean(K_AUTO_MEETING, false); }
    public void setAutoMeeting(boolean v) {
        prefs.edit().putBoolean(K_AUTO_MEETING, v).apply();
    }
    private static final String K_AUTO_MEETING = "auto_meeting_calendar";

    /**
     * The situational mode the owner has switched on, or NONE.
     *
     * A mode overrides the category content and the time windows while it is active. Stored by
     * name and parsed back through ResponderMode.parse, which falls to NONE on anything corrupt,
     * so a bad value can only ever mean "no mode" and never a wrong one.
     */
    public ResponderMode mode() {
        return ResponderMode.parse(prefs.getString(K_MODE, ResponderMode.NONE.name()));
    }

    public void setMode(ResponderMode mode) {
        prefs.edit().putString(K_MODE,
                (mode == null ? ResponderMode.NONE : mode).name()).apply();
    }

    /**
     * Who may set a reminder by texting "remind me / don't forget".
     *
     * CONTACTS by default, as the owner chose: a stranger must not be able to put an alarm on the
     * phone. ANYONE and FAMILY are the other options. Stored as the enum name; a corrupt value
     * falls back to CONTACTS, the safe middle.
     */
    public enum ReminderScope { ANYONE, CONTACTS, FAMILY }

    public ReminderScope reminderScope() {
        String raw = prefs.getString(K_REMINDER_SCOPE, ReminderScope.CONTACTS.name());
        try { return ReminderScope.valueOf(raw); }
        catch (Exception ignored) { return ReminderScope.CONTACTS; }
    }

    public void setReminderScope(ReminderScope scope) {
        prefs.edit().putString(K_REMINDER_SCOPE,
                (scope == null ? ReminderScope.CONTACTS : scope).name()).apply();
    }

    /** Whether this sender may set a reminder, given the scope and what's known about them. */
    public boolean reminderAllowed(boolean savedContact, ReplyTone.Relation relation) {
        switch (reminderScope()) {
            case ANYONE: return true;
            case FAMILY: return relation == ReplyTone.Relation.FAMILY;
            default: return savedContact || relation == ReplyTone.Relation.FAMILY
                    || relation == ReplyTone.Relation.WORK
                    || relation == ReplyTone.Relation.FRIEND;
        }
    }

    // ─── Family ring ───
    //
    // A saved FAMILY contact who marks a message urgent can be given a word to reply that makes
    // the phone ring loudly, overriding silent and Do Not Disturb. Every family contact can do
    // this, with no per-day limit, and it is not tied to any mode -- family get full priority, as
    // the owner asked. The one control is a master switch; when it is off, IRIS tells the sender
    // to call directly instead.

    /** Whether the family-ring capability is on. Default true. */
    public boolean familyRingEnabled() { return prefs.getBoolean(K_FAMILY_RING, true); }
    public void setFamilyRingEnabled(boolean v) {
        prefs.edit().putBoolean(K_FAMILY_RING, v).apply();
    }

    /**
     * The word IRIS is currently waiting for from a conversation to trigger a ring, or empty.
     *
     * Set when IRIS offers the ring to a family contact; checked against their next message. The
     * two-step confirm is what makes the ring safe: a single urgent message never rings, only a
     * deliberate second reply of the exact word does.
     */
    public String pendingRingWord(String conversationKey) {
        return prefs.getString(P_RING_WORD + safe(conversationKey), "");
    }

    public void setPendingRingWord(String conversationKey, String word) {
        prefs.edit().putString(P_RING_WORD + safe(conversationKey),
                word == null ? "" : word).apply();
    }

    public void clearPendingRingWord(String conversationKey) {
        prefs.edit().remove(P_RING_WORD + safe(conversationKey)).apply();
    }

    // ─── Apps the responder may speak through ───

    /**
     * Packages the responder is allowed to reply through. Default EMPTY.
     *
     * Deliberately separate from the archive's ticked-app list. Those answer different questions:
     * the archive's is "keep a record of this app", this one is "send messages through this app on
     * my behalf". Sharing one list would mean ticking Instagram for archiving silently authorised
     * outgoing Instagram messages.
     */
    public Set<String> apps() {
        Set<String> out = new LinkedHashSet<>();
        String raw = prefs.getString(K_APPS, null);
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public void setApps(Set<String> packages) {
        StringBuilder joined = new StringBuilder();
        if (packages != null) {
            for (String pkg : packages) {
                if (pkg == null || pkg.trim().isEmpty()) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(pkg.trim());
            }
        }
        prefs.edit().putString(K_APPS, joined.toString()).apply();
    }

    public boolean allowsApp(String pkg) {
        return pkg != null && apps().contains(pkg);
    }

    // ─── Time windows ───

    public int quietStart() { return minute(K_QUIET_START, ResponderWindow.QUIET_START_DEFAULT); }
    public int quietEnd() { return minute(K_QUIET_END, ResponderWindow.QUIET_END_DEFAULT); }
    public int workStart() { return minute(K_WORK_START, ResponderWindow.WORK_START_DEFAULT); }
    public int workEnd() { return minute(K_WORK_END, ResponderWindow.WORK_END_DEFAULT); }

    public void setQuietHours(int startMinute, int endMinute) {
        prefs.edit().putInt(K_QUIET_START, startMinute).putInt(K_QUIET_END, endMinute).apply();
    }

    public void setWorkHours(int startMinute, int endMinute) {
        prefs.edit().putInt(K_WORK_START, startMinute).putInt(K_WORK_END, endMinute).apply();
    }

    public boolean workHoursForceProfessional() {
        return prefs.getBoolean(K_WORK_TONE_ON, true);
    }

    public void setWorkHoursForceProfessional(boolean v) {
        prefs.edit().putBoolean(K_WORK_TONE_ON, v).apply();
    }

    /** Window list for the tone resolver, built from the stored work hours. */
    public List<ResponderWindow.Window> toneWindows() {
        List<ResponderWindow.Window> out = new ArrayList<>();
        if (workHoursForceProfessional()) {
            out.add(new ResponderWindow.Window("work", workStart(), workEnd(),
                    ReplyTone.Tone.PROFESSIONAL));
        }
        return out;
    }

    private int minute(String key, int fallback) {
        int stored = prefs.getInt(key, fallback);
        // A stored value outside the day is treated as unset rather than passed on, so a corrupt
        // write cannot silently disable quiet hours.
        return ResponderWindow.isValidMinute(stored) ? stored : fallback;
    }

    // ─── Limits ───

    public long cooldownMs() {
        long stored = prefs.getLong(K_COOLDOWN, COOLDOWN_DEFAULT_MS);
        return stored >= 0 ? stored : COOLDOWN_DEFAULT_MS;
    }

    public void setCooldownMs(long value) {
        prefs.edit().putLong(K_COOLDOWN, Math.max(0, value)).apply();
    }

    public int contactCapPerDay() { return prefs.getInt(K_CONTACT_CAP, CONTACT_CAP_DEFAULT); }
    public void setContactCapPerDay(int v) {
        prefs.edit().putInt(K_CONTACT_CAP, Math.max(0, v)).apply();
    }

    public int dailyCapTotal() { return prefs.getInt(K_DAILY_CAP, DAILY_CAP_DEFAULT); }
    public void setDailyCapTotal(int v) {
        prefs.edit().putInt(K_DAILY_CAP, Math.max(0, v)).apply();
    }

    // ─── Tone ───

    public ReplyTone.Tone defaultTone() {
        return ReplyTone.parseTone(prefs.getString(K_DEFAULT_TONE,
                ReplyTone.Tone.PROFESSIONAL.name()));
    }

    public void setDefaultTone(ReplyTone.Tone tone) {
        prefs.edit().putString(K_DEFAULT_TONE,
                (tone == null ? ReplyTone.Tone.PROFESSIONAL : tone).name()).apply();
    }

    private static final String P_RELATION_TONE = "responder_relation_tone_";

    /**
     * Custom tone configured for a relationship category (FAMILY, WORK, FRIEND), or null if set to
     * inherit the global default tone, or the relationship default if unconfigured.
     */
    public ReplyTone.Tone relationTone(ReplyTone.Relation relation) {
        if (relation == null || relation == ReplyTone.Relation.UNKNOWN) return null;
        String val = prefs.getString(P_RELATION_TONE + relation.name(), null);
        if (val == null) {
            return ReplyTone.toneFor(relation);
        }
        if ("DEFAULT".equalsIgnoreCase(val) || "INHERIT".equalsIgnoreCase(val)) {
            return null; // Follow global default tone
        }
        return ReplyTone.parseTone(val);
    }

    /**
     * Stored configuration key for a relation tone: either a Tone name (e.g. "SARCASTIC") or "DEFAULT".
     */
    public String relationToneRaw(ReplyTone.Relation relation) {
        if (relation == null || relation == ReplyTone.Relation.UNKNOWN) return "DEFAULT";
        String val = prefs.getString(P_RELATION_TONE + relation.name(), null);
        if (val == null) {
            ReplyTone.Tone def = ReplyTone.toneFor(relation);
            return def != null ? def.name() : "DEFAULT";
        }
        return val;
    }

    public void setRelationTone(ReplyTone.Relation relation, ReplyTone.Tone tone) {
        if (relation == null || relation == ReplyTone.Relation.UNKNOWN) return;
        if (tone == null) {
            prefs.edit().putString(P_RELATION_TONE + relation.name(), "DEFAULT").apply();
        } else {
            prefs.edit().putString(P_RELATION_TONE + relation.name(), tone.name()).apply();
        }
    }

    /** Words that count as naming the owner in a group. Supports comma-separated or newline-separated entries. */
    public List<String> mentionKeywords() {
        String raw = prefs.getString(K_MENTION_WORDS, "suvra\nstg");
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("[\\n,]+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public void setMentionKeywords(List<String> words) {
        prefs.edit().putString(K_MENTION_WORDS, join(words)).apply();
    }

    /**
     * WhatsApp @-handles that count as naming the owner in a group (matched whole, underscores and
     * all, on the raw message). Separate from name keywords because these must NOT be normalised.
     */
    public List<String> mentionHandles() {
        return lines(prefs.getString(K_MENTION_HANDLES, "you_know__who__i__am"));
    }
    public void setMentionHandles(List<String> v) {
        prefs.edit().putString(K_MENTION_HANDLES, join(v)).apply();
    }

    /** Owner phone numbers (any format) that count as naming the owner; matched on last 10 digits. */
    public List<String> mentionNumbers() {
        return lines(prefs.getString(K_MENTION_NUMBERS, "7789893369"));
    }
    public void setMentionNumbers(List<String> v) {
        prefs.edit().putString(K_MENTION_NUMBERS, join(v)).apply();
    }

    private List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static final String K_MENTION_HANDLES = "mention_handles";
    private static final String K_MENTION_NUMBERS = "mention_numbers";

    /**
     * One name-pattern rule: a substring to look for, and what to do about it.
     *
     * A rule either sets a tone or refuses the contact outright. The owner asked for both from one
     * place -- "if the name has eric, be professional, or don't reply at all" -- and splitting them
     * across two screens would mean matching the same name twice by two different mechanisms.
     */
    public static final class NameRule {
        public final String pattern;
        /** Tone to apply. Ignored when {@link #never} is true or {@link #reply} is set. */
        public final ReplyTone.Tone tone;
        /** Refuse anyone matching this pattern, whatever else is true. */
        public final boolean never;
        /**
         * Fixed reply text for anyone matching this pattern, or empty.
         *
         * When set, it is the whole reply: category classification and tone are bypassed, because
         * the owner has said exactly what to send. Transparency is still the owner's to waive --
         * the editor warns if the text names neither IRIS nor Suvra, but does not refuse it.
         */
        public final String reply;

        public NameRule(String pattern, ReplyTone.Tone tone) {
            this(pattern, tone, false, "");
        }

        public NameRule(String pattern, ReplyTone.Tone tone, boolean never) {
            this(pattern, tone, never, "");
        }

        public NameRule(String pattern, ReplyTone.Tone tone, boolean never, String reply) {
            this.pattern = pattern == null ? "" : pattern;
            this.tone = tone == null ? ReplyTone.Tone.PROFESSIONAL : tone;
            this.never = never;
            // Newlines are collapsed because rules are stored one per line; a stray newline in a
            // reply would otherwise split one rule into two malformed ones on the next read.
            this.reply = reply == null ? "" : reply.replace('\n', ' ').replace('\r', ' ').trim();
        }

        public boolean hasReply() { return !reply.isEmpty(); }

        /** How this rule's ACTION is stored and shown. */
        public String actionName() {
            if (never) return "NEVER";
            if (hasReply()) return "SAY";
            return tone.name();
        }
    }

    // Separates a rule's pattern, action and optional reply payload in storage. A vertical bar
    // cannot appear in a tone name or in "NEVER"/"SAY", and the reply payload is the last field so
    // any bars the owner types in their reply text are preserved intact.
    private static final String RULE_SAY_PREFIX = "SAY|";

    /**
     * Name-pattern rules, in priority order. First match wins.
     *
     * Ships with the owner's rule: a contact whose name contains "eric" as a whole word is an
     * Ericsson colleague and gets the professional tone.
     *
     * Line format is {@code pattern|ACTION} where ACTION is a tone name, {@code NEVER}, or
     * {@code SAY|the reply text}. SAY keeps the reply as the final field so the owner's own text
     * may contain bars without being mangled.
     */
    public List<NameRule> nameRules() {
        String raw = prefs.getString(K_NAME_PATTERNS, "eric|PROFESSIONAL");
        List<NameRule> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int bar = trimmed.indexOf('|');
            if (bar <= 0) continue;
            String pattern = trimmed.substring(0, bar);
            String action = trimmed.substring(bar + 1).trim();
            if ("NEVER".equalsIgnoreCase(action)) {
                out.add(new NameRule(pattern, null, true));
            } else if (action.length() >= RULE_SAY_PREFIX.length()
                    && action.substring(0, RULE_SAY_PREFIX.length())
                            .equalsIgnoreCase(RULE_SAY_PREFIX)) {
                out.add(new NameRule(pattern, null, false,
                        action.substring(RULE_SAY_PREFIX.length())));
            } else {
                // Checked after NEVER and SAY, which would otherwise be read as unknown tones and
                // silently return PROFESSIONAL -- turning a refusal or a fixed reply into a plain
                // professional one.
                out.add(new NameRule(pattern, ReplyTone.parseTone(action), false));
            }
        }
        return out;
    }

    public void setNameRules(List<NameRule> rules) {
        StringBuilder joined = new StringBuilder();
        if (rules != null) {
            for (NameRule rule : rules) {
                if (rule == null || rule.pattern.trim().isEmpty()) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(rule.pattern.trim()).append('|');
                if (rule.hasReply()) {
                    joined.append(RULE_SAY_PREFIX).append(rule.reply);
                } else {
                    joined.append(rule.actionName());
                }
            }
        }
        prefs.edit().putString(K_NAME_PATTERNS, joined.toString()).apply();
    }

    /**
     * Tone implied by the first matching name rule, or null.
     *
     * A refusal or a fixed-reply rule carries no tone, so neither must be read as one. Returning
     * PROFESSIONAL here would mean a "never reply" rule quietly became "reply professionally".
     */
    public ReplyTone.Tone toneForName(String contactName) {
        for (NameRule rule : nameRules()) {
            if (!ReplyTone.nameMatches(contactName, rule.pattern)) continue;
            return (rule.never || rule.hasReply()) ? null : rule.tone;
        }
        return null;
    }

    /** Whether a name rule refuses this contact outright. First match wins, as for tone. */
    public boolean nameRuleBlocks(String contactName) {
        for (NameRule rule : nameRules()) {
            if (ReplyTone.nameMatches(contactName, rule.pattern)) return rule.never;
        }
        return false;
    }

    /**
     * The fixed reply a name rule dictates for this contact, or empty.
     *
     * First match wins, as for tone and refusal. A refusal rule earlier in the list is honoured by
     * the guards before this is consulted, so a NEVER rule cannot be undone by a later SAY rule.
     */
    public String nameRuleReply(String contactName) {
        for (NameRule rule : nameRules()) {
            if (ReplyTone.nameMatches(contactName, rule.pattern)) {
                return rule.never ? "" : rule.reply;
            }
        }
        return "";
    }

    /** The pattern that matched, for the decision log. Empty when none did. */
    public String matchingNamePattern(String contactName) {
        for (NameRule rule : nameRules()) {
            if (ReplyTone.nameMatches(contactName, rule.pattern)) return rule.pattern;
        }
        return "";
    }

    // ─── Categories ───

    /** Whether a category is answered at all, defaulting to the shipped policy. */
    public boolean categoryEnabled(MessageCategory.Kind kind, ReplyTone.Tone tone) {
        if (kind == null) return false;
        // The shipped default comes from ReplyTone, the single source of truth, so the two cannot
        // drift apart. Storage only holds a deliberate override.
        boolean shipped = ReplyTone.answers(kind, tone);
        return prefs.getBoolean(P_CATEGORY + kind.name(), shipped);
    }

    public void setCategoryEnabled(MessageCategory.Kind kind, boolean enabled) {
        if (kind == null) return;
        prefs.edit().putBoolean(P_CATEGORY + kind.name(), enabled).apply();
    }

    // ─── Editable reply text ───

    private static String templateKey(MessageCategory.Kind kind, ReplyTone.Tone tone,
                                      boolean quiet) {
        return P_TEMPLATE + (kind == null ? "NONE" : kind.name()) + "_"
                + (tone == null ? "PROFESSIONAL" : tone.name()) + "_" + (quiet ? "q" : "n");
    }

    /** The owner's edited text for a situation, or empty when the shipped default applies. */
    public String customTemplate(MessageCategory.Kind kind, ReplyTone.Tone tone, boolean quiet) {
        return prefs.getString(templateKey(kind, tone, quiet), "");
    }

    public void setCustomTemplate(MessageCategory.Kind kind, ReplyTone.Tone tone, boolean quiet,
                                  String text) {
        String key = templateKey(kind, tone, quiet);
        if (text == null || text.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, text.trim()).apply();
        }
    }

    /**
     * Variants for a situation: the owner's edit if there is one, otherwise the shipped set.
     *
     * A custom text replaces the whole variant set rather than joining it, so the owner always
     * knows exactly what will be sent. Mixing their wording with IRIS's would make the output
     * unpredictable.
     */
    public List<String> variantsFor(MessageCategory.Kind kind, ReplyTone.Tone tone,
                                    boolean quiet) {
        String custom = customTemplate(kind, tone, quiet);
        if (!custom.isEmpty()) {
            List<String> single = new ArrayList<>();
            single.add(custom);
            return single;
        }
        return ReplyTemplates.variants(kind, tone, quiet);
    }

    /**
     * Variants for an active mode: the owner's mode edit if there is one, otherwise the shipped
     * mode set.
     *
     * Mode wording is stored under its own template key (quiet flag false, keyed by the mode name
     * standing in for the category) so an edit to the driving reply cannot collide with an edit to
     * any message category.
     */
    public List<String> modeVariantsFor(ResponderMode mode, MessageCategory.Kind kind,
                                        ReplyTone.Tone tone) {
        if (mode == null || !mode.isActive()) return new ArrayList<>();
        String custom = prefs.getString(modeTemplateKey(mode, tone), "");
        if (!custom.isEmpty()) {
            List<String> single = new ArrayList<>();
            single.add(custom);
            return single;
        }
        return ReplyTemplates.modeVariants(mode, kind, tone);
    }

    private static String modeTemplateKey(ResponderMode mode, ReplyTone.Tone tone) {
        return P_TEMPLATE + "MODE_" + (mode == null ? "NONE" : mode.name()) + "_"
                + (tone == null ? "PROFESSIONAL" : tone.name());
    }

    /** The owner's edited mode text, or empty. */
    public String customModeTemplate(ResponderMode mode, ReplyTone.Tone tone) {
        return prefs.getString(modeTemplateKey(mode, tone), "");
    }

    public void setCustomModeTemplate(ResponderMode mode, ReplyTone.Tone tone, String text) {
        String key = modeTemplateKey(mode, tone);
        if (text == null || text.trim().isEmpty()) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, text.trim()).apply();
    }

    /** Remove every edited text, restoring the shipped wording. */
    public int resetAllTemplates() {
        SharedPreferences.Editor editor = prefs.edit();
        int removed = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(P_TEMPLATE)) { editor.remove(key); removed++; }
        }
        editor.apply();
        return removed;
    }

    /** Restore every responder setting to its shipped default. */
    public void resetEverything() {
        prefs.edit().clear().apply();
    }

    // ─── Per-contact rules ───

    /**
     * The key identifying one conversation.
     *
     * Shared by the responder and the contacts screen deliberately. If the two computed keys
     * differently the owner would set a rule that silently never applied, and nothing would look
     * broken: the screen would show NEVER while the responder cheerfully answered. One
     * implementation removes the possibility.
     *
     * Built from the package and the RESOLVED sender name, so a contact answers to the same key
     * whether their notification arrived as a saved name or as a bare number.
     */
    public static String conversationKey(String pkg, String resolvedSender) {
        return (pkg == null ? "" : pkg) + "|" + (resolvedSender == null ? "" : resolvedSender);
    }

    /**
     * Readable label for a configured contact.
     *
     * Stored because the preference key is sanitised and irreversible. Without it, a contact set
     * to NEVER whose messages later stopped being archived would disappear from the contacts list
     * while the rule stayed in force -- visible nowhere, changeable nowhere.
     */
    public String contactLabel(String contactKey) {
        return prefs.getString(P_CONTACT_LABEL + safe(contactKey), "");
    }

    public void setContactLabel(String contactKey, String label) {
        if (label == null || label.trim().isEmpty()) return;
        prefs.edit().putString(P_CONTACT_LABEL + safe(contactKey), label.trim()).apply();
    }

    public ResponderGuards.ContactRule contactRule(String contactKey) {
        String raw = prefs.getString(P_CONTACT_RULE + safe(contactKey), null);
        if (raw == null) return ResponderGuards.ContactRule.INHERIT;
        try {
            return ResponderGuards.ContactRule.valueOf(raw);
        } catch (Exception ignored) {
            return ResponderGuards.ContactRule.INHERIT;
        }
    }

    public void setContactRule(String contactKey, ResponderGuards.ContactRule rule) {
        prefs.edit().putString(P_CONTACT_RULE + safe(contactKey),
                (rule == null ? ResponderGuards.ContactRule.INHERIT : rule).name()).apply();
    }

    public ReplyTone.Relation contactRelation(String contactKey) {
        return ReplyTone.parseRelation(
                prefs.getString(P_CONTACT_RELATION + safe(contactKey), null));
    }

    public void setContactRelation(String contactKey, ReplyTone.Relation relation) {
        prefs.edit().putString(P_CONTACT_RELATION + safe(contactKey),
                (relation == null ? ReplyTone.Relation.UNKNOWN : relation).name()).apply();
    }

    /** Explicit per-contact tone, or null when unset so the resolver can fall through. */
    public ReplyTone.Tone contactTone(String contactKey) {
        String raw = prefs.getString(P_CONTACT_TONE + safe(contactKey), null);
        return raw == null ? null : ReplyTone.parseTone(raw);
    }

    public void setContactTone(String contactKey, ReplyTone.Tone tone) {
        String key = P_CONTACT_TONE + safe(contactKey);
        if (tone == null) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, tone.name()).apply();
    }

    /** Every contact the owner has given an instruction about. */
    public List<String> knownContactKeys() {
        List<String> out = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(P_CONTACT_RULE)) {
                out.add(key.substring(P_CONTACT_RULE.length()));
            }
        }
        return out;
    }

    // ─── Name-level contact rules (app-independent) ───
    //
    // Per-conversation rules above are keyed by app AND name, which is right for someone the
    // responder has actually seen message. The contact PICKER loads the phone book, where a
    // person has a name but no app yet, so those rules are keyed by name alone. A name-level rule
    // is the owner's statement about a PERSON ("never reply to my landlord"), independent of which
    // app they use; a per-conversation rule is about one thread and takes precedence when both
    // exist, because it is the more specific of the two.

    private String nameKey(String name) {
        return safe(name);
    }

    public ResponderGuards.ContactRule nameContactRule(String name) {
        String raw = prefs.getString(P_NAME_RULE + nameKey(name), null);
        if (raw == null) return ResponderGuards.ContactRule.INHERIT;
        try {
            return ResponderGuards.ContactRule.valueOf(raw);
        } catch (Exception ignored) {
            return ResponderGuards.ContactRule.INHERIT;
        }
    }

    public void setNameContactRule(String name, ResponderGuards.ContactRule rule) {
        prefs.edit().putString(P_NAME_RULE + nameKey(name),
                (rule == null ? ResponderGuards.ContactRule.INHERIT : rule).name()).apply();
        // Keep a readable label so the picker can show configured contacts that are no longer in
        // the current search results.
        prefs.edit().putString(P_NAME_LABEL + nameKey(name), name).apply();
    }

    public ReplyTone.Relation nameContactRelation(String name) {
        return ReplyTone.parseRelation(prefs.getString(P_NAME_RELATION + nameKey(name), null));
    }

    public void setNameContactRelation(String name, ReplyTone.Relation relation) {
        prefs.edit().putString(P_NAME_RELATION + nameKey(name),
                (relation == null ? ReplyTone.Relation.UNKNOWN : relation).name()).apply();
        prefs.edit().putString(P_NAME_LABEL + nameKey(name), name).apply();
    }

    public String nameContactLabel(String name) {
        return prefs.getString(P_NAME_LABEL + nameKey(name), "");
    }

    /** Names the owner has set a rule or relationship about from the picker. */
    public List<String> knownNameKeys() {
        List<String> out = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(P_NAME_LABEL)) out.add(key.substring(P_NAME_LABEL.length()));
        }
        return out;
    }

    /**
     * The rule that actually applies to a message: the per-conversation rule if the owner set
     * one, otherwise the name-level rule, otherwise INHERIT. The specific beats the general.
     */
    public ResponderGuards.ContactRule effectiveContactRule(String conversationKey,
                                                            String resolvedSender) {
        ResponderGuards.ContactRule specific = contactRule(conversationKey);
        if (specific != ResponderGuards.ContactRule.INHERIT) return specific;
        return nameContactRule(resolvedSender);
    }

    /** As effectiveContactRule, for the relationship that drives tone. */
    public ReplyTone.Relation effectiveContactRelation(String conversationKey,
                                                       String resolvedSender) {
        ReplyTone.Relation specific = contactRelation(conversationKey);
        if (specific != ReplyTone.Relation.UNKNOWN) return specific;
        return nameContactRelation(resolvedSender);
    }

    // ─── Counters, reset each day ───

    /**
     * Roll the daily counters when the date changes.
     *
     * Per-conversation counters are cleared too, which also bounds how many keys this file can
     * accumulate: at most one day's worth of conversations rather than a lifetime's.
     */
    private void rollIfNewDay(long nowMs) {
        int today = dayStamp(nowMs);
        if (prefs.getInt(K_COUNTER_DAY, 0) == today) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(K_COUNTER_DAY, today)
                .putInt(K_REPLIES_TODAY, 0);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(P_CONV_COUNT)) editor.remove(key);
            if (key.startsWith(P_CONV_IRIS)) editor.remove(key);
        }
        editor.apply();
    }

    /** Local calendar date as yyyyMMdd. */
    public static int dayStamp(long millis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.YEAR) * 10000
                + (calendar.get(Calendar.MONTH) + 1) * 100
                + calendar.get(Calendar.DAY_OF_MONTH);
    }

    public int repliesToday(long nowMs) {
        rollIfNewDay(nowMs);
        return prefs.getInt(K_REPLIES_TODAY, 0);
    }

    public int repliesToContactToday(String conversationKey, long nowMs) {
        rollIfNewDay(nowMs);
        return prefs.getInt(P_CONV_COUNT + safe(conversationKey), 0);
    }

    public long lastReplyTo(String conversationKey) {
        return prefs.getLong(P_CONV_LAST + safe(conversationKey), 0);
    }

    /**
     * Whether IRIS was the last to speak in this conversation, within living memory.
     *
     * Used to spot a reply aimed at IRIS rather than at Suvra. Thirty minutes, because the signal
     * is "they are responding to what IRIS just said" -- a message the next morning is a fresh
     * message, not a continuation.
     */
    public boolean irisSpokeLast(String conversationKey, long nowMs) {
        long last = lastReplyTo(conversationKey);
        if (last <= 0) return false;
        long elapsed = nowMs - last;
        return elapsed >= 0 && elapsed <= 30L * 60 * 1000;
    }

    public int toIrisRepliesToday(String conversationKey, long nowMs) {
        rollIfNewDay(nowMs);
        return prefs.getInt(P_CONV_IRIS + safe(conversationKey), 0);
    }

    public void recordIrisIntroduction(String conversationKey, long nowMs) {
        rollIfNewDay(nowMs);
        String key = P_CONV_IRIS + safe(conversationKey);
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    /** Record that a reply was sent, for the cooldown and both caps. */
    public void recordReply(String conversationKey, long nowMs) {
        rollIfNewDay(nowMs);
        String convKey = safe(conversationKey);
        prefs.edit()
                .putLong(P_CONV_LAST + convKey, nowMs)
                .putInt(P_CONV_COUNT + convKey,
                        prefs.getInt(P_CONV_COUNT + convKey, 0) + 1)
                .putInt(K_REPLIES_TODAY, prefs.getInt(K_REPLIES_TODAY, 0) + 1)
                .apply();
    }

    /**
     * Record an INCOMING message and return how many have arrived from this conversation within
     * the given window (including this one). Feeds the implicit-urgency burst signal.
     */
    public int recordIncomingAndCountBurst(String conversationKey, long nowMs, long windowMs) {
        String key = P_CONV_INWINDOW + safe(conversationKey);
        String raw = prefs.getString(key, "");
        int count = 0;
        long start = nowMs;
        int sep = raw.indexOf(':');
        if (sep > 0) {
            try {
                int prevCount = Integer.parseInt(raw.substring(0, sep));
                long prevStart = Long.parseLong(raw.substring(sep + 1));
                if (nowMs - prevStart <= windowMs && nowMs >= prevStart) {
                    count = prevCount;
                    start = prevStart;
                }
            } catch (NumberFormatException ignore) { /* reset */ }
        }
        count += 1;
        prefs.edit().putString(key, count + ":" + start).apply();
        return count;
    }

    /** Consecutive answered banter messages in this conversation (for the auto-snooze). */
    public int banterCount(String conversationKey) {
        return prefs.getInt(P_CONV_BANTER + safe(conversationKey), 0);
    }

    public void bumpBanterCount(String conversationKey) {
        String key = P_CONV_BANTER + safe(conversationKey);
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    public void resetBanterCount(String conversationKey) {
        prefs.edit().remove(P_CONV_BANTER + safe(conversationKey)).apply();
    }

    /** A pending IRIS offer (e.g. OFFERED_ESCALATION) awaiting the sender's yes/no, with a window. */
    public ConversationContext.Offer pendingOffer(String conversationKey, long nowMs) {
        String raw = prefs.getString(P_CONV_OFFER + safe(conversationKey), "");
        int sep = raw.indexOf(':');
        if (sep <= 0) return ConversationContext.Offer.NONE;
        try {
            long when = Long.parseLong(raw.substring(sep + 1));
            if (nowMs - when > OFFER_WINDOW_MS || nowMs < when) return ConversationContext.Offer.NONE;
            return ConversationContext.Offer.valueOf(raw.substring(0, sep));
        } catch (RuntimeException e) {
            return ConversationContext.Offer.NONE;
        }
    }

    public void setPendingOffer(String conversationKey, ConversationContext.Offer offer, long nowMs) {
        String key = P_CONV_OFFER + safe(conversationKey);
        if (offer == null || offer == ConversationContext.Offer.NONE) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, offer.name() + ":" + nowMs).apply();
        }
    }

    public void clearPendingOffer(String conversationKey) {
        prefs.edit().remove(P_CONV_OFFER + safe(conversationKey)).apply();
    }

    private static final String P_CONV_OFFER = "coffer_";
    /** How long a pending offer stays answerable. */
    private static final long OFFER_WINDOW_MS = 20L * 60 * 1000;

    /**
     * How many recent messages of a chat IRIS keeps as context (the "context depth"). Default 3,
     * clamped 0..MAX_DEPTH. 0 means "just this message". Persisted.
     */
    public int contextDepth() {
        return ChatHistory.clampDepth(prefs.getInt(K_CONTEXT_DEPTH, 3));
    }
    public void setContextDepth(int depth) {
        prefs.edit().putInt(K_CONTEXT_DEPTH, ChatHistory.clampDepth(depth)).apply();
    }

    /** The stored rolling history for a conversation (oldest first), honouring the depth. */
    public List<String> chatHistory(String conversationKey) {
        return chatHistory(conversationKey, System.currentTimeMillis());
    }

    /** The stored rolling history for a conversation, expiring if stale (older than 3 minutes). */
    public List<String> chatHistory(String conversationKey, long nowMs) {
        long lastTime = prefs.getLong(P_CONV_HIST_TIME + safe(conversationKey), 0L);
        if (ChatHistory.isStale(lastTime, nowMs)) {
            clearChatHistory(conversationKey);
            return new ArrayList<>();
        }
        String raw = prefs.getString(P_CONV_HIST + safe(conversationKey), "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    /** Clear rolling history for this conversation. */
    public void clearChatHistory(String conversationKey) {
        prefs.edit()
                .remove(P_CONV_HIST + safe(conversationKey))
                .remove(P_CONV_HIST_TIME + safe(conversationKey))
                .apply();
    }

    /** Append an incoming message to the conversation's rolling history (trimmed to the depth). */
    public void recordIncomingHistory(String conversationKey, String message) {
        recordIncomingHistory(conversationKey, message, System.currentTimeMillis());
    }

    /** Append an incoming message to the conversation's rolling history with timestamp. */
    public void recordIncomingHistory(String conversationKey, String message, long nowMs) {
        int depth = contextDepth();
        if (depth <= 0) {
            clearChatHistory(conversationKey);
            return;
        }
        long lastTime = prefs.getLong(P_CONV_HIST_TIME + safe(conversationKey), 0L);
        List<String> prev = ChatHistory.isStale(lastTime, nowMs)
                ? new ArrayList<>() : chatHistory(conversationKey, nowMs);
        String clean = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').trim();
        List<String> next = ChatHistory.append(prev, clean, depth);
        StringBuilder sb = new StringBuilder();
        for (String m : next) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m);
        }
        prefs.edit()
                .putString(P_CONV_HIST + safe(conversationKey), sb.toString())
                .putLong(P_CONV_HIST_TIME + safe(conversationKey), nowMs)
                .apply();
    }

    private static final String K_CONTEXT_DEPTH = "context_depth";
    private static final String P_CONV_HIST = "chist_";
    private static final String P_CONV_HIST_TIME = "chist_time_";

    /**
     * Prepend a short time/day-aware line ("It's Friday evening\u2026") to generic replies. Off by
     * default so nothing changes unless the owner opts in.
     */
    public boolean timeFlavorEnabled() { return prefs.getBoolean(K_TIME_FLAVOR, false); }
    public void setTimeFlavorEnabled(boolean v) {
        prefs.edit().putBoolean(K_TIME_FLAVOR, v).apply();
    }
    private static final String K_TIME_FLAVOR = "time_flavor";

    /**
     * The exact text IRIS last answered in this conversation, normalised, or empty.
     *
     * Used to refuse answering the identical message twice. Stored normalised so the comparison in
     * the guard is a plain equality.
     */
    public String lastAnsweredText(String conversationKey) {
        return prefs.getString(P_CONV_TEXT + safe(conversationKey), "");
    }

    public void recordAnsweredText(String conversationKey, String incomingText) {
        String norm = MessageCategory.normalise(incomingText == null ? "" : incomingText);
        prefs.edit().putString(P_CONV_TEXT + safe(conversationKey), norm).apply();
    }

    /**
     * Whether IRIS has already introduced itself in full to this conversation.
     *
     * The first reply names IRIS and the owner in full, for transparency; later replies to the
     * same thread can be short and say just "IRIS", because the recipient already knows. This is
     * what the owner asked for -- stop repeating "personal intelligence" every time -- without
     * ever hiding that it is not the owner replying.
     */
    public boolean hasIntroduced(String conversationKey) {
        return prefs.getBoolean(P_CONV_INTRO + safe(conversationKey), false);
    }

    public void markIntroduced(String conversationKey) {
        prefs.edit().putBoolean(P_CONV_INTRO + safe(conversationKey), true).apply();
    }

    /** Rotation counter, so repeat senders do not receive identical wording. */
    public int nextRotation() {
        int current = prefs.getInt(K_ROTATION, 0);
        prefs.edit().putInt(K_ROTATION, current + 1).apply();
        return current;
    }

    public int rotation() { return prefs.getInt(K_ROTATION, 0); }

    // ─── Persisted answered-notification fingerprints ───
    //
    // WhatsApp re-posts the same notification while a message stays unread, often cancelling and
    // re-adding it, which cleared the in-process "already answered" set and let IRIS reply again.
    // This store survives both the removal and a process restart: a notification key plus the
    // normalised body it was answered for, with a timestamp, bounded. If the same key+body is seen
    // again within the window, it is not answered a second time.

    private static final String K_ANSWERED_FP = "answered_fp";
    private static final int ANSWERED_FP_MAX = 80;
    /** How long a re-posted identical notification stays suppressed. */
    public static final long ANSWERED_FP_WINDOW_MS = 6L * 60 * 60 * 1000;

    private static String fingerprint(String notifKey, String body) {
        return (notifKey == null ? "" : notifKey) + "\u0001"
                + MessageCategory.normalise(body == null ? "" : body);
    }

    /** True if this exact notification+body was already answered within the window. */
    public boolean answeredRecently(String notifKey, String body, long nowMs) {
        String fp = fingerprint(notifKey, body);
        String raw = prefs.getString(K_ANSWERED_FP, "");
        if (raw.isEmpty()) return false;
        for (String rec : raw.split("\n")) {
            int tab = rec.lastIndexOf('\u0002');
            if (tab <= 0) continue;
            String key = rec.substring(0, tab);
            if (!key.equals(fp)) continue;
            try {
                long when = Long.parseLong(rec.substring(tab + 1));
                return nowMs - when >= 0 && nowMs - when < ANSWERED_FP_WINDOW_MS;
            } catch (Exception ignored) { return false; }
        }
        return false;
    }

    /** Record that this notification+body was answered, pruning old and surplus entries. */
    public void recordAnsweredFingerprint(String notifKey, String body, long nowMs) {
        String fp = fingerprint(notifKey, body);
        java.util.List<String> kept = new ArrayList<>();
        String raw = prefs.getString(K_ANSWERED_FP, "");
        if (!raw.isEmpty()) {
            for (String rec : raw.split("\n")) {
                int tab = rec.lastIndexOf('\u0002');
                if (tab <= 0) continue;
                String key = rec.substring(0, tab);
                if (key.equals(fp)) continue; // replace any prior entry for this fingerprint
                try {
                    long when = Long.parseLong(rec.substring(tab + 1));
                    if (nowMs - when < ANSWERED_FP_WINDOW_MS) kept.add(rec);
                } catch (Exception ignored) { }
            }
        }
        kept.add(fp + "\u0002" + nowMs);
        while (kept.size() > ANSWERED_FP_MAX) kept.remove(0);
        prefs.edit().putString(K_ANSWERED_FP, String.join("\n", kept)).apply();
    }

    // ─── Helpers ───

    /**
     * Make a contact or conversation identifier safe to use as a preference key.
     *
     * Contact names and package strings arrive from notifications and can contain anything, so
     * they are reduced to a bounded alphanumeric form. Length is capped because a pathological
     * group name would otherwise become a pathological key.
     */
    private static String safe(String raw) {
        if (raw == null) return "unknown";
        String cleaned = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "_");
        if (cleaned.isEmpty()) return "unknown";
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }

    private static String join(List<String> items) {
        StringBuilder joined = new StringBuilder();
        if (items == null) return "";
        for (String item : items) {
            if (item == null || item.trim().isEmpty()) continue;
            if (joined.length() > 0) joined.append('\n');
            joined.append(item.trim());
        }
        return joined.toString();
    }
}
