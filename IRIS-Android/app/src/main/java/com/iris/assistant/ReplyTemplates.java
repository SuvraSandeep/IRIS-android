package com.iris.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The shipped reply texts, and the placeholder substitution that fills them in.
 *
 * Pure, with no Android imports, so the whole template set is checkable offline.
 *
 * These are DEFAULTS. The owner can edit every one of them, which is why they live here as
 * read-only constants: an edit is stored separately, and reset restores from this file. Without a
 * frozen copy there would be nothing to reset to.
 *
 * Every shipped variant names both IRIS and Suvra, and a test asserts it. That is decision 3 made
 * enforceable: the recipient must finish the message knowing it was not Suvra who wrote it. The
 * identification is built into the sentence rather than appended as a marker, because a trailing
 * "- sent by IRIS" is invisible on a watch face or a lock-screen preview.
 *
 * Transparency cannot be enforced on text the owner writes himself without refusing to save his
 * edit, which would be a worse product. The editor warns instead. So the guarantee is precise:
 * shipped defaults always identify IRIS, and the owner may knowingly opt out of that.
 */
public final class ReplyTemplates {

    /** Owner's name as used in replies. First name only; the UI may use STG. */
    public static final String OWNER = "Suvra";

    private ReplyTemplates() { }

    /**
     * A set of interchangeable texts for one situation.
     *
     * Several variants rather than one, so a contact who writes every morning does not receive a
     * verbatim duplicate each time and conclude they are talking to a machine on a loop.
     */
    public static List<String> variants(MessageCategory.Kind category, ReplyTone.Tone tone,
                                        boolean quietHours) {
        ReplyTone.Tone safeTone = tone == null ? ReplyTone.Tone.PROFESSIONAL : tone;

        // Quiet hours override the category for content, with one exception handled inside:
        // an urgent message gets a variant that redirects to a phone call.
        if (quietHours) {
            boolean urgent = category == MessageCategory.Kind.URGENT;
            return urgent ? quietUrgent(safeTone) : quiet(safeTone);
        }

        if (category == null) return new ArrayList<>();
        switch (category) {
            case GREETING:
            case SHORT:
                return greeting(safeTone);
            case TIME_GREETING:
                return timeGreeting(safeTone);
            case FESTIVAL:
                return festival(safeTone);
            case BIRTHDAY:
                return birthday(safeTone);
            case WEEKEND:
                return weekend(safeTone);
            case THANKS:
                return thanks(safeTone);
            case CHECK_IN:
                return checkIn(safeTone);
            case QUESTION:
                return question(safeTone);
            case URGENT:
                return urgent(safeTone);
            case MENTION:
                return mention(safeTone);
            case ACKNOWLEDGEMENT:
                // Only ever reached in sarcastic tone; ReplyTone.answers refuses the rest.
                return acknowledgement(safeTone);
            default:
                return new ArrayList<>();
        }
    }

    // ─── Quiet hours ───

    private static List<String> quiet(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        OWNER + "'s asleep. I'm IRIS, his personal intelligence \u2014 I mind the "
                                + "messages while he's out. He'll see this in the morning.",
                        "It's {time} and " + OWNER + "'s asleep. I'm IRIS, the intelligence he "
                                + "keeps for exactly this. I've got your message safe for him.");
            case SARCASTIC:
                return Arrays.asList(
                        OWNER + " is unconscious, so you've got IRIS instead \u2014 his personal "
                                + "intelligence, and the one of us that doesn't need sleep. He'll "
                                + "read it when he resurfaces.",
                        "It is {time}. " + OWNER + " is asleep like a sensible person. I'm IRIS, "
                                + "his personal intelligence, and I never get that luxury. "
                                + "Message logged.");
            case BRIEF:
                return Arrays.asList(
                        OWNER + " is asleep. IRIS, his personal intelligence \u2014 message logged.",
                        "Asleep. IRIS here, " + OWNER + "'s personal intelligence. Noted for him.");
            default:
                return Arrays.asList(
                        OWNER + " is asleep. I'm IRIS, the personal intelligence he keeps for "
                                + "exactly this \u2014 your message is recorded and he'll see it in "
                                + "the morning.",
                        "It's {time} and " + OWNER + " is resting. This is IRIS, his personal "
                                + "intelligence. I've logged your message; he'll respond in about "
                                + "{hours_until_morning} hours.");
        }
    }

    /**
     * Urgent, during quiet hours.
     *
     * Decision 13: urgency does not pierce quiet hours, because "urgent" is a word spammers use
     * freely and a 3am message must not be able to talk IRIS into different behaviour. Instead the
     * sender is redirected to the one channel that actually reaches a sleeping person.
     *
     * This is strictly better than piercing. A spammer is told to call and ignored; a real
     * emergency is told exactly how to get through. IRIS never has to judge which it is, which is
     * the part it would get wrong.
     */
    private static List<String> quietUrgent(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        OWNER + "'s asleep and a message won't wake him. I'm IRIS, his personal "
                                + "intelligence \u2014 if this really can't wait, call him. The "
                                + "phone rings. This doesn't.",
                        "Marked urgent, but " + OWNER + " is asleep. I'm IRIS, his personal "
                                + "intelligence. If it's genuinely now, ring him \u2014 that gets "
                                + "through and this won't.");
            case SARCASTIC:
                return Arrays.asList(
                        "Everyone's message is urgent at {time}. " + OWNER + " is asleep and I'm "
                                + "IRIS, his personal intelligence, not his alarm clock. If it's "
                                + "truly an emergency, call him \u2014 that actually wakes people.",
                        OWNER + " is asleep and I don't wake him for text. I'm IRIS, his personal "
                                + "intelligence. If this is as urgent as you say, ring the phone.");
            case BRIEF:
                return Arrays.asList(
                        "Urgent noted. " + OWNER + " is asleep \u2014 call, don't text. IRIS, his "
                                + "personal intelligence.",
                        OWNER + " is asleep. Call to wake him. IRIS, his personal intelligence.");
            default:
                return Arrays.asList(
                        OWNER + " is asleep and a message won't wake him. I'm IRIS, his personal "
                                + "intelligence \u2014 if this genuinely cannot wait until "
                                + "morning, call him. The phone will ring; this will not.",
                        "It's {time} and " + OWNER + " is asleep. I'm IRIS, his personal "
                                + "intelligence. I've marked this urgent, but if it's truly "
                                + "immediate, call \u2014 a call gets through and a message "
                                + "doesn't.");
        }
    }

    // ─── Ordinary hours ───

    private static List<String> greeting(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Hello. " + OWNER + " isn't free right now \u2014 I'm IRIS, his personal "
                                + "intelligence. I've passed it on and he'll get back to you.",
                        "Hi. You've got IRIS, " + OWNER + "'s personal intelligence. He's busy, "
                                + "but your message is with him.");
            case SARCASTIC:
                return Arrays.asList(
                        "Hello to you too. " + OWNER + " is unavailable, so you've reached IRIS, "
                                + "his personal intelligence \u2014 the part of him that answers "
                                + "on time.",
                        "Hi. " + OWNER + " isn't answering, which is where I come in. IRIS, his "
                                + "personal intelligence. Say more and he'll have something to "
                                + "read.");
            case BRIEF:
                return Arrays.asList(
                        "Hello. IRIS here, " + OWNER + "'s personal intelligence. He'll reply "
                                + "later.",
                        "Hi. " + OWNER + " is busy. IRIS, his personal intelligence \u2014 noted.");
            default:
                return Arrays.asList(
                        "Hello. " + OWNER + " isn't available at the moment. I'm IRIS, his "
                                + "personal intelligence; I've noted your message and he'll reply "
                                + "when he's free.",
                        "Good {part_of_day}. " + OWNER + " is away from his phone. This is IRIS, "
                                + "his personal intelligence \u2014 your message is with him.");
        }
    }

    private static List<String> timeGreeting(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Good {part_of_day} to you too. " + OWNER + "'s not at his phone \u2014 "
                                + "I'm IRIS, his personal intelligence, and I've passed it on.",
                        "And to you. " + OWNER + " is busy; I'm IRIS, his personal intelligence. "
                                + "He'll see it shortly.");
            case SARCASTIC:
                return Arrays.asList(
                        "Good {part_of_day}, and on " + OWNER + "'s behalf, since he hasn't got "
                                + "to it. IRIS, his personal intelligence.",
                        "Returned in kind. " + OWNER + " is elsewhere, so you have IRIS, his "
                                + "personal intelligence, being polite for him.");
            case BRIEF:
                return Arrays.asList(
                        "Good {part_of_day}. IRIS, " + OWNER + "'s personal intelligence \u2014 "
                                + "passed on.",
                        "And to you. IRIS here, for " + OWNER + ".");
            default:
                return Arrays.asList(
                        "Good {part_of_day} to you as well. " + OWNER + " is away from his phone "
                                + "\u2014 this is IRIS, his personal intelligence. Your message "
                                + "is with him.",
                        "Thank you, and the same to you. I'm IRIS, " + OWNER + "'s personal "
                                + "intelligence; he'll reply himself when he's free.");
        }
    }

    private static List<String> festival(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Thank you, and the same to you. " + OWNER + "'s not at his phone \u2014 "
                                + "I'm IRIS, his personal intelligence, and I've passed your "
                                + "wishes on.",
                        "Lovely, thank you. IRIS here, " + OWNER + "'s personal intelligence. "
                                + "He'll have seen it by evening.");
            case SARCASTIC:
                return Arrays.asList(
                        "Wishes received and logged. " + OWNER + " is celebrating somewhere "
                                + "without his phone, so IRIS, his personal intelligence, thanks "
                                + "you on his behalf.",
                        "The same to you. " + OWNER + " will reply eventually; I'm IRIS, his "
                                + "personal intelligence, and I am at least punctual.");
            case BRIEF:
                return Arrays.asList(
                        "Thank you, same to you. IRIS, " + OWNER + "'s personal intelligence.",
                        "Wishes passed to " + OWNER + ". IRIS, his personal intelligence.");
            default:
                return Arrays.asList(
                        "Thank you, and the same to you. " + OWNER + " isn't at his phone right "
                                + "now; I'm IRIS, his personal intelligence, and I've passed your "
                                + "wishes on to him.",
                        "That's very kind \u2014 thank you. This is IRIS, " + OWNER + "'s personal "
                                + "intelligence. He'll want to reply himself, and he will.");
        }
    }

    private static List<String> birthday(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Thank you. IRIS, " + OWNER + "'s personal intelligence \u2014 passed on.",
                    "Noted with thanks, for " + OWNER + ". IRIS, his personal intelligence.");
        }
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Thank you. " + OWNER + " is being celebrated elsewhere, so IRIS, his personal "
                            + "intelligence, is accepting wishes on his behalf.",
                    "Duly recorded. I'm IRIS, " + OWNER + "'s personal intelligence, and I will "
                            + "make sure he knows you remembered.");
        }
        return Arrays.asList(
                "Thank you very much. " + OWNER + " isn't at his phone \u2014 I'm IRIS, his "
                        + "personal intelligence, and I've made sure he has your message.",
                "That's kind of you, thank you. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence; he'll thank you himself shortly.");
    }

    private static List<String> weekend(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "And to you. " + OWNER + " is enjoying his weekend by not looking at this. "
                            + "IRIS, his personal intelligence, is looking at it for him.",
                    "Same to you. " + OWNER + " is off duty; I'm IRIS, his personal intelligence, "
                            + "and I am not.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "You too. IRIS, " + OWNER + "'s personal intelligence \u2014 passed on.",
                    "And to you. IRIS here, for " + OWNER + ".");
        }
        return Arrays.asList(
                "Thank you, and the same to you. " + OWNER + " is away from his phone \u2014 this "
                        + "is IRIS, his personal intelligence, and your message is with him.",
                "You too. I'm IRIS, " + OWNER + "'s personal intelligence; he'll pick this up "
                        + "when the weekend lets him.");
    }

    private static List<String> thanks(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "You're welcome, although " + OWNER + " did the work and I'm only IRIS, his "
                            + "personal intelligence, taking the credit.",
                    "Noted. I'll tell " + OWNER + " he was appreciated. IRIS, his personal "
                            + "intelligence.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Welcome. IRIS, " + OWNER + "'s personal intelligence.",
                    "Passed to " + OWNER + ". IRIS, his personal intelligence.");
        }
        return Arrays.asList(
                "You're very welcome. I'm IRIS, " + OWNER + "'s personal intelligence \u2014 I've "
                        + "passed your thanks on to him.",
                "Glad to help. This is IRIS, " + OWNER + "'s personal intelligence; he'll see your "
                        + "message shortly.");
    }

    private static List<String> checkIn(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "He is not. I am. IRIS, " + OWNER + "'s personal intelligence \u2014 the "
                            + "reliable half of this arrangement.",
                    "Not right now. You've got IRIS, " + OWNER + "'s personal intelligence, which "
                            + "is the next best thing and arguably better.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Not right now. IRIS, " + OWNER + "'s personal intelligence \u2014 noted.",
                    OWNER + " is away. IRIS here. Logged.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    "He's not around at the moment \u2014 I'm IRIS, his personal intelligence. "
                            + "I'll make sure " + OWNER + " sees you were looking for him.",
                    "Not just now. IRIS here, " + OWNER + "'s personal intelligence. He'll come "
                            + "back to you.");
        }
        return Arrays.asList(
                OWNER + " isn't available at the moment. I'm IRIS, his personal intelligence \u2014 "
                        + "I've noted that you were trying to reach him.",
                "He's away from his phone just now. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence; he'll get back to you.");
    }

    private static List<String> question(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "That one needs " + OWNER + " himself. I'm IRIS, his personal intelligence, "
                            + "and I'd only guess convincingly.",
                    "Above my authority. I'm IRIS, " + OWNER + "'s personal intelligence \u2014 "
                            + "I've written the question down for him.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "One for " + OWNER + ". IRIS, his personal intelligence \u2014 question logged.",
                    "He'll answer that. IRIS, " + OWNER + "'s personal intelligence.");
        }
        return Arrays.asList(
                "That's one for " + OWNER + " himself. I'm IRIS, his personal intelligence; I've "
                        + "recorded the question and he'll answer it properly when he's back.",
                "I'd rather he answered that than I did. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 your question is with him.");
    }

    private static List<String> urgent(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Flagged as urgent, as requested. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence. If it is genuinely urgent, call him rather than "
                            + "typing at me.",
                    "Understood. " + OWNER + " now has this above everything else, courtesy of "
                            + "IRIS, his personal intelligence. A call would be faster.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Urgent, flagged for " + OWNER + ". IRIS, his personal intelligence. Call if "
                            + "it cannot wait.",
                    "Marked urgent for " + OWNER + ". IRIS here. Call if immediate.");
        }
        return Arrays.asList(
                "Understood as urgent. I'm IRIS, " + OWNER + "'s personal intelligence \u2014 I've "
                        + "flagged this above everything else. If it cannot wait, call rather "
                        + "than message.",
                "Marked urgent and put in front of " + OWNER + ". This is IRIS, his personal "
                        + "intelligence; a call will reach him faster than this will.");
    }

    private static List<String> mention(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    OWNER + " has been summoned and is not here. I'm IRIS, his personal "
                            + "intelligence, standing in until he notices.",
                    "He's been named and he's absent. IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 I've logged the mention.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    OWNER + " is away. IRIS, his personal intelligence \u2014 mention logged.",
                    "Noted for " + OWNER + ". IRIS, his personal intelligence.");
        }
        return Arrays.asList(
                OWNER + " has been mentioned and isn't here to answer. This is IRIS, his personal "
                        + "intelligence; the mention is logged and he'll pick it up.",
                "He's been named but he's away from his phone. I'm IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 I've made sure he'll see it.");
    }

    /**
     * Acknowledgements, sarcastic only.
     *
     * ReplyTone.answers refuses this category in every other tone, so these are the only variants
     * that exist. Decision 16: "ok" deserves silence when IRIS is being polite and a remark when
     * it is not.
     */
    private static List<String> acknowledgement(ReplyTone.Tone tone) {
        return Arrays.asList(
                "A whole word. " + OWNER + " will treasure it. IRIS, his personal intelligence.",
                "Riveting. I've filed that under correspondence for " + OWNER + ". IRIS, his "
                        + "personal intelligence.",
                "Noted, and barely. I'm IRIS, " + OWNER + "'s personal intelligence, and even I "
                        + "expected more.");
    }

    /**
     * Fill the placeholders in a template.
     *
     * Unknown placeholders are left exactly as they are rather than blanked. An owner who mistypes
     * {naem} should see the mistake in the preview, not a sentence with a hole in it.
     *
     * @param template     the text, which may contain placeholders
     * @param senderName   resolved name of the sender, may be empty
     * @param clockText    current time as HH:MM
     * @param partOfDay    morning / afternoon / evening / night
     * @param hoursToWake  whole hours until quiet hours end, or -1 when not applicable
     * @param dayName      current day name, may be empty
     */
    public static String fill(String template, String senderName, String clockText,
                              String partOfDay, int hoursToWake, String dayName) {
        if (template == null) return "";
        String out = template;
        out = out.replace("{name}", senderName == null ? "" : senderName);
        out = out.replace("{owner}", OWNER);
        out = out.replace("{time}", clockText == null ? "" : clockText);
        out = out.replace("{part_of_day}", partOfDay == null || partOfDay.isEmpty()
                ? "day" : partOfDay);
        out = out.replace("{day}", dayName == null ? "" : dayName);
        out = out.replace("{hours_until_morning}",
                hoursToWake > 0 ? String.valueOf(hoursToWake) : "a few");
        // Tidy the double spaces an empty substitution can leave behind.
        return out.replaceAll("  +", " ").trim();
    }

    /**
     * Pick one variant, rotating deterministically.
     *
     * Index-based rather than random so the choice is reproducible in a dry run: the owner reading
     * the decision log must be able to tell why a particular wording appeared.
     */
    public static String choose(List<String> variants, int rotation) {
        if (variants == null || variants.isEmpty()) return "";
        int index = Math.abs(rotation % variants.size());
        return variants.get(index);
    }

    /** Whether a text identifies both IRIS and the owner, used by the editor's warning. */
    public static boolean identifiesIris(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("iris") && lower.contains(OWNER.toLowerCase(java.util.Locale.ROOT));
    }
}
