package com.iris.assistant;

import java.util.Collection;
import java.util.Locale;

/**
 * Chooses how IRIS speaks, and whether it speaks at all.
 *
 * Pure, with no Android imports. Every customisable value arrives as a parameter rather than being
 * read from settings here, so the resolution order is checkable offline.
 *
 * Tone resolves BEFORE the decision to stay silent, which is the opposite of the original design.
 * The owner's rule forced it: "ok" deserves no reply when IRIS is being professional, but deserves
 * a remark when it is being sarcastic. Whether a category earns an answer therefore depends on the
 * tone, so the tone has to be known first.
 */
public final class ReplyTone {

    /** How a reply is phrased. PROFESSIONAL is the shipped default. */
    public enum Tone {
        /** Measured, complete sentences, no jokes. Safe for colleagues. */
        PROFESSIONAL,
        /** Relaxed and contracted, still clear. */
        CASUAL,
        /** Dry. Reachable only by naming a contact explicitly; never by default. */
        SARCASTIC,
        /** One short line, for people who dislike bots. */
        BRIEF
    }

    /**
     * How a contact relates to Suvra.
     *
     * Exists because the same message deserves a different answer from a brother than from a
     * client. Carries a default tone, and reply templates may offer relationship-specific
     * variants.
     */
    public enum Relation {
        FAMILY,
        WORK,
        FRIEND,
        /** Not labelled. Falls through to the next step in the resolution order. */
        UNKNOWN
    }

    private ReplyTone() { }

    /**
     * The tone a relationship implies, or null when it implies nothing.
     *
     * FAMILY and FRIEND default to casual because formality to a sibling reads as coldness. WORK
     * defaults to professional. UNKNOWN deliberately returns null rather than guessing, so an
     * unlabelled contact falls through to the name patterns and then the global default.
     */
    public static Tone toneFor(Relation relation) {
        if (relation == null) return null;
        switch (relation) {
            case FAMILY: return Tone.CASUAL;
            case FRIEND: return Tone.CASUAL;
            case WORK: return Tone.PROFESSIONAL;
            default: return null;
        }
    }

    /**
     * Resolve the tone for one conversation.
     *
     * Order, most specific first:
     *   1. a tone set explicitly on this contact
     *   2. the contact's relationship
     *   3. a name-pattern rule, such as "eric" meaning a colleague
     *   4. an active time window, such as work hours
     *   5. the global default
     *
     * A relationship beats a name pattern on purpose. A pattern is a guess about who someone is;
     * a relationship is the owner stating it. The concrete case: "eric" is meant to catch Ericsson
     * colleagues, but a sister named Erica would match the same rule. Labelling her FAMILY has to
     * win, and it does.
     *
     * @param explicitTone  tone set on the contact, or null
     * @param relation      the contact's relationship, or null / UNKNOWN
     * @param patternTone   tone from a matched name pattern, or null
     * @param windowTone    tone from an active time window, or null
     * @param globalDefault the owner's default, or null to fall back to PROFESSIONAL
     */
    public static Tone resolve(Tone explicitTone, Relation relation, Tone patternTone,
                               Tone windowTone, Tone globalDefault) {
        if (explicitTone != null) return explicitTone;

        Tone fromRelation = toneFor(relation);
        if (fromRelation != null) return fromRelation;

        if (patternTone != null) return patternTone;
        if (windowTone != null) return windowTone;
        if (globalDefault != null) return globalDefault;

        // Last resort. Professional is the only tone that cannot embarrass anyone, so an
        // unconfigured responder is formal rather than familiar.
        return Tone.PROFESSIONAL;
    }

    /**
     * Whether a name pattern matches a contact.
     *
     * Whole-word, not substring. The owner's intended rule is "eric" for Ericsson colleagues, who
     * appear in contacts as "Rajesh Bhai Eric Kolkata" and "Debraj Naskar Eric Kol" -- Eric as its
     * own word. A substring match would also claim "Erica" and "Frederica", which is exactly the
     * kind of quiet mistake that sends a family member a formal reply.
     *
     * To match a longer form such as "Ericsson", add it as its own pattern.
     */
    public static boolean nameMatches(String contactName, String pattern) {
        if (contactName == null || pattern == null) return false;
        String name = MessageCategory.normalise(contactName);
        String needle = MessageCategory.normalise(pattern);
        if (name.isEmpty() || needle.isEmpty()) return false;
        return (" " + name + " ").contains(" " + needle + " ");
    }

    /**
     * The first pattern in the list that matches, or -1.
     *
     * Order is meaningful and owner-editable: earlier rules win, so a specific pattern can be
     * placed above a broad one.
     */
    public static int firstMatchingPattern(String contactName, Collection<String> patterns) {
        if (patterns == null) return -1;
        int index = 0;
        for (String pattern : patterns) {
            if (nameMatches(contactName, pattern)) return index;
            index++;
        }
        return -1;
    }

    /**
     * Whether this category earns a reply in this tone, as shipped.
     *
     * The owner may override any of these per category; this is only the default. Two categories
     * are special:
     *
     * ACKNOWLEDGEMENT -- "ok", "hmm", "theek", "noted" -- is answered ONLY in sarcastic tone. A
     * professional or casual reply to "ok" is noise, and sending a colleague "Suvra is asleep"
     * because they acknowledged something would be faintly absurd. In sarcastic tone it is the
     * whole point, so it speaks.
     *
     * MEDIA_ONLY is off by default. Photos arrive in bulk and auto-replying to each one is the
     * fastest way to look broken.
     */
    public static boolean answers(MessageCategory.Kind category, Tone tone) {
        if (category == null) return false;
        switch (category) {
            case NONE:
                // Nothing was recognised, and IRIS cannot compose an answer to an arbitrary
                // message. Silence is the only honest option.
                return false;
            case ACKNOWLEDGEMENT:
                return tone == Tone.SARCASTIC;
            case MEDIA_ONLY:
                return false;
            default:
                return true;
        }
    }

    /** Parse a stored tone name, falling back to professional rather than throwing. */
    public static Tone parseTone(String value) {
        if (value == null) return Tone.PROFESSIONAL;
        try {
            return Tone.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Tone.PROFESSIONAL;
        }
    }

    /** Parse a stored relationship, falling back to unknown rather than throwing. */
    public static Relation parseRelation(String value) {
        if (value == null) return Relation.UNKNOWN;
        try {
            return Relation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Relation.UNKNOWN;
        }
    }
}
