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
    private static final String K_ROTATION = "rotation";
    private static final String K_COUNTER_DAY = "counter_day";
    private static final String K_REPLIES_TODAY = "replies_today";

    private static final String P_CATEGORY = "cat_";
    private static final String P_TEMPLATE = "tpl_";
    private static final String P_CONTACT_RULE = "crule_";
    private static final String P_CONTACT_RELATION = "crel_";
    private static final String P_CONTACT_LABEL = "clabel_";
    private static final String P_CONTACT_TONE = "ctone_";
    private static final String P_CONV_LAST = "clast_";
    private static final String P_CONV_COUNT = "ccount_";

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

    /** Words that count as naming the owner in a group. */
    public List<String> mentionKeywords() {
        String raw = prefs.getString(K_MENTION_WORDS, "suvra\nstg");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public void setMentionKeywords(List<String> words) {
        prefs.edit().putString(K_MENTION_WORDS, join(words)).apply();
    }

    /** One name-pattern rule: a substring to look for, and the tone it implies. */
    public static final class NameRule {
        public final String pattern;
        public final ReplyTone.Tone tone;
        public NameRule(String pattern, ReplyTone.Tone tone) {
            this.pattern = pattern == null ? "" : pattern;
            this.tone = tone == null ? ReplyTone.Tone.PROFESSIONAL : tone;
        }
    }

    /**
     * Name-pattern rules, in priority order. First match wins.
     *
     * Ships with the owner's rule: a contact whose name contains "eric" as a whole word is an
     * Ericsson colleague and gets the professional tone.
     */
    public List<NameRule> nameRules() {
        String raw = prefs.getString(K_NAME_PATTERNS, "eric|PROFESSIONAL");
        List<NameRule> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int bar = trimmed.indexOf('|');
            if (bar <= 0) continue;
            out.add(new NameRule(trimmed.substring(0, bar),
                    ReplyTone.parseTone(trimmed.substring(bar + 1))));
        }
        return out;
    }

    public void setNameRules(List<NameRule> rules) {
        StringBuilder joined = new StringBuilder();
        if (rules != null) {
            for (NameRule rule : rules) {
                if (rule == null || rule.pattern.trim().isEmpty()) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(rule.pattern.trim()).append('|').append(rule.tone.name());
            }
        }
        prefs.edit().putString(K_NAME_PATTERNS, joined.toString()).apply();
    }

    /** Tone implied by the first matching name rule, or null. */
    public ReplyTone.Tone toneForName(String contactName) {
        for (NameRule rule : nameRules()) {
            if (ReplyTone.nameMatches(contactName, rule.pattern)) return rule.tone;
        }
        return null;
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

    /** Rotation counter, so repeat senders do not receive identical wording. */
    public int nextRotation() {
        int current = prefs.getInt(K_ROTATION, 0);
        prefs.edit().putInt(K_ROTATION, current + 1).apply();
        return current;
    }

    public int rotation() { return prefs.getInt(K_ROTATION, 0); }

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
