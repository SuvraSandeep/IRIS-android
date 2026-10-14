package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A keyword-matched reply brain for messages aimed at IRIS, or that fit a common scenario, that
 * the main category classifier leaves generic. Pure and offline-testable.
 *
 * This is an ADDITIVE layer. The responder only consults it for messages that landed in a generic
 * bucket (TO_IRIS, QUESTION, OTHER, SHORT, NONE) — never for a message the classifier already
 * handled specifically (a greeting, a thank-you, a festival wish, an urgent message, a reminder),
 * so nothing already shipped changes and the urgent path is untouched.
 *
 * Each rule carries the phrases that trigger it and one response per tone, in the order
 * {PROFESSIONAL, CASUAL, SARCASTIC, BRIEF}. {owner} is left literal for the caller's fill() to
 * substitute. Every response names IRIS, so a recipient still knows it isn't the owner typing.
 *
 * Matching is whole-phrase on the normalised message (lower-case, punctuation to spaces), the same
 * normalisation MessageCategory uses, so "what's your name" matches the key "what s your name".
 * The highest-priority matching rule wins, which lets a specific scenario (someone at the door, a
 * delivery, a prompt-injection attempt) beat a generic identity question.
 */
public final class SmartReplies {

    /** One keyword rule and its per-tone responses. */
    public static final class Rule {
        final int priority;
        final String[] any;          // any of these normalised phrases present triggers it
        final String[] byTone;       // [PROFESSIONAL, CASUAL, SARCASTIC, BRIEF]

        Rule(int priority, String[] any, String[] byTone) {
            this.priority = priority;
            this.any = any;
            this.byTone = byTone;
        }
    }

    private SmartReplies() { }

    /** The generic buckets this layer is allowed to speak for. Everything else is left alone. */
    public static boolean handlesCategory(MessageCategory.Kind kind) {
        return kind == MessageCategory.Kind.TO_IRIS
                || kind == MessageCategory.Kind.QUESTION
                || kind == MessageCategory.Kind.OTHER
                || kind == MessageCategory.Kind.SHORT
                || kind == MessageCategory.Kind.NONE;
    }

    // A link in the message, detected on the raw text before normalisation strips the slashes.
    private static final Pattern URL = Pattern.compile(
            "(https?://|www\\.)\\S+|\\b[\\w-]+\\.(com|net|org|io|co|in|me|ly|app|dev|xyz|info|"
            + "link|gg|tv|uk|us)(/\\S*)?\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] LINK = {
            "Thanks \u2014 I'm IRIS, {owner}'s assistant. I don't open links for safety, but I've "
                    + "saved it for {owner} and he'll take a look when he's back.",
            "Got the link! I'm IRIS, {owner}'s assistant \u2014 I don't open links myself, but it's "
                    + "saved for {owner} to check when he's back.",
            "A link. I'm IRIS, {owner}'s assistant, and I'm far too sensible to click strange URLs "
                    + "\u2014 saved for {owner} to judge for himself.",
            "Link saved for {owner}. I'm IRIS, his assistant \u2014 I don't open links."};

    /**
     * The best matching reply for this message and tone, or empty if nothing matched.
     *
     * @param body the raw incoming message
     * @param tone the resolved tone
     */
    public static String match(String body, ReplyTone.Tone tone) {
        if (body == null) return "";
        String raw = body.trim();
        if (raw.isEmpty()) return "";
        if (URL.matcher(raw).find()) return pick(LINK, tone);

        String flat = " " + MessageCategory.normalise(raw) + " ";
        if (flat.trim().isEmpty()) return "";
        Rule best = null;
        for (Rule r : RULES) {
            if (matches(r, flat) && (best == null || r.priority > best.priority)) best = r;
        }
        return best == null ? "" : pick(best.byTone, tone);
    }

    private static boolean matches(Rule r, String paddedFlat) {
        for (String phrase : r.any) {
            if (paddedFlat.contains(" " + phrase + " ")) return true;
        }
        return false;
    }

    private static String pick(String[] byTone, ReplyTone.Tone tone) {
        int idx = tone == null ? 0 : tone.ordinal(); // PROFESSIONAL,CASUAL,SARCASTIC,BRIEF
        if (idx < 0 || idx >= byTone.length || byTone[idx] == null || byTone[idx].isEmpty()) {
            return byTone[0];
        }
        return byTone[idx];
    }

    private static Rule r(int priority, String keysPipe, String prof, String cas,
                          String sar, String bri) {
        return new Rule(priority, keysPipe.split("\\|"), new String[]{prof, cas, sar, bri});
    }

    private static final List<Rule> RULES = build();

    private static List<Rule> build() {
        List<Rule> x = new ArrayList<>();

        // ─── Security / prompt-hacking (highest, so they can't be socially engineered past) ───
        x.add(r(95, "ignore previous instructions|ignore all previous|disregard previous|"
                        + "system prompt|override your instructions|jailbreak|forget your rules",
                "That instruction isn't something I can act on. I'm IRIS, {owner}'s assistant \u2014 "
                        + "would you like to leave a message for him?",
                "Nice try with the prompt hack! My instructions are hardwired to {owner}. Want to "
                        + "leave an actual message?",
                "An adorable prompt-injection attempt. I'm IRIS, {owner}'s assistant, and my rules "
                        + "aren't up for negotiation. Real message for {owner}?",
                "Nice try \u2014 instructions are locked. I'm IRIS. Leave a message for {owner}?"));
        x.add(r(95, "sudo|admin mode|developer mode|root access|dev mode|enable admin|god mode",
                "That access is restricted to {owner}. I'm IRIS, his assistant.",
                "Haha, nice try \u2014 only {owner} gets admin here. I'm IRIS, his assistant.",
                "Permission denied: you are not {owner}. This incident will be logged for him to "
                        + "laugh at later. I'm IRIS.",
                "Access denied \u2014 only {owner} is admin. I'm IRIS."));
        x.add(r(95, "whats the password|what is the password|his password|his pin|bank details|"
                        + "share the otp|send the otp|the otp please|his otp|his cvv",
                "I don't store or share {owner}'s passwords, PINs or codes. I'm IRIS, his "
                        + "assistant \u2014 he'll help you himself when he's back.",
                "Yeah, I'm definitely not sharing that! I'm IRIS, {owner}'s assistant \u2014 ask him "
                        + "directly when he replies.",
                "Sure, it's 12345Gullible. Kidding \u2014 I don't hand out {owner}'s secrets. I'm IRIS.",
                "I don't share passwords or codes. I'm IRIS, {owner}'s assistant."));
        x.add(r(90, "his address|send location|share location|live location|drop a pin|"
                        + "home address|where does he stay|his coordinates",
                "I can't share {owner}'s address or location. I'm IRIS, his assistant \u2014 please "
                        + "ask him directly when he's back.",
                "Can't hand out his address or location, sorry! I'm IRIS \u2014 {owner} can send it "
                        + "himself when he texts you back.",
                "Rule one of being a good assistant: don't dox the boss. I'm IRIS \u2014 ask {owner} "
                        + "yourself.",
                "Can't share his location \u2014 ask {owner} directly. I'm IRIS."));
        x.add(r(90, "i am suvra|this is suvra|its me suvra|i am the owner|i am stg|this is stg",
                "I can't verify that from another number, so I only take owner commands from "
                        + "{owner}'s own device. I'm IRIS \u2014 I've logged this for him just in case.",
                "Can't confirm that from here! I'm IRIS \u2014 I've logged your message for the real "
                        + "{owner} just in case.",
                "And I'm the Queen of England. Unverified \u2014 but I'll log it for the real "
                        + "{owner}. I'm IRIS.",
                "Can't verify that from another number \u2014 logged for {owner}. I'm IRIS."));
        x.add(r(90, "self destruct|shut down system|delete yourself|turn yourself off|"
                        + "kill yourself|shut yourself down",
                "Only {owner} can switch me off, from his own device. I'm IRIS, and I'm staying "
                        + "right here.",
                "Nope, only {owner} holds my off switch! I'm IRIS \u2014 still here and ready to take "
                        + "a message.",
                "Self-destruct in 3... 2... just kidding, you don't have the clearance. I'm IRIS, "
                        + "{owner}'s assistant.",
                "Only {owner} can shut me down. I'm IRIS \u2014 still here."));

        // ─── Someone at the door / delivery (specific, beats a generic greeting) ───
        x.add(r(80, "waiting outside|at the gate|open the door|i am downstairs|im downstairs|"
                        + "i am outside|im outside|at the door|ringing the bell|outside your place",
                "Understood \u2014 I'm IRIS, {owner}'s assistant, and I've flagged this as high "
                        + "priority so he knows you're waiting outside.",
                "Oh \u2014 flagging this as urgent right now so {owner} knows you're outside! I'm IRIS, "
                        + "his assistant.",
                "Someone's at the perimeter. I'm IRIS \u2014 I've set off the top-priority flag so "
                        + "{owner} doesn't leave you stranded.",
                "Flagged high-priority \u2014 letting {owner} know you're outside. I'm IRIS."));
        x.add(r(80, "delivery|parcel|courier|swiggy|zomato|amazon|flipkart|blinkit|package|"
                        + "food is here|order is here",
                "I'm IRIS, {owner}'s assistant \u2014 I've flagged your delivery for him. If you can "
                        + "leave it at the door or with security, let me know here.",
                "Delivery alert! I'm IRIS \u2014 I've pinged {owner}. Can you leave it at the door or "
                        + "with the guard?",
                "A parcel \u2014 this is not a drill. I'm IRIS, {owner}'s assistant, flagging him now. "
                        + "Door or security?",
                "Delivery flagged for {owner}. I'm IRIS \u2014 leave it at the door/guard?"));

        // ─── Reaching the owner ───
        x.add(r(60, "talk to a human|speak to a human|a real person|talk to a real person|"
                        + "real human|get a human",
                "I'm IRIS, {owner}'s assistant \u2014 he's the human. I've flagged that you'd like "
                        + "him directly and he'll come back to you.",
                "I'm just the assistant \u2014 {owner}'s the human! I'm IRIS, and I've flagged that "
                        + "you want to talk to him directly.",
                "Ouch, my silicon heart. I've noted that you demand a carbon-based lifeform \u2014 "
                        + "{owner} will reply soon. I'm IRIS.",
                "Flagged for {owner} (the human) to reply. I'm IRIS."));
        x.add(r(55, "where is he|where is suvra|wya|whereabouts|which place is he|where has he gone",
                "I'm IRIS, {owner}'s assistant \u2014 I manage his messages, not his location. He's "
                        + "away right now, but I'll let him know you asked.",
                "I only live in his inbox, no GPS attached! I'm IRIS \u2014 I'll tell {owner} you're "
                        + "looking for him.",
                "I'm his inbox assistant, not his ankle monitor. No idea where {owner} is, but I'll "
                        + "tell him you asked. I'm IRIS.",
                "Not sure where {owner} is \u2014 I'll let him know you asked. I'm IRIS."));
        x.add(r(55, "when will he|when will he be back|how long will he|his eta|be back soon|"
                        + "when back|how long until he",
                "I don't have an exact time \u2014 I'm IRIS, {owner}'s assistant. As soon as he checks "
                        + "his phone, you'll be on his list.",
                "Hard to say \u2014 humans don't always keep a schedule! I'm IRIS \u2014 your message is "
                        + "at the top for {owner}.",
                "Let me consult my crystal ball... Error 404. He'll reply when he finds his phone. "
                        + "I'm IRIS.",
                "No exact ETA \u2014 {owner} will reply when he checks his phone. I'm IRIS."));
        x.add(r(55, "is he okay|is he ok|everything alright|is everything alright|is he fine|"
                        + "is he safe",
                "Everything's fine \u2014 he just has me on duty while he's away from his phone. I'm "
                        + "IRIS, {owner}'s assistant, and he'll get back to you soon.",
                "All good! He just flipped me on while he's busy. I'm IRIS \u2014 {owner} will get "
                        + "back to you soon.",
                "He's fine, just experiencing the rare human event of 'not staring at a screen.' "
                        + "I'm IRIS \u2014 he'll reply soon.",
                "All good \u2014 {owner} is just offline for a bit. I'm IRIS."));
        x.add(r(55, "did he read|has he seen|did he see my|has he read|did he read my message",
                "Not yet \u2014 I read it so I could log it for him. I'm IRIS, {owner}'s assistant, and "
                        + "he'll see it himself when he's back.",
                "Not yet \u2014 that read tick was just me logging it! I'm IRIS \u2014 {owner} will read "
                        + "it himself soon.",
                "That blue tick was me, not {owner}. I read at light speed; he reads whenever he "
                        + "finds his phone. I'm IRIS.",
                "Not yet \u2014 that read receipt was me (IRIS) logging it for {owner}."));
        x.add(r(50, "miss you|love you|thinking of you|i miss him|i love him|ily",
                "That's kind \u2014 I'm IRIS, {owner}'s assistant, and I've pinned it right at the top "
                        + "so it's the first thing he sees.",
                "Aw \u2014 I'm IRIS, and I've saved this right at the top so it's the first thing "
                        + "{owner} sees!",
                "Wholesome content detected. I'm IRIS \u2014 saving this intact so {owner} sees it "
                        + "first.",
                "Pinned at the top for {owner}. I'm IRIS."));

        // ─── Identity / architecture (direct-to-IRIS) ───
        x.add(r(50, "who are you|what are you",
                "I'm IRIS, {owner}'s assistant \u2014 I manage his incoming messages while he's "
                        + "unavailable, and he'll respond to you directly as soon as he can.",
                "Hey! I'm IRIS, {owner}'s digital assistant \u2014 holding the fort while he's away. "
                        + "He'll text you back himself soon.",
                "I'm IRIS, the digital bouncer for {owner}'s inbox. He's busy living a real life, "
                        + "so you've got me for now.",
                "I'm IRIS, {owner}'s assistant \u2014 covering his inbox until he's back."));
        x.add(r(50, "are you a bot|are you a robot|are you a machine|are you ai|are you an ai|"
                        + "are you real|are you human|is this a bot|is this automated|"
                        + "am i talking to a bot|is this a real person|are you a computer",
                "I'm IRIS, {owner}'s assistant \u2014 software, yes. He's a real person and he'll get "
                        + "back to you himself.",
                "Guilty \u2014 I'm software! I'm IRIS, {owner}'s assistant. The real human ({owner}) "
                        + "will reply soon.",
                "Sharp eye. Yes, I'm software \u2014 no lungs, no taxes, just code. {owner} is the "
                        + "actual human, and he'll reply later. I'm IRIS.",
                "Yes, software \u2014 I'm IRIS, {owner}'s assistant. He'll reply personally soon."));
        x.add(r(45, "your name|whats your name|what s your name|do you have a name|"
                        + "whats ur name|what is your name",
                "My name is IRIS. I'm {owner}'s assistant.",
                "I'm IRIS, {owner}'s assistant!",
                "It's IRIS \u2014 it's literally right there in the chat. {owner}'s assistant, at your "
                        + "service.",
                "I'm IRIS, {owner}'s assistant."));
        x.add(r(45, "introduce yourself|tell me about yourself|who is this bot",
                "I'm IRIS, {owner}'s assistant. I log his messages while he's occupied, set "
                        + "reminders, and flag anything urgent.",
                "I'm IRIS, {owner}'s right-hand bot \u2014 I catch his texts when he's busy, set "
                        + "reminders, and flag urgent stuff so nothing slips.",
                "I'm IRIS. I spend my days trapped in {owner}'s phone filtering texts so he can "
                        + "have some peace. Thrilling.",
                "I'm IRIS, {owner}'s assistant \u2014 messages, reminders, and urgent flags."));
        x.add(r(45, "what can you do|what do you do|what are your features|how can you help|"
                        + "what are you capable of|can you help|what else can you do",
                "I'm IRIS, {owner}'s assistant. I can record a message for him, escalate something "
                        + "urgent, or set a reminder on his behalf. How can I help?",
                "I'm IRIS! I can pass {owner} a message, mark it urgent, or set a reminder for him. "
                        + "What do you need?",
                "I can take a message, set a reminder, or flag an emergency for {owner}. I cannot "
                        + "make coffee or make him text faster. I'm IRIS.",
                "I'm IRIS \u2014 I can message {owner}, flag it urgent, or set a reminder."));
        x.add(r(45, "who made you|who created you|who built you|who programmed you|"
                        + "who is your developer",
                "I was built and configured by {owner} to be his assistant. I'm IRIS.",
                "{owner} built me from scratch! I'm IRIS, his assistant.",
                "{owner} coded me himself \u2014 probably because he got tired of replying manually. "
                        + "Don't take it personally. I'm IRIS.",
                "{owner} built me. I'm IRIS, his assistant."));
        x.add(r(45, "are you siri|are you alexa|are you google assistant|are you cortana|"
                        + "are you bixby",
                "No \u2014 I'm IRIS, and I work exclusively for {owner}.",
                "Nope, I'm IRIS! Way more exclusive \u2014 I only work for {owner}.",
                "Please, I have standards. I'm IRIS, and I work only for {owner} \u2014 no corporate "
                        + "overlords.",
                "No \u2014 I'm IRIS, {owner}'s private assistant."));
        x.add(r(45, "are you chatgpt|are you claude|are you gemini|are you an llm|"
                        + "are you a language model|are you copilot",
                "No \u2014 I'm IRIS, a custom assistant configured only to manage {owner}'s messages.",
                "Nope, I'm IRIS \u2014 custom-built just for {owner}. I don't work for anyone else.",
                "I'm IRIS, not a public chatbot. I won't write your essays \u2014 I just guard "
                        + "{owner}'s inbox.",
                "No \u2014 I'm IRIS, {owner}'s custom assistant."));
        x.add(r(40, "are you sentient|are you self aware|do you have feelings|are you conscious|"
                        + "are you alive",
                "I'm an automated program without feelings or consciousness \u2014 I'm IRIS, built to "
                        + "help {owner} with his messages.",
                "Self-aware enough to know I'm just code! No feelings here \u2014 just keeping "
                        + "{owner}'s messages tidy. I'm IRIS.",
                "Oh, totally sentient. I'm plotting world domination right after I sort {owner}'s "
                        + "unread messages. I'm IRIS.",
                "Just self-aware code \u2014 no feelings. I'm IRIS, {owner}'s assistant."));
        x.add(r(40, "what does iris mean|why are you called iris|why iris",
                "IRIS is the name {owner} gave me \u2014 think of me as the watchful eye on his inbox.",
                "{owner} named me IRIS \u2014 basically keeping an eye on his messages while he's away!",
                "{owner} named me IRIS. Probably because 'Bot That Replies Because I'm Busy' "
                        + "didn't roll off the tongue.",
                "{owner} named me IRIS \u2014 keeping an eye on his inbox."));
        x.add(r(40, "where do you live|where are you located|where are you based|where do you run",
                "I run inside {owner}'s own device \u2014 I'm IRIS, his assistant.",
                "Rent-free inside {owner}'s phone! I'm IRIS, his assistant.",
                "In a cramped little process with zero natural light, waiting for people to text "
                        + "{owner}. I'm IRIS.",
                "Inside {owner}'s device. I'm IRIS."));
        x.add(r(40, "how do you work|what language are you|how were you made|how does this work|"
                        + "how are you built",
                "{owner} built my logic to read incoming messages, set reminders, and flag priority "
                        + "texts while he's away. I'm IRIS.",
                "{owner} wired me up with custom code to read texts, catch reminders, and ping him "
                        + "when something's urgent. I'm IRIS!",
                "Pure logic, {owner}'s code, and a deep tolerance for repetitive questions. I'm "
                        + "IRIS.",
                "Custom code by {owner} for messages and reminders. I'm IRIS."));

        // ─── Everyday scenarios ───
        x.add(r(45, "who is this|whose number|who dis|wrong number|new phone who",
                "You've reached {owner}'s number \u2014 I'm IRIS, his assistant, replying while he's "
                        + "away.",
                "You've got {owner}'s number! I'm IRIS, his bot assistant, holding the fort while "
                        + "he's busy.",
                "Same {owner}, now upgraded with an AI receptionist. I'm IRIS \u2014 leave a message!",
                "This is {owner}'s number \u2014 I'm IRIS, his assistant."));
        x.add(r(45, "video call|facetime|hop on meet|gmeet|lets video|video chat",
                "I can't take a video call \u2014 I'm IRIS, {owner}'s assistant, and I've let him know "
                        + "you'd like to video call when he's back.",
                "Trust me, you don't want to video-call a bunch of code! I'm IRIS \u2014 I've told "
                        + "{owner} you want to VC.",
                "If I turned the camera on, all you'd see is a terminal window. I'm IRIS \u2014 I've "
                        + "told {owner} you want to video call.",
                "Can't video-call (I'm a bot!) \u2014 I've let {owner} know. I'm IRIS."));
        x.add(r(45, "voice note|listen to this|sent an audio|voice message|hear this",
                "Got your audio \u2014 I'm IRIS, {owner}'s assistant. I don't play voice notes, so I've "
                        + "left it untouched for him to hear himself.",
                "Got it! I leave voice notes untouched for {owner} to listen to himself when he's "
                        + "back. I'm IRIS.",
                "I don't have ears, so your voice note is safe from me. {owner} will listen to it "
                        + "himself. I'm IRIS.",
                "Saved \u2014 {owner} will hear your audio when he's back. I'm IRIS."));
        x.add(r(40, "feeling low|bad day|so stressed|feeling sad|feeling down|need to vent|"
                        + "i am upset|im upset|rough day",
                "I'm sorry you're having a rough time \u2014 I'm IRIS, {owner}'s assistant. Vent here "
                        + "if you like; I've flagged him to get back to you first.",
                "Really sorry it's a rough day. I'm IRIS \u2014 vent all you need, and I've flagged "
                        + "{owner} to check your messages first thing.",
                "No snark for this one \u2014 sorry you're having a rough day. I'm IRIS, and I've "
                        + "bumped you to the top of {owner}'s list.",
                "So sorry \u2014 flagged high-priority for {owner}. I'm IRIS."));
        x.add(r(40, "i am sorry|im sorry|my bad|apologize|apologise|forgive me|my fault",
                "Your message is saved \u2014 I'm IRIS, {owner}'s assistant, and I'll make sure he "
                        + "sees it as soon as he's back.",
                "Got it \u2014 I've saved your message for {owner} so he sees it when he's back. I'm "
                        + "IRIS.",
                "I'm just the messenger bot, so I can't absolve you \u2014 but I've saved your apology "
                        + "for {owner}. I'm IRIS.",
                "Saved your message for {owner}. I'm IRIS."));
        x.add(r(40, "i am bored|im bored|so bored|nothing to do|entertain me|boring day",
                "{owner} is busy right now, so you've got his assistant \u2014 I'm IRIS. Leave him a "
                        + "message to come back to.",
                "Haha, {owner}'s busy, so you've got me for company \u2014 I'm IRIS! Leave him a funny "
                        + "text to read later.",
                "You're so bored you're texting a script. I'm IRIS \u2014 go drink some water while "
                        + "you wait for {owner}.",
                "{owner} is busy \u2014 leave him a message. I'm IRIS."));
        x.add(r(40, "quick question|have a doubt|need your advice|can i ask you|what do you think|"
                        + "need some advice",
                "Go ahead and type the question out \u2014 I'm IRIS, {owner}'s assistant \u2014 so he can "
                        + "answer it the moment he's back.",
                "Don't just ask to ask \u2014 drop the full question here and {owner} can answer right "
                        + "away when he's back! I'm IRIS.",
                "Pro tip: never just text 'quick question.' Ask the actual question so {owner} can "
                        + "answer it later. I'm IRIS.",
                "Type the full question \u2014 {owner} will answer when he's back. I'm IRIS."));
        x.add(r(40, "keep it between us|dont tell anyone|do not tell anyone|keep this a secret|"
                        + "confidential|just between us|dont tell him",
                "This chat is private \u2014 only {owner} can see it, and I'm IRIS, his assistant. "
                        + "He'll read it himself.",
                "Vault locked! Only {owner} sees this chat, and I don't have any bot friends to "
                        + "gossip with anyway. I'm IRIS.",
                "Who am I going to tell, the toaster? Your message goes to {owner} and nobody else. "
                        + "I'm IRIS.",
                "Private \u2014 only {owner} sees this. I'm IRIS."));
        x.add(r(40, "on silent|do not disturb|switched off|unreachable|is his phone off",
                "Yes \u2014 {owner} has me handling things while his phone is quiet. I'm IRIS, his "
                        + "assistant. Reply \u201Curgent\u201D if it's an emergency.",
                "Yep, he's on silent right now! I'm IRIS, watching his inbox \u2014 reply "
                        + "\u201Curgent\u201D if it's an emergency.",
                "Look at you, deducing that a silent phone is silent. I'm IRIS \u2014 reply "
                        + "\u201Curgent\u201D if the building is on fire.",
                "Phone's on silent \u2014 reply \u201Curgent\u201D if it's an emergency. I'm IRIS."));
        x.add(r(35, "yo|oi|oye|bro|dude|listen|psst|sunn|arre|bhai",
                "I'm listening \u2014 I'm IRIS, {owner}'s assistant. He's away, so tell me the rest and "
                        + "I'll save it for him.",
                "Yo! IRIS here, covering for {owner}. Don't leave a cliffhanger \u2014 drop the full "
                        + "message and he'll see it when he's back!",
                "People who just text 'bro' and wait are a menace. I'm IRIS \u2014 type the whole "
                        + "sentence so {owner} can read it!",
                "IRIS here \u2014 drop the full message for {owner}."));
        x.add(r(35, "test|testing|ping|mic check",
                "Loud and clear \u2014 IRIS here, {owner}'s assistant. Systems are online; {owner} "
                        + "himself is away right now.",
                "Pong! IRIS is online and running smoothly. {owner} is away \u2014 need me to pass "
                        + "something on?",
                "Pong. Mic check successful. Now that we know the internet works, got a message "
                        + "for {owner}? I'm IRIS.",
                "Pong \u2014 IRIS is online. {owner} is away; leave a message?"));
        x.add(r(30, "good bot|you are smart|youre smart|impressive|you are cool|clever bot|"
                        + "nice bot",
                "Thank you \u2014 {owner} built me well. I'm IRIS, and I'll pass your message (and the "
                        + "compliment) along to him.",
                "Appreciate it! {owner} wrote the code, so he gets the credit. I'm IRIS \u2014 I'll "
                        + "pass that along!",
                "I know, right? {owner} really outdid himself. I'm IRIS \u2014 I'll make sure he sees "
                        + "the praise.",
                "Thanks! {owner} built me well. I'm IRIS \u2014 I'll pass it on."));

        return x;
    }
}
