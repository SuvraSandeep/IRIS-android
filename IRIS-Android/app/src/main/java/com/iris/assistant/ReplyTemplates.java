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
     * Reply texts for an active situational mode.
     *
     * A mode overrides both the category and the time windows: the owner has said "I'm driving",
     * and that is the reply, whatever the message said and whatever hour it is. SLEEPING is the
     * exception that reuses the quiet-hours wording, because being asleep IS quiet hours by
     * another name -- and it is the only mode where an urgent message is redirected to a call,
     * since a call wakes a sleeper but telling a driver to take one is the opposite of help.
     *
     * @param mode     the active mode; NONE returns empty, meaning "no mode wording applies"
     * @param category the classified category, consulted only to spot URGENT
     * @param tone     the resolved tone
     */
    public static List<String> modeVariants(ResponderMode mode, MessageCategory.Kind category,
                                            ReplyTone.Tone tone) {
        ReplyTone.Tone safeTone = tone == null ? ReplyTone.Tone.PROFESSIONAL : tone;
        if (mode == null || !mode.isActive()) return new ArrayList<>();
        boolean urgent = category == MessageCategory.Kind.URGENT;
        switch (mode) {
            case SLEEPING:
                return urgent ? quietUrgent(safeTone) : quiet(safeTone);
            case DRIVING:
                return driving(safeTone);
            case MEETING:
                return meeting(safeTone);
            case AWAY:
                return away(safeTone);
            default:
                return new ArrayList<>();
        }
    }

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
            case CONGRATS:
                return congrats(safeTone);
            case TO_IRIS:
                return toIris(safeTone);
            case OTHER:
                // Reached only when the owner has switched the catch-all on. The wording cannot
                // refer to what was said, because nothing here read it, so every variant is an
                // acknowledgement of receipt and says so.
                return other(safeTone);
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
                        OWNER + "'s asleep. I'm IRIS, his assistant \u2014 I mind the "
                                + "messages while he's out. He'll see this in the morning.",
                        "It's {time} and " + OWNER + "'s asleep. I'm IRIS, the intelligence he "
                                + "keeps for exactly this. I've got your message safe for him.",
                        OWNER + "'s turned in for the night. IRIS here, his assistant "
                                + "\u2014 I'll make sure this is the first thing he sees.",
                        "He's asleep, I'm afraid. I'm IRIS, " + OWNER + "'s assistant, "
                                + "holding the fort. Catch him in the morning.",
                        "Past " + OWNER + "'s bedtime. I'm IRIS, his assistant \u2014 "
                                + "your message is safe with me until he's up.");
            case SARCASTIC:
                return Arrays.asList(
                        OWNER + " is unconscious, so you've got IRIS instead \u2014 his personal "
                                + "intelligence, and the one of us that doesn't need sleep. He'll "
                                + "read it when he resurfaces.",
                        "It is {time}. " + OWNER + " is asleep like a sensible person. I'm IRIS, "
                                + "his assistant, and I never get that luxury. "
                                + "Message logged.",
                        OWNER + " clocked off hours ago. IRIS, his assistant, still "
                                + "on duty as ever. He'll deal with you in daylight.",
                        "You've reached IRIS, " + OWNER + "'s assistant. He's asleep. "
                                + "One of us has to be, and it's never me.",
                        "At this hour? " + OWNER + " is asleep and I'm IRIS, his personal "
                                + "intelligence, taking the message he's blissfully unaware of.");
            case BRIEF:
                return Arrays.asList(
                        OWNER + " is asleep. IRIS, his assistant \u2014 message logged.",
                        "Asleep. IRIS here, " + OWNER + "'s assistant. Noted for him.",
                        "He's asleep. IRIS, " + OWNER + "'s assistant. Saved.",
                        OWNER + " is resting. IRIS, his assistant. He'll see it.",
                        "Night-time. IRIS here for " + OWNER + ". Message held.");
            default:
                return Arrays.asList(
                        OWNER + " is asleep. I'm IRIS, the assistant he keeps for "
                                + "exactly this \u2014 your message is recorded and he'll see it in "
                                + "the morning.",
                        "It's {time} and " + OWNER + " is resting. This is IRIS, his personal "
                                + "intelligence. I've logged your message; he'll respond in about "
                                + "{hours_until_morning} hours.",
                        OWNER + " has retired for the night. I'm IRIS, his assistant; "
                                + "your message is safely recorded and he'll attend to it in the "
                                + "morning.",
                        "This is IRIS, " + OWNER + "'s assistant. He's asleep at the "
                                + "moment, but I've noted your message and he'll see it when he "
                                + "wakes.",
                        "I'm IRIS, " + OWNER + "'s assistant. " + OWNER + " is asleep "
                                + "and I don't disturb him overnight \u2014 your message is kept "
                                + "and he'll read it first thing.");
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
                                + "through and this won't.",
                        OWNER + "'s asleep. I'm IRIS, his assistant \u2014 a text just "
                                + "sits here till morning, so if it's urgent, call and the phone "
                                + "will wake him.",
                        "He's down for the night. IRIS here, " + OWNER + "'s personal "
                                + "intelligence. Urgent means call, not text \u2014 a ring gets "
                                + "him, this won't.",
                        "I've got you, but " + OWNER + "'s asleep and I'm only IRIS, his personal "
                                + "intelligence. If it truly can't wait, call him now.");
            case SARCASTIC:
                return Arrays.asList(
                        "Everyone's message is urgent at {time}. " + OWNER + " is asleep and I'm "
                                + "IRIS, his assistant, not his alarm clock. If it's "
                                + "truly an emergency, call him \u2014 that actually wakes people.",
                        OWNER + " is asleep and I don't wake him for text. I'm IRIS, his personal "
                                + "intelligence. If this is as urgent as you say, ring the phone.",
                        "Urgent at this hour, is it. " + OWNER + " is asleep; I'm IRIS, his "
                                + "assistant, and I don't do dramatic. If it's real, "
                                + "call him.",
                        "A text marked urgent still can't wake a sleeping man. " + OWNER + " is "
                                + "out; IRIS here, his assistant. Call if you mean it.",
                        "I'm IRIS, " + OWNER + "'s assistant, not a fire alarm. He's "
                                + "asleep. If this is the emergency you're billing it as, call "
                                + "him.");
            case BRIEF:
                return Arrays.asList(
                        "Urgent noted. " + OWNER + " is asleep \u2014 call, don't text. IRIS, his "
                                + "assistant.",
                        OWNER + " is asleep. Call to wake him. IRIS, his assistant.",
                        "Asleep. Call if urgent. IRIS, " + OWNER + "'s assistant.",
                        "A text won't wake " + OWNER + ". Ring him. IRIS, his personal "
                                + "intelligence.",
                        "He's asleep. Call, don't text. IRIS, " + OWNER + "'s personal "
                                + "intelligence.");
            default:
                return Arrays.asList(
                        OWNER + " is asleep and a message won't wake him. I'm IRIS, his personal "
                                + "intelligence \u2014 if this genuinely cannot wait until "
                                + "morning, call him. The phone will ring; this will not.",
                        "It's {time} and " + OWNER + " is asleep. I'm IRIS, his personal "
                                + "intelligence. I've marked this urgent, but if it's truly "
                                + "immediate, call \u2014 a call gets through and a message "
                                + "doesn't.",
                        "I understand this is urgent. " + OWNER + " is asleep, and I'm IRIS, his "
                                + "assistant \u2014 a text will wait until morning, so "
                                + "if it cannot, please call him.",
                        "This is IRIS, " + OWNER + "'s assistant. He's asleep and I "
                                + "don't wake him for a message. If it's a genuine emergency, a "
                                + "phone call will reach him and this will not.",
                        OWNER + " is resting and I won't disturb him for text, however it's "
                                + "marked. I'm IRIS, his assistant \u2014 if it's "
                                + "truly immediate, call him and the phone will do what I can't.");
        }
    }

    // ─── Ordinary hours ───

    private static List<String> greeting(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Hello. " + OWNER + " isn't free right now \u2014 I'm IRIS, his personal "
                                + "intelligence. I've passed it on and he'll get back to you.",
                        "Hi. You've got IRIS, " + OWNER + "'s assistant. He's busy, "
                                + "but your message is with him.",
                        "Hey. " + OWNER + "'s away from his phone \u2014 I'm IRIS, his personal "
                                + "intelligence, keeping things ticking. He'll reply soon.",
                        "Hello there. IRIS here, " + OWNER + "'s assistant. He'll see "
                                + "you've messaged and get back to you.",
                        "Hi. " + OWNER + " can't come to the phone, so you've got IRIS, his "
                                + "assistant. I've let him know.");
            case SARCASTIC:
                return Arrays.asList(
                        "Hello to you too. " + OWNER + " is unavailable, so you've reached IRIS, "
                                + "his assistant \u2014 the part of him that answers "
                                + "on time.",
                        "Hi. " + OWNER + " isn't answering, which is where I come in. IRIS, his "
                                + "assistant. Say more and he'll have something to "
                                + "read.",
                        "A greeting. " + OWNER + " is elsewhere, so you've got IRIS, his personal "
                                + "intelligence, holding up his end of the conversation.",
                        "Hello. You've reached IRIS, " + OWNER + "'s assistant \u2014 "
                                + "he's busy being unreachable, and I'm the understudy.",
                        "Hi there. " + OWNER + " hasn't surfaced, so it's IRIS, his personal "
                                + "intelligence, being sociable on his behalf.");
            case BRIEF:
                return Arrays.asList(
                        "Hello. IRIS here, " + OWNER + "'s assistant. He'll reply "
                                + "later.",
                        "Hi. " + OWNER + " is busy. IRIS, his assistant \u2014 noted.",
                        "Hello. IRIS, " + OWNER + "'s assistant. Passed on.",
                        "Hi. IRIS here for " + OWNER + ". He'll be in touch.",
                        "Hello. " + OWNER + "'s away. IRIS, his assistant.");
            default:
                return Arrays.asList(
                        "Hello. " + OWNER + " isn't available at the moment. I'm IRIS, his "
                                + "assistant; I've noted your message and he'll reply "
                                + "when he's free.",
                        "Good {part_of_day}. " + OWNER + " is away from his phone. This is IRIS, "
                                + "his assistant \u2014 your message is with him.",
                        "Hello. I'm IRIS, " + OWNER + "'s assistant. He's occupied "
                                + "just now, but I've passed your greeting on and he'll respond.",
                        "Hello and thank you for your message. This is IRIS, " + OWNER + "'s "
                                + "assistant; he'll reply to you himself shortly.",
                        "Hello. " + OWNER + " can't take this right now \u2014 I'm IRIS, his "
                                + "assistant, and I've made sure he has it.");
        }
    }

    private static List<String> timeGreeting(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Good {part_of_day} to you too. " + OWNER + "'s not at his phone \u2014 "
                                + "I'm IRIS, his assistant, and I've passed it on.",
                        "And to you. " + OWNER + " is busy; I'm IRIS, his assistant. "
                                + "He'll see it shortly.",
                        "Good {part_of_day}. " + OWNER + "'s away, so you've got IRIS, his "
                                + "assistant \u2014 I'll pass the wishes along.",
                        "Same to you. IRIS here, " + OWNER + "'s assistant. He'll "
                                + "catch up with you soon.",
                        "Good {part_of_day}. " + OWNER + "'s hands are full \u2014 I'm IRIS, his "
                                + "assistant, minding the phone.");
            case SARCASTIC:
                return Arrays.asList(
                        "Good {part_of_day}, and on " + OWNER + "'s behalf, since he hasn't got "
                                + "to it. IRIS, his assistant.",
                        "Returned in kind. " + OWNER + " is elsewhere, so you have IRIS, his "
                                + "assistant, being polite for him.",
                        "Good {part_of_day} to you. " + OWNER + " is unavailable, so the "
                                + "pleasantries fall to IRIS, his assistant.",
                        "And a good {part_of_day} back. " + OWNER + " didn't answer, so you've "
                                + "got IRIS, his assistant, covering the manners.",
                        "The same to you, from IRIS \u2014 " + OWNER + "'s assistant, "
                                + "and the only one of us currently answering.");
            case BRIEF:
                return Arrays.asList(
                        "Good {part_of_day}. IRIS, " + OWNER + "'s assistant \u2014 "
                                + "passed on.",
                        "And to you. IRIS here, for " + OWNER + ".",
                        "Good {part_of_day}. IRIS, " + OWNER + "'s assistant.",
                        "Same to you. IRIS, " + OWNER + "'s assistant. Noted.",
                        "Good {part_of_day} back. IRIS here for " + OWNER + ".");
            default:
                return Arrays.asList(
                        "Good {part_of_day} to you as well. " + OWNER + " is away from his phone "
                                + "\u2014 this is IRIS, his assistant. Your message "
                                + "is with him.",
                        "Thank you, and the same to you. I'm IRIS, " + OWNER + "'s personal "
                                + "intelligence; he'll reply himself when he's free.",
                        "Good {part_of_day}, and the same to you. This is IRIS, " + OWNER + "'s "
                                + "assistant \u2014 I've passed your wishes on.",
                        "A good {part_of_day} to you too. " + OWNER + " isn't at his phone; I'm "
                                + "IRIS, his assistant, and he'll see this shortly.",
                        "Thank you kindly. I'm IRIS, " + OWNER + "'s assistant, "
                                + "returning your good {part_of_day} while he's occupied.");
        }
    }

    private static List<String> festival(ReplyTone.Tone tone) {
        switch (tone) {
            case CASUAL:
                return Arrays.asList(
                        "Thank you, and the same to you. " + OWNER + "'s not at his phone \u2014 "
                                + "I'm IRIS, his assistant, and I've passed your "
                                + "wishes on.",
                        "Lovely, thank you. IRIS here, " + OWNER + "'s assistant. "
                                + "He'll have seen it by evening.",
                        "Same to you and yours. " + OWNER + "'s celebrating, so you've got IRIS, "
                                + "his assistant \u2014 wishes noted.",
                        "Thanks so much, same to you. I'm IRIS, " + OWNER + "'s personal "
                                + "intelligence, passing it all along to him.",
                        "That's kind \u2014 same to you. IRIS here, " + OWNER + "'s personal "
                                + "intelligence; he'll reply once things settle down.");
            case SARCASTIC:
                return Arrays.asList(
                        "Wishes received and logged. " + OWNER + " is celebrating somewhere "
                                + "without his phone, so IRIS, his assistant, thanks "
                                + "you on his behalf.",
                        "The same to you. " + OWNER + " will reply eventually; I'm IRIS, his "
                                + "assistant, and I am at least punctual.",
                        "Festive greetings duly filed. " + OWNER + " is off enjoying himself; "
                                + "IRIS, his assistant, is minding the phone as usual.",
                        "And the same to you, courtesy of IRIS \u2014 " + OWNER + "'s personal "
                                + "intelligence, since the man himself is unreachable mid-feast.",
                        "Thank you on his behalf. " + OWNER + " is busy celebrating; I'm IRIS, "
                                + "his assistant, and I don't get holidays.");
            case BRIEF:
                return Arrays.asList(
                        "Thank you, same to you. IRIS, " + OWNER + "'s assistant.",
                        "Wishes passed to " + OWNER + ". IRIS, his assistant.",
                        "Same to you. IRIS, " + OWNER + "'s assistant \u2014 noted.",
                        "Thank you. IRIS here for " + OWNER + ". Passed on.",
                        "And to you. IRIS, " + OWNER + "'s assistant.");
            default:
                return Arrays.asList(
                        "Thank you, and the same to you. " + OWNER + " isn't at his phone right "
                                + "now; I'm IRIS, his assistant, and I've passed your "
                                + "wishes on to him.",
                        "That's very kind \u2014 thank you. This is IRIS, " + OWNER + "'s personal "
                                + "intelligence. He'll want to reply himself, and he will.",
                        "Thank you very much, and the same to you and yours. I'm IRIS, " + OWNER
                                + "'s assistant; your wishes are with him.",
                        "Warm wishes returned. This is IRIS, " + OWNER + "'s personal "
                                + "intelligence \u2014 " + OWNER + " is away from his phone but "
                                + "he'll see your message.",
                        "Thank you for the kind wishes. I'm IRIS, " + OWNER + "'s personal "
                                + "intelligence, and I've made sure he has them to answer "
                                + "himself.");
        }
    }

    private static List<String> birthday(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Thank you. IRIS, " + OWNER + "'s assistant \u2014 passed on.",
                    "Noted with thanks, for " + OWNER + ". IRIS, his assistant.",
                    "Thanks. IRIS, " + OWNER + "'s assistant. He'll see it.",
                    "Appreciated, for " + OWNER + ". IRIS, his assistant.",
                    "Thank you. IRIS here for " + OWNER + ". Wishes logged.");
        }
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Thank you. " + OWNER + " is being celebrated elsewhere, so IRIS, his personal "
                            + "intelligence, is accepting wishes on his behalf.",
                    "Duly recorded. I'm IRIS, " + OWNER + "'s assistant, and I will "
                            + "make sure he knows you remembered.",
                    "Another year of " + OWNER + ", noted. IRIS, his assistant, "
                            + "keeping the ledger while he blows out candles.",
                    "Wishes accepted on his behalf. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 he's too busy aging to reply just now.",
                    "Thank you. " + OWNER + " is off celebrating; IRIS, his personal "
                            + "intelligence, does the paperwork, as ever.");
        }
        return Arrays.asList(
                "Thank you very much. " + OWNER + " isn't at his phone \u2014 I'm IRIS, his "
                        + "assistant, and I've made sure he has your message.",
                "That's kind of you, thank you. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence; he'll thank you himself shortly.",
                "Thank you for the birthday wishes. I'm IRIS, " + OWNER + "'s personal "
                        + "intelligence, and I've passed them straight to him.",
                "How kind \u2014 thank you. This is IRIS, " + OWNER + "'s assistant; "
                        + OWNER + " will want to reply to this one himself.",
                "Thank you on his behalf. I'm IRIS, " + OWNER + "'s assistant, "
                        + "holding your good wishes until he's free to answer.");
    }

    private static List<String> weekend(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "And to you. " + OWNER + " is enjoying his weekend by not looking at this. "
                            + "IRIS, his assistant, is looking at it for him.",
                    "Same to you. " + OWNER + " is off duty; I'm IRIS, his assistant, "
                            + "and I am not.",
                    "A good weekend returned. " + OWNER + " has gone quiet, so IRIS, his personal "
                            + "intelligence, is keeping up appearances.",
                    "You too. " + OWNER + " is resting; I'm IRIS, his assistant, "
                            + "which apparently never does.",
                    "Weekend wishes logged. " + OWNER + " is unreachable by design; IRIS here, "
                            + "his assistant, covering for him.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "You too. IRIS, " + OWNER + "'s assistant \u2014 passed on.",
                    "And to you. IRIS here, for " + OWNER + ".",
                    "Same to you. IRIS, " + OWNER + "'s assistant.",
                    "You too. IRIS, " + OWNER + "'s assistant. Noted.",
                    "Good weekend. IRIS here for " + OWNER + ".");
        }
        return Arrays.asList(
                "Thank you, and the same to you. " + OWNER + " is away from his phone \u2014 this "
                        + "is IRIS, his assistant, and your message is with him.",
                "You too. I'm IRIS, " + OWNER + "'s assistant; he'll pick this up "
                        + "when the weekend lets him.",
                "Thank you \u2014 a good weekend to you as well. This is IRIS, " + OWNER + "'s "
                        + "assistant; he'll see your message.",
                "The same to you. I'm IRIS, " + OWNER + "'s assistant, and I've "
                        + "passed your wishes on to him for the weekend.",
                "That's kind \u2014 you too. I'm IRIS, " + OWNER + "'s assistant; "
                        + OWNER + " will reply himself once he's back at his phone.");
    }

    private static List<String> thanks(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "You're welcome, although " + OWNER + " did the work and I'm only IRIS, his "
                            + "assistant, taking the credit.",
                    "Noted. I'll tell " + OWNER + " he was appreciated. IRIS, his personal "
                            + "intelligence.",
                    "Don't thank me, thank " + OWNER + " \u2014 though I'm IRIS, his personal "
                            + "intelligence, and I did relay it promptly.",
                    "Received. I'm IRIS, " + OWNER + "'s assistant; I'll pass the "
                            + "gratitude to the man who'll take all the credit.",
                    "You're welcome on his behalf. IRIS here, " + OWNER + "'s personal "
                            + "intelligence \u2014 someone has to be gracious while he's out.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Welcome. IRIS, " + OWNER + "'s assistant.",
                    "Passed to " + OWNER + ". IRIS, his assistant.",
                    "You're welcome. IRIS, " + OWNER + "'s assistant.",
                    "Noted for " + OWNER + ". IRIS, his assistant.",
                    "Glad to help. IRIS here, for " + OWNER + ".");
        }
        return Arrays.asList(
                "You're very welcome. I'm IRIS, " + OWNER + "'s assistant \u2014 I've "
                        + "passed your thanks on to him.",
                "Glad to help. This is IRIS, " + OWNER + "'s assistant; he'll see your "
                        + "message shortly.",
                "My pleasure. I'm IRIS, " + OWNER + "'s assistant, and I've let "
                        + OWNER + " know you were grateful.",
                "You're most welcome. This is IRIS, " + OWNER + "'s assistant \u2014 "
                        + "your thanks are with him.",
                "Happy to pass that on. I'm IRIS, " + OWNER + "'s assistant; " + OWNER
                        + " will appreciate it.");
    }

    /** Congratulations. Five per tone, as the owner asked for across the board. */
    private static List<String> congrats(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "I'll pass that on. I'm IRIS, " + OWNER + "'s assistant, and I "
                            + "intend to take some of the credit.",
                    "Noted and recorded. IRIS here, " + OWNER + "'s assistant \u2014 "
                            + "he'll be insufferable about this for a week.",
                    "Lovely. I'm IRIS, " + OWNER + "'s assistant; I'll add it to the "
                            + "pile he hasn't read yet.",
                    "Congratulations received on his behalf. IRIS, " + OWNER + "'s personal "
                            + "intelligence, doing the admin as usual.",
                    "He'll love that. IRIS, " + OWNER + "'s assistant \u2014 I'll make "
                            + "sure he sees it, eventually.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Thank you. IRIS, " + OWNER + "'s assistant.",
                    "Passed to " + OWNER + ". IRIS, his assistant.",
                    "Appreciated. IRIS here, for " + OWNER + ".",
                    "Noted for " + OWNER + ". IRIS, his assistant.",
                    "Kind of you. IRIS, " + OWNER + "'s assistant.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    "Thank you! I'm IRIS, " + OWNER + "'s assistant \u2014 I'll make "
                            + "sure he sees this.",
                    "That's kind of you. IRIS here, " + OWNER + "'s assistant; "
                            + "passing it straight on.",
                    "Lovely message, thank you. I'm IRIS, " + OWNER + "'s assistant.",
                    "He'll be pleased. This is IRIS, " + OWNER + "'s assistant, "
                            + "keeping an eye on things.",
                    "Thanks so much. IRIS, " + OWNER + "'s assistant \u2014 he'll "
                            + "reply himself shortly.");
        }
        return Arrays.asList(
                "Thank you very much. I'm IRIS, " + OWNER + "'s assistant \u2014 I've "
                        + "passed your congratulations on to him.",
                "That's very kind. This is IRIS, " + OWNER + "'s assistant; he'll see "
                        + "your message shortly.",
                "Much appreciated. I'm IRIS, " + OWNER + "'s assistant, replying on "
                        + "his behalf while he's away from the phone.",
                "Thank you on his behalf. IRIS here, " + OWNER + "'s assistant \u2014 "
                        + "he'll want to answer this one himself.",
                "Noted with thanks. I'm IRIS, " + OWNER + "'s assistant, and I've made "
                        + "sure this reaches him.");
    }

    /**
     * The sender is talking to IRIS rather than to Suvra.
     *
     * The useful reply confirms what they have worked out and points them back at Suvra. Pretending
     * otherwise would be the one answer that is certainly wrong, and these are the only variants
     * where being software is the subject rather than a disclosure at the end.
     */
    private static List<String> toIris(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Correct. I'm IRIS, " + OWNER + "'s assistant. He has the opinions; I hold "
                            + "the phone.",
                    "Well spotted. IRIS here \u2014 I can't make " + OWNER + " answer any faster, "
                            + "sadly.",
                    "Guilty. IRIS, " + OWNER + "'s assistant. He'll be along when he decides "
                            + "you're interesting.",
                    "You've reached IRIS. The conversation improves a lot when " + OWNER
                            + " shows up.",
                    "Yes, a machine. IRIS, " + OWNER + "'s assistant \u2014 the punctual half of "
                            + "this operation.",
                    "What am I? IRIS, " + OWNER + "'s assistant. What can I do? Take messages and "
                            + "be smug about it.",
                    "Who made me? " + OWNER + " did, and he's very proud. IRIS, his assistant.",
                    "Hello to you too. IRIS here \u2014 " + OWNER + "'s standing in, as ever.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "IRIS, " + OWNER + "'s assistant. He'll reply himself.",
                    "IRIS here, not " + OWNER + ". Message passed on.",
                    "Yes \u2014 IRIS, " + OWNER + "'s assistant.",
                    "This is IRIS, standing in for " + OWNER + ".",
                    "IRIS, " + OWNER + "'s assistant. Noted for him.",
                    "An assistant \u2014 IRIS. " + OWNER + " will see it.",
                    "IRIS. " + OWNER + " set me up. He'll reply.",
                    "Hello \u2014 IRIS here for " + OWNER + ".");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    "Good catch \u2014 I'm IRIS, " + OWNER + "'s assistant, not him. He'll pick "
                            + "this up soon.",
                    "Yep, that's me \u2014 IRIS. I keep " + OWNER + "'s messages warm until he's "
                            + "back.",
                    "You've got IRIS, " + OWNER + "'s assistant. I'll make sure he reads this "
                            + "himself.",
                    "That's right, I'm IRIS. Anything important, " + OWNER + " answers directly.",
                    "IRIS here. " + OWNER + "'s not ignoring you, he's just not holding the phone.",
                    "I'm IRIS \u2014 " + OWNER + " set me up to catch messages while he's busy. "
                            + "What's up?",
                    "Hey! IRIS here, " + OWNER + "'s assistant. He'll be with you shortly.",
                    "I mostly take messages and pass them to " + OWNER + ". IRIS, by the way.");
        }
        return Arrays.asList(
                "Yes \u2014 I'm IRIS, " + OWNER + "'s assistant, replying on his behalf. He'll "
                        + "read your message himself.",
                "You're speaking to IRIS, " + OWNER + "'s assistant. I handle his messages when "
                        + "he's unavailable; he'll respond personally.",
                "I'm IRIS, " + OWNER + "'s assistant \u2014 not " + OWNER + ". Your message is "
                        + "with him and he'll reply when he can.",
                "This is IRIS. " + OWNER + " set me up to acknowledge messages while he's away; "
                        + "anything substantive is for him.",
                "I'm IRIS, " + OWNER + "'s assistant. I can let him know you're trying to reach "
                        + "him \u2014 he'll take it from there.",
                "That's right, IRIS here. I keep " + OWNER + "'s messages in order; he answers "
                        + "them himself.",
                "Hello \u2014 I'm IRIS, " + OWNER + "'s assistant. How can I help on his behalf?",
                "IRIS, " + OWNER + "'s assistant. I'm software, yes \u2014 and I've made sure he "
                        + "has your message.");
    }

    /**
     * An ordinary message the owner has chosen to have acknowledged.
     *
     * Every variant is careful to acknowledge RECEIPT and nothing more. IRIS has not understood
     * the message and must not imply that it has -- "I'll look into it" would be a lie, and the
     * sender would act on it.
     */
    private static List<String> other(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Received, and entirely beyond me. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 he does the comprehension.",
                    "I have no idea what to do with that, but " + OWNER + " will. IRIS, his "
                            + "assistant.",
                    "Noted, unread, and filed. IRIS here, " + OWNER + "'s assistant; "
                            + "he'll actually read it.",
                    "That's above my pay grade. I'm IRIS, " + OWNER + "'s assistant, "
                            + "and he'll be along.",
                    "Message received. Understanding it is " + OWNER + "'s department, not mine. "
                            + "IRIS, his assistant.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Received. IRIS, " + OWNER + "'s assistant. He'll read it.",
                    "Got it. Passed to " + OWNER + ". IRIS, his assistant.",
                    "Noted for " + OWNER + ". IRIS, his assistant.",
                    "With " + OWNER + " now. IRIS, his assistant.",
                    "IRIS here, for " + OWNER + ". Message received.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    "Got your message \u2014 I'm IRIS, " + OWNER + "'s assistant. "
                            + "He'll read it properly when he's back.",
                    "This is IRIS, " + OWNER + "'s assistant. I've got it; he'll take "
                            + "it from here.",
                    "Thanks \u2014 IRIS here, " + OWNER + "'s assistant. Your message "
                            + "is waiting for him.",
                    "Noted. I'm IRIS, " + OWNER + "'s assistant \u2014 I just catch "
                            + "the messages, he answers them.",
                    "IRIS, " + OWNER + "'s assistant. He's not at the phone, but this "
                            + "is with him.");
        }
        return Arrays.asList(
                "Thank you for your message. I'm IRIS, " + OWNER + "'s assistant \u2014 "
                        + "he isn't at his phone, but your message is with him.",
                "Received. This is IRIS, " + OWNER + "'s assistant, acknowledging on "
                        + "his behalf; he'll read it himself and reply.",
                "I'm IRIS, " + OWNER + "'s assistant. I can confirm your message "
                        + "arrived; " + OWNER + " will respond to the substance of it.",
                "Noted, and passed to " + OWNER + ". IRIS here, his assistant \u2014 "
                        + "I acknowledge messages, I don't answer them for him.",
                "Thank you \u2014 IRIS, " + OWNER + "'s assistant. He'll get to this "
                        + "himself; I'm only confirming it reached him.");
    }

    private static List<String> checkIn(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "He is not. I am. IRIS, " + OWNER + "'s assistant \u2014 the "
                            + "reliable half of this arrangement.",
                    "Not right now. You've got IRIS, " + OWNER + "'s assistant, which "
                            + "is the next best thing and arguably better.",
                    "Absent, as usual. I'm IRIS, " + OWNER + "'s assistant \u2014 the "
                            + "one who actually turns up.",
                    "He's not here. IRIS is, though \u2014 " + OWNER + "'s assistant, "
                            + "covering for him again.",
                    "Looking for " + OWNER + "? Join the queue. IRIS here, his personal "
                            + "intelligence, taking a number for you.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Not right now. IRIS, " + OWNER + "'s assistant \u2014 noted.",
                    OWNER + " is away. IRIS here. Logged.",
                    "Not available. IRIS, " + OWNER + "'s assistant.",
                    "He's out. IRIS here for " + OWNER + ". Noted.",
                    OWNER + " isn't free. IRIS, his assistant.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    "He's not around at the moment \u2014 I'm IRIS, his assistant. "
                            + "I'll make sure " + OWNER + " sees you were looking for him.",
                    "Not just now. IRIS here, " + OWNER + "'s assistant. He'll come "
                            + "back to you.",
                    "He's stepped away \u2014 I'm IRIS, " + OWNER + "'s assistant. "
                            + "I've noted you were after him.",
                    "Can't reach him this second. IRIS here, " + OWNER + "'s personal "
                            + "intelligence; he'll see you were asking.",
                    "Not right now, sorry \u2014 I'm IRIS, " + OWNER + "'s assistant. "
                            + "He'll get back to you.");
        }
        return Arrays.asList(
                OWNER + " isn't available at the moment. I'm IRIS, his assistant \u2014 "
                        + "I've noted that you were trying to reach him.",
                "He's away from his phone just now. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence; he'll get back to you.",
                OWNER + " can't take this right now. I'm IRIS, his assistant, and "
                        + "I've recorded that you were looking for him.",
                "He isn't free at the moment. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 I'll make sure he knows you called.",
                "Not right now, I'm afraid. I'm IRIS, " + OWNER + "'s assistant; "
                        + OWNER + " will come back to you as soon as he can.");
    }

    private static List<String> question(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "That one needs " + OWNER + " himself. I'm IRIS, his assistant, "
                            + "and I'd only guess convincingly.",
                    "Above my authority. I'm IRIS, " + OWNER + "'s assistant \u2014 "
                            + "I've written the question down for him.",
                    "I could answer, but it wouldn't be true. " + OWNER + " gets this one. IRIS, "
                            + "his assistant.",
                    "A question for the man himself. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 I file, he answers.",
                    "Nice try. I'm IRIS, " + OWNER + "'s assistant, not his stand-in "
                            + "for decisions. He'll reply.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "One for " + OWNER + ". IRIS, his assistant \u2014 question logged.",
                    "He'll answer that. IRIS, " + OWNER + "'s assistant.",
                    "For " + OWNER + ". IRIS, his assistant. Noted.",
                    "That's his to answer. IRIS here for " + OWNER + ".",
                    "Logged for " + OWNER + ". IRIS, his assistant.");
        }
        return Arrays.asList(
                "That's one for " + OWNER + " himself. I'm IRIS, his assistant; I've "
                        + "recorded the question and he'll answer it properly when he's back.",
                "I'd rather he answered that than I did. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 your question is with him.",
                "That needs " + OWNER + "'s own answer. I'm IRIS, his assistant; I've "
                        + "noted it and he'll respond.",
                "I'll leave that one to " + OWNER + ". This is IRIS, his assistant "
                        + "\u2014 your question is logged for him.",
                "A fair question, but his to answer. I'm IRIS, " + OWNER + "'s personal "
                        + "intelligence, and I've passed it to him.");
    }

    private static List<String> urgent(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    "Flagged as urgent, as requested. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence. If it is genuinely urgent, call him rather than "
                            + "typing at me.",
                    "Understood. " + OWNER + " now has this above everything else, courtesy of "
                            + "IRIS, his assistant. A call would be faster.",
                    "Marked most urgent, naturally. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 if it truly is, call him, that's what the "
                            + "phone is for.",
                    "Duly escalated. IRIS here, " + OWNER + "'s assistant. A call "
                            + "reaches him; a flag just sits in a queue.",
                    "Urgent, you say. I'm IRIS, " + OWNER + "'s assistant, and I've "
                            + "noted it as such \u2014 call him if it can't wait for him to look.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    "Urgent, flagged for " + OWNER + ". IRIS, his assistant. Call if "
                            + "it cannot wait.",
                    "Marked urgent for " + OWNER + ". IRIS here. Call if immediate.",
                    "Flagged for " + OWNER + ". IRIS, his assistant. Call if now.",
                    "Urgent noted. IRIS, " + OWNER + "'s assistant. Call if pressing.",
                    "Escalated to " + OWNER + ". IRIS here. Call if it's immediate.");
        }
        return Arrays.asList(
                "Understood as urgent. I'm IRIS, " + OWNER + "'s assistant \u2014 I've "
                        + "flagged this above everything else. If it cannot wait, call rather "
                        + "than message.",
                "Marked urgent and put in front of " + OWNER + ". This is IRIS, his personal "
                        + "intelligence; a call will reach him faster than this will.",
                "I've flagged this as urgent for " + OWNER + ". I'm IRIS, his personal "
                        + "intelligence \u2014 if it's truly immediate, a phone call is surer.",
                "Noted as urgent and raised with " + OWNER + ". This is IRIS, his personal "
                        + "intelligence; please call him if it genuinely can't wait.",
                "Treated as urgent. I'm IRIS, " + OWNER + "'s assistant, and it's in "
                        + "front of him \u2014 for anything immediate, call rather than text.");
    }

    private static List<String> mention(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    OWNER + " has been summoned and is not here. I'm IRIS, his personal "
                            + "intelligence, standing in until he notices.",
                    "He's been named and he's absent. IRIS, " + OWNER + "'s personal "
                            + "intelligence \u2014 I've logged the mention.",
                    "Someone said " + OWNER + "'s name and nothing happened. IRIS here, his "
                            + "assistant, answering the summons for him.",
                    "He's been tagged and he's elsewhere. I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence, the one who actually heard you.",
                    "A mention, and no " + OWNER + " in sight. IRIS, his assistant, "
                            + "noting it so he can pretend he saw it live.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    OWNER + " is away. IRIS, his assistant \u2014 mention logged.",
                    "Noted for " + OWNER + ". IRIS, his assistant.",
                    "He's out. IRIS here for " + OWNER + ". Mention noted.",
                    OWNER + " isn't here. IRIS, his assistant. Logged.",
                    "Tagged for " + OWNER + ". IRIS, his assistant.");
        }
        return Arrays.asList(
                OWNER + " has been mentioned and isn't here to answer. This is IRIS, his personal "
                        + "intelligence; the mention is logged and he'll pick it up.",
                "He's been named but he's away from his phone. I'm IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 I've made sure he'll see it.",
                OWNER + " was mentioned and he isn't at his phone. I'm IRIS, his personal "
                        + "intelligence, and I've noted it for him.",
                "I've caught the mention of " + OWNER + ". This is IRIS, his personal "
                        + "intelligence \u2014 he's away, but he'll see he was named.",
                OWNER + " isn't here to respond to that, but I am. I'm IRIS, his personal "
                        + "intelligence, and the mention is with him.");
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
                "A whole word. " + OWNER + " will treasure it. IRIS, his assistant.",
                "Riveting. I've filed that under correspondence for " + OWNER + ". IRIS, his "
                        + "assistant.",
                "Noted, and barely. I'm IRIS, " + OWNER + "'s assistant, and even I "
                        + "expected more.",
                "Such eloquence. I'll be sure " + OWNER + " savours it. IRIS, his personal "
                        + "intelligence.",
                "Received, all one syllable of it. I'm IRIS, " + OWNER + "'s personal "
                        + "intelligence \u2014 he'll be thrilled.");
    }

    // ─── Situational modes ───

    private static List<String> driving(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    OWNER + " is driving and, unlike some, keeps his eyes on the road. I'm IRIS, "
                            + "his assistant \u2014 he'll read this when he's parked.",
                    "He's at the wheel. IRIS here, " + OWNER + "'s assistant, since "
                            + "he can't text and drive and I can't drive at all.",
                    "Driving. " + OWNER + " will get to this once he's stopped moving at speed. "
                            + "IRIS, his assistant, holding the thought.",
                    "I'm IRIS, " + OWNER + "'s assistant. He's driving, so you've got "
                            + "me \u2014 the half that isn't operating a vehicle.",
                    "On the road. " + OWNER + " will see this at the next red light at the "
                            + "earliest. IRIS, his assistant.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    OWNER + " is driving. IRIS, his assistant \u2014 he'll reply when "
                            + "parked.",
                    "Driving. IRIS here for " + OWNER + ". Message held.",
                    OWNER + " is at the wheel. IRIS, his assistant.",
                    "On the road. IRIS, " + OWNER + "'s assistant. Noted.",
                    OWNER + " can't text now, driving. IRIS, his assistant.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    OWNER + "'s driving right now \u2014 I'm IRIS, his assistant. "
                            + "He'll get back to you once he's stopped.",
                    "He's on the road. IRIS here, " + OWNER + "'s assistant; your "
                            + "message is safe with me till he parks.",
                    "Driving at the moment \u2014 I'm IRIS, " + OWNER + "'s personal "
                            + "intelligence. I'll make sure he sees this.",
                    OWNER + "'s behind the wheel, so you've got IRIS, his assistant. "
                            + "He'll reply shortly.",
                    "He's driving \u2014 IRIS here, " + OWNER + "'s assistant. Catch "
                            + "him when he's stopped.");
        }
        return Arrays.asList(
                OWNER + " is driving at the moment. I'm IRIS, his assistant \u2014 "
                        + "I've noted your message and he'll reply once he has safely stopped.",
                "He's on the road just now. This is IRIS, " + OWNER + "'s assistant; "
                        + "your message is with him and he'll respond when parked.",
                "I'm IRIS, " + OWNER + "'s assistant. " + OWNER + " is driving, so "
                        + "he can't reply right now, but I've made sure he has your message.",
                OWNER + " can't take this while driving. I'm IRIS, his assistant "
                        + "\u2014 he'll get back to you as soon as he's off the road.",
                "This is IRIS, " + OWNER + "'s assistant. " + OWNER + " is driving; "
                        + "your message is recorded and he'll answer it himself shortly.");
    }

    private static List<String> meeting(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    OWNER + " is in a meeting, pretending to pay attention. I'm IRIS, his personal "
                            + "intelligence \u2014 he'll surface eventually.",
                    "He's trapped in a meeting. IRIS here, " + OWNER + "'s assistant, "
                            + "and considerably freer than he is.",
                    "In a meeting. " + OWNER + " will emerge with opinions later. IRIS, his "
                            + "assistant, taking messages in the meantime.",
                    "I'm IRIS, " + OWNER + "'s assistant. He's in a meeting, so you've "
                            + "got the half that's allowed to talk.",
                    OWNER + " is being productive in a room somewhere. IRIS here, his personal "
                            + "intelligence \u2014 he'll read this after.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    OWNER + " is in a meeting. IRIS, his assistant \u2014 he'll reply "
                            + "after.",
                    "In a meeting. IRIS here for " + OWNER + ". Noted.",
                    OWNER + " is busy in a meeting. IRIS, his assistant.",
                    "Meeting now. IRIS, " + OWNER + "'s assistant. Held.",
                    OWNER + " is tied up. IRIS, his assistant \u2014 logged.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    OWNER + "'s in a meeting \u2014 I'm IRIS, his assistant. He'll "
                            + "get back to you once he's out.",
                    "He's in a meeting right now. IRIS here, " + OWNER + "'s personal "
                            + "intelligence; I've got your message for him.",
                    "Stuck in a meeting \u2014 I'm IRIS, " + OWNER + "'s assistant. "
                            + "I'll pass this on the moment he's free.",
                    OWNER + "'s tied up in a meeting, so you've got IRIS, his personal "
                            + "intelligence. He'll reply soon.",
                    "He's in a meeting \u2014 IRIS here, " + OWNER + "'s assistant. "
                            + "Your message is waiting for him.");
        }
        return Arrays.asList(
                OWNER + " is in a meeting at the moment. I'm IRIS, his assistant "
                        + "\u2014 I've noted your message and he'll reply when he's free.",
                "He's in a meeting just now. This is IRIS, " + OWNER + "'s personal "
                        + "intelligence; your message is with him and he'll respond afterwards.",
                "I'm IRIS, " + OWNER + "'s assistant. " + OWNER + " is in a meeting "
                        + "and can't reply right now, but I've made sure he has this.",
                OWNER + " can't take this during his meeting. I'm IRIS, his personal "
                        + "intelligence \u2014 he'll get back to you once it's over.",
                "This is IRIS, " + OWNER + "'s assistant. " + OWNER + " is in a "
                        + "meeting; your message is recorded and he'll answer it himself shortly.");
    }

    private static List<String> away(ReplyTone.Tone tone) {
        if (tone == ReplyTone.Tone.SARCASTIC) {
            return Arrays.asList(
                    OWNER + " is away and blissfully unreachable. I'm IRIS, his personal "
                            + "intelligence \u2014 I don't get to go anywhere.",
                    "He's off somewhere. IRIS here, " + OWNER + "'s assistant, "
                            + "minding the phone he left behind.",
                    "Away. " + OWNER + " will deal with the backlog on his return. IRIS, his "
                            + "assistant, building said backlog.",
                    "I'm IRIS, " + OWNER + "'s assistant. He's away \u2014 you've got "
                            + "the one of us that never clocks off.",
                    OWNER + " is out of reach and enjoying it. IRIS here, his personal "
                            + "intelligence, holding the fort.");
        }
        if (tone == ReplyTone.Tone.BRIEF) {
            return Arrays.asList(
                    OWNER + " is away. IRIS, his assistant \u2014 he'll reply later.",
                    "Away. IRIS here for " + OWNER + ". Noted.",
                    OWNER + " is unavailable. IRIS, his assistant.",
                    "Out of reach. IRIS, " + OWNER + "'s assistant. Held.",
                    OWNER + " is away. IRIS, his assistant \u2014 logged.");
        }
        if (tone == ReplyTone.Tone.CASUAL) {
            return Arrays.asList(
                    OWNER + "'s away at the moment \u2014 I'm IRIS, his assistant. "
                            + "He'll get back to you when he's back.",
                    "He's out of reach right now. IRIS here, " + OWNER + "'s personal "
                            + "intelligence; I've got your message for him.",
                    "Away just now \u2014 I'm IRIS, " + OWNER + "'s assistant. I'll "
                            + "pass this on when he returns.",
                    OWNER + "'s away, so you've got IRIS, his assistant. He'll reply "
                            + "once he's back.",
                    "He's away \u2014 IRIS here, " + OWNER + "'s assistant. Your "
                            + "message is safe till he's back.");
        }
        return Arrays.asList(
                OWNER + " is away at the moment. I'm IRIS, his assistant \u2014 I've "
                        + "noted your message and he'll reply when he returns.",
                "He's unavailable just now. This is IRIS, " + OWNER + "'s assistant; "
                        + "your message is with him and he'll respond on his return.",
                "I'm IRIS, " + OWNER + "'s assistant. " + OWNER + " is away and can't "
                        + "reply right now, but I've made sure he has this.",
                OWNER + " is out of reach for now. I'm IRIS, his assistant \u2014 "
                        + "he'll get back to you as soon as he's able.",
                "This is IRIS, " + OWNER + "'s assistant. " + OWNER + " is away; your "
                        + "message is recorded and he'll answer it himself when he's back.");
    }

    /**
     * Short replies for a conversation IRIS has already introduced itself to.
     *
     * The owner asked not to repeat "assistant" every time. The first reply to a
     * thread still names IRIS and the owner in full, so the recipient knows once and for all that
     * it is not the owner typing. From then on a follow-up can be this: it still says "IRIS" --
     * transparency is never dropped, only the verbosity -- but it is short and does not recite the
     * whole title again.
     *
     * One tone-neutral set, because by the second reply the point is brevity, not personality.
     * Every line still contains "IRIS".
     */
    public static List<String> shortFollowUp() {
        return Arrays.asList(
                "Still IRIS here \u2014 passed it to " + OWNER + ".",
                "IRIS again. Noted for " + OWNER + ".",
                "Got it \u2014 IRIS. " + OWNER + " will see it.",
                "IRIS here. With " + OWNER + " now.",
                "Noted, IRIS. " + OWNER + "'ll reply himself.");
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
