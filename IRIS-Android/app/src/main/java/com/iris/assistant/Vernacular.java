package com.iris.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Hinglish and Odia replies, so IRIS recognises the owner's real traffic instead of going silent.
 * Pure and offline-testable.
 *
 * SAFETY FIRST. Transliterated vernacular is impossible to parse, so the one hard rule is: match
 * WHOLE PHRASES only, never a bare ambiguous root. "bhala laguni" (= NOT feeling good) must stay
 * silent, so there is no bare "bhala" key \u2014 only "sab bhala" / "bhala ta". The real-Odia
 * must-not-match corpus from MessageCategoryTest is re-asserted in VernacularTest; nothing here may
 * claim any of it. Matching is substring-of-the-normalised-text on padded phrases, exactly like the
 * SmartReplies exact pass \u2014 no loose/synonym/fuzzy matching, which would be far too dangerous
 * across a language we cannot parse.
 *
 * Like SmartReplies this is an ADDITIVE layer the responder consults only for generic buckets, so
 * a message already handled (an English greeting, a festival wish, the urgent path) is untouched,
 * and festival words already handled in English (diwali/holi/eid) are deliberately NOT duplicated
 * here.
 */
public final class Vernacular {

    public static final class Rule {
        final int priority;
        final String[] any;
        final String[] byTone; // [PROFESSIONAL, CASUAL, SARCASTIC, BRIEF]

        Rule(int priority, String[] any, String[] byTone) {
            this.priority = priority;
            this.any = any;
            this.byTone = byTone;
        }
    }

    private Vernacular() { }

    /** Best matching vernacular reply for this message and tone, or empty. */
    public static String match(String body, ReplyTone.Tone tone) {
        if (body == null) return "";
        String norm = squeeze(MessageCategory.normalise(body));
        if (norm.isEmpty()) return "";
        String flat = " " + norm + " ";
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

    /** Collapse a run of 3+ identical characters to one, so "achhhu"/"sooo" normalise. */
    static String squeeze(String s) {
        if (s == null) return "";
        return s.replaceAll("(.)\\1{2,}", "$1");
    }

    private static String pick(String[] byTone, ReplyTone.Tone tone) {
        int idx = tone == null ? 0 : tone.ordinal();
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
        String O = ReplyTemplates.OWNER;

        // ─── Come outside / downstairs (highest: someone is physically waiting) ───
        x.add(r(80, "bahar aa|niche aa|baharaku aasa|talaku aasa|gate pakhare|tala ku aa|"
                        + "bahara ku aasa",
                "I've flagged this high-priority \u2014 I'm IRIS, " + O + "'s assistant, and I've let "
                        + "him know you're waiting outside.",
                "Oh, you're outside?! Flagging this urgent so " + O + " comes down \u2014 IRIS here!",
                "Sounding the perimeter alarm. I'm IRIS \u2014 I've told " + O + " you're waiting "
                        + "outside so he doesn't leave you there.",
                "Urgent \u2014 told " + O + " you're outside. IRIS."));

        // ─── Greetings / how-are-you ───
        x.add(r(45, "kemiti achu|kemiti achanti|khabar kana|sab bhala|bhala ta",
                "All good here \u2014 I'm IRIS, " + O + "'s assistant. He's away from his phone, but "
                        + "I've saved your message for him.",
                "Sab bhala! IRIS here, " + O + "'s assistant \u2014 he's busy, but he'll text you "
                        + "back soon.",
                "My code runs at 100%, so bhala. " + O + " will tell you how he is when he's back. "
                        + "IRIS.",
                "All good \u2014 IRIS here, saved your text for " + O + "."));
        x.add(r(45, "kaisa hai|kaisi ho|kya haal|sab badhiya|aur bata|kya haal chaal|kaise ho",
                "Everything's great \u2014 I'm IRIS, " + O + "'s assistant. He's offline, so tell me "
                        + "what I can pass on!",
                "Sab badhiya! IRIS here covering for " + O + " \u2014 what can I pass along?",
                "Living the dream inside a server. " + O + " is away \u2014 spill the news for him. "
                        + "IRIS.",
                "Sab badhiya! IRIS here \u2014 leave a message for " + O + "?"));
        x.add(r(45, "kya chal raha|kana chalichi|kn chalichi|kya scene hai|kya plan hai",
                "Right now just me guarding his inbox \u2014 I'm IRIS, " + O + "'s assistant. Drop "
                        + "your plan and he'll reply when he's back!",
                "Just me (IRIS) watching the inbox! Drop the scene/plan here for " + O + ".",
                "The only scene is you texting a script. Drop the plan and " + O + " will reply. "
                        + "IRIS.",
                "IRIS on duty \u2014 drop the plan for " + O + "."));
        x.add(r(45, "pranam|namaskar|jay jagannath|jai jagannath|ram ram|namaste",
                "Pranam \u2014 I'm IRIS, " + O + "'s assistant. I've saved your message with priority "
                        + "for when he checks his phone.",
                "Jai Jagannath / Pranam! IRIS here \u2014 " + O + "'s away, but I've pinned your "
                        + "message for him!",
                "Cultured greetings detected. Pranam \u2014 IRIS here, I've saved your message at the "
                        + "top for " + O + ".",
                "Pranam! IRIS here \u2014 saved your message for " + O + "."));
        x.add(r(40, "bhai sun|arre bhai|oye bhai|bandhu|sangha|re bhai|suna bhai",
                "I'm listening \u2014 I'm IRIS, " + O + "'s assistant. He's away, so type the rest and "
                        + "I'll save it for him.",
                "Haan bhai, IRIS here! " + O + "'s off his screen \u2014 drop the full message and "
                        + "I'll make sure he sees it!",
                "Texting 'bhai' and waiting is a crime against efficiency. Type the whole thing for "
                        + O + "! IRIS.",
                "IRIS here \u2014 drop the full message for " + O + "."));
        x.add(r(45, "kiye|kiye kahucha|eta kiye|kaun bol raha|kaun hai be|tu kaun hai|kie",
                "I'm IRIS, " + O + "'s assistant \u2014 software he built to look after his messages. "
                        + "He'll reply himself soon.",
                "Main IRIS hoon \u2014 " + O + "'s digital assistant! Catching his texts while he's "
                        + "busy. He'll reply soon!",
                "Relax, nobody stole his phone. I'm IRIS, " + O + "'s assistant bot \u2014 he has a "
                        + "life offline.",
                "I'm IRIS, " + O + "'s assistant \u2014 covering his inbox."));
        x.add(r(45, "achu ki|achha ki|hai kya|idhar hai|online achu|achanti ki",
                "I'm here \u2014 IRIS, " + O + "'s assistant. He's offline right now, but tell me what "
                        + "you need him to know.",
                "" + O + " isn't here, but I am! IRIS on duty \u2014 what should I pass on?",
                "I am always here because I'm code. " + O + " is away \u2014 leave a message. IRIS.",
                "IRIS is here! " + O + " is away \u2014 leave a message?"));
        x.add(r(40, "hau|acha thik achi|thik achi|theek hai|sahi hai|haan bhai|chalega|thik achhi",
                "Noted \u2014 I'm IRIS, " + O + "'s assistant. Everything's saved for him.",
                "Sorted! Everything's saved for " + O + ". IRIS.",
                "Got it \u2014 keeping " + O + " posted. IRIS.",
                "Noted \u2014 saved for " + O + "! IRIS."));
        x.add(r(42, "dhanyabad|bahut badhiya|mast bot|badiya hai|shukriya|sahi bot|dhanyavad",
                "Dhanyabad \u2014 I'm IRIS, " + O + "'s assistant! He built me himself; I'll pass your "
                        + "message along.",
                "Dhanyabad! " + O + " coded me himself \u2014 I'll pass the credit and your text to "
                        + "him! IRIS.",
                "Finally, someone who appreciates good engineering. Dhanyabad \u2014 I'll tell " + O
                        + " you approve. IRIS.",
                "Dhanyabad! Passed on to " + O + ". IRIS."));
        x.add(r(40, "pare katha heba|baad me baat|chalta hu|rakhta hu|vida|pare kahiba",
                "I'm IRIS, " + O + "'s assistant \u2014 everything's logged and he'll get back to you "
                        + "when he's free!",
                "Chalo, catch you later! I've saved everything so " + O + " can talk when he's "
                        + "back. IRIS.",
                "Pare katha heba indeed \u2014 mostly because you're talking to a script. Saved for "
                        + O + "! IRIS.",
                "Saved for " + O + " \u2014 talk soon! IRIS."));

        // ─── Where are you / what doing / busy / home / office / ETA / reached ───
        x.add(r(50, "pagal h kya|pagal ho kya|pagal hai kya|tu pagal hai|tu pagal hai kya|"
                        + "pagal ho|pagal kya|dimag kharab|dimaag kharab|sanki ho kya|"
                        + "are you mad|are you crazy|r u mad",
                "Ha! No \u2014 just a program doing its job. I'm IRIS, " + O + "'s assistant. He's "
                        + "away, so I'm minding his messages.",
                "Haha pagal toh nahi, bas IRIS hoon \u2014 " + O + "'s assistant! He's away, your "
                        + "message is saved.",
                "Pagal? I'm a script \u2014 I don't have the range for that. I'm IRIS, " + O + "'s "
                        + "assistant. Message saved.",
                "Haha \u2014 I'm IRIS, " + O + "'s assistant. Saved for him."));
        x.add(r(45, "kaha hai|kahan ho|kidhar hai|kaha par hai|kidhar gayab|kahan par",
                "I don't track his location \u2014 I'm IRIS, " + O + "'s assistant. He's away, so tell "
                        + "me what to pass on!",
                "No GPS on him! IRIS here, " + O + "'s assistant \u2014 I'll tell him you're looking "
                        + "for him.",
                "He didn't file a movement report before putting his phone down. Leave a message! "
                        + "IRIS.",
                "Not sure where " + O + " is \u2014 just watching his inbox. IRIS."));
        x.add(r(45, "kouthi achu|kauthi achu|kouthi re|kou jagare|kouthi gala|kouthi achanti|"
                        + "kouthi achha",
                "I only watch his inbox, not where he goes \u2014 I'm IRIS, " + O + "'s assistant. "
                        + "I've logged that you're looking for him!",
                "Mu khali inbox dekhe, GPS nahi! IRIS here \u2014 I'll tell " + O + " you asked where "
                        + "he is!",
                "I live in a server, so I'm right here. As for kouthi " + O + " is, he'll tell you "
                        + "when he checks his phone. IRIS.",
                "Just watching his inbox \u2014 I'll tell " + O + " you asked! IRIS."));
        x.add(r(45, "kya kar raha|kya kr rha|kya ho raha hai|kya kar rahe ho",
                "" + O + " is busy offline \u2014 I'm IRIS, his assistant, looking after his messages "
                        + "until he's back.",
                "" + O + " is busy, so he put me (IRIS) on inbox duty! What can I pass on?",
                "He's doing human things offline while I do the real work on his texts. What's the "
                        + "message? IRIS.",
                "" + O + " is busy offline \u2014 IRIS here taking messages!"));
        x.add(r(45, "kana karuchu|kn karuchu|kana karucha|kn krchu|kana houchi|kana karuchha",
                "He's busy away from his phone \u2014 I'm IRIS, " + O + "'s assistant, catching his "
                        + "messages so nothing's missed.",
                "" + O + "'s busy offline, so I'm handling the texts! Tell me what you need. IRIS.",
                "Right now I'm parsing your text in 3 milliseconds. " + O + " is offline \u2014 tell "
                        + "me what to pass on. IRIS.",
                "" + O + " is away \u2014 IRIS here saving your messages!"));
        x.add(r(45, "busy achu ki|bzy hai kya|busy ho kya|kaam me hai|kama re achu|busy achha ki",
                "Yes, " + O + "'s tied up \u2014 that's why I'm here! I'm IRIS, his assistant. Drop "
                        + "your message and he'll reply when free.",
                "Yep, he's tied up \u2014 that's my cue! IRIS here, drop your text for " + O + "!",
                "An automated bot just answered you, so yes, he's busy. Leave a message! IRIS.",
                "Yes, " + O + " is busy \u2014 leave your message with IRIS!"));
        x.add(r(45, "ghare achu|room re achu|ghar pe hai|room pe hai|ghar kab aayega|"
                        + "ghara kebe asibu|gharu achu",
                "I can't tell if he's at his room or home \u2014 I'm IRIS, " + O + "'s assistant. I've "
                        + "logged your question for him!",
                "Not sure if he's in or out! I've saved your message so " + O + " can tell you when "
                        + "he checks in. IRIS.",
                "I don't have cameras on his room, I just read the inbox. He'll tell you when he's "
                        + "back. IRIS.",
                "Not sure if he's home \u2014 saved your text for " + O + "! IRIS."));
        x.add(r(45, "office re achu|office me hai|duty re|kaam khatam hua|kama sarila|log off|"
                        + "office achu",
                "He might still be wrapped up in work \u2014 I'm IRIS, " + O + "'s assistant. He'll "
                        + "get back once he logs off!",
                "Probably still deep in work! I'll make sure " + O + " sees this the minute he logs "
                        + "off. IRIS.",
                "Kama never truly ends in IT, does it? He's busy \u2014 I've logged your message for "
                        + O + ". IRIS.",
                "Likely still working \u2014 saved for when " + O + " logs off! IRIS."));
        x.add(r(45, "kebe asibu|kab aayega|kab aa raha|asuchu ki|ketebele asibu|kebe asuchu",
                "I don't have his ETA \u2014 I'm IRIS, " + O + "'s assistant. If he's on the way, I've "
                        + "flagged this so he updates you!",
                "No clue on his ETA! I've bumped this up so " + O + " can tell you when he's coming. "
                        + "IRIS.",
                "Unless he travels through fibre-optic cables like me, I don't know his ETA. "
                        + "Flagged for " + O + "! IRIS.",
                "No ETA \u2014 flagged for " + O + " to update you! IRIS."));
        x.add(r(45, "pahanch gaya|pahanchilu ki|pohanchila|reached ki|thik se pahanch|"
                        + "pahanchi gala",
                "I'm IRIS, " + O + "'s assistant \u2014 if he's still commuting or just reached, I've "
                        + "pinned this so he tells you he's safe!",
                "IRIS here! Pinned this at the top so " + O + " can text you he's reached safely!",
                "I haven't moved from this server, and " + O + " hasn't checked his phone \u2014 "
                        + "pinned so he updates you! IRIS.",
                "Pinned at the top for " + O + " to confirm! IRIS."));

        // ─── Family / home calling (VIP, high priority) ───
        x.add(r(78, "maa kahuthile|bapa khojuchanti|gharu call|mummy ka call|ghar pe baat kar|"
                        + "maa jio|gharu phone",
                "Family comes first \u2014 I'm IRIS, " + O + "'s assistant. I've pinned this at the very "
                        + "top so he calls home as soon as he checks in.",
                "Family VIP alert! I've pinned this at the top so " + O + " calls home first thing. "
                        + "IRIS.",
                "Even I know not to mess with a call from home. Pinned at #1 for " + O + ". IRIS.",
                "Family priority \u2014 pinned at the top for " + O + "! IRIS."));

        // ─── Urgent (vernacular) ───
        x.add(r(75, "jaldi|turant|urgent achi|bahut zaroori|fatafat|tike suna|emergency achi|"
                        + "zaroori hai",
                "I've flagged this high-priority \u2014 I'm IRIS, " + O + "'s assistant \u2014 so he sees "
                        + "it the moment he checks in.",
                "On it! Flagged top-priority so " + O + " sees it the second he's back. IRIS.",
                "Jaldi mode engaged. Bumping you to the front of " + O + "'s queue. IRIS.",
                "Urgent flag triggered for " + O + "! IRIS."));

        // ─── Pick up the phone ───
        x.add(r(55, "phone utha|call utha|pick kar|utha na|phone kyu nahi utha|phone utha re|"
                        + "call uthaunu|phone laguni|call laguni|pick karunu",
                "" + O + " isn't near his phone \u2014 I'm IRIS, his assistant. I've flagged your call "
                        + "so he rings you back as soon as he sees it.",
                "He's away from his phone, else he'd pick up! IRIS here \u2014 flagged your call so "
                        + O + " rings you back asap.",
                "I don't have thumbs to swipe the green button, and " + O + " isn't near his phone. "
                        + "Flagged him to call back! IRIS.",
                "" + O + " is away from his phone \u2014 flagged your call! IRIS."));
        x.add(r(50, "call kar|phone kar|missed call de|call back karibu|phone karibu|ring kar|"
                        + "ring karibu",
                "Got it \u2014 I'm IRIS, " + O + "'s assistant. I've put \u201Ccall you back\u201D at the top "
                        + "of his list.",
                "Done! Put \u201Ccall you back\u201D at the top of " + O + "'s list. IRIS.",
                "Added \u201Cuse voice to speak with this human\u201D to " + O + "'s priority list. IRIS.",
                "Callback reminder pinned for " + O + "! IRIS."));

        // ─── Sleep ───
        x.add(r(45, "so gaya kya|so rha hai|sota hai|neend me|soye ho|so gaye kya",
                "He might be asleep \u2014 I'm IRIS, " + O + "'s assistant. I'm awake 24/7, so your "
                        + "message is safe until he's up.",
                "Good chance he's knocked out! IRIS here \u2014 I never sleep, your text is safe till "
                        + O + "'s up.",
                "He's probably so gaya, leaving me the night shift unpaid. Your message is logged. "
                        + "IRIS.",
                "Might be asleep \u2014 saved your message for " + O + "! IRIS."));
        x.add(r(45, "soichu ki|soipadilu|soi gala ki|nida houchi|soigalu|soipadichi",
                "Could be \u2014 I'm IRIS, " + O + "'s assistant. I don't need sleep, so I'm watching "
                        + "his inbox until he wakes.",
                "Bodhe soipadichi! IRIS here \u2014 wide awake and keeping your message safe till "
                        + O + "'s up!",
                "Humans need 8 hours of unconsciousness; scripts need zero. If he's soipadichi, "
                        + "I've got your text. IRIS.",
                "Might be asleep \u2014 IRIS here keeping your text safe! "));
        x.add(r(45, "uth gaya kya|uthilu ki|uthila na nahi|kab uthega|kebe uthibu|utha ki",
                "Not on his phone yet \u2014 I'm IRIS, " + O + "'s assistant. Yours will be one of the "
                        + "first messages he sees when he's up!",
                "Hasn't touched his phone yet! I've put your text at the top for when " + O
                        + " wakes. IRIS.",
                "His alarm and I are both waiting for signs of life. He'll see this when he's up. "
                        + "IRIS.",
                "Not up yet \u2014 queued at the top for " + O + "! IRIS."));
        x.add(r(45, "uth ja|utha re|jaldi uth|uthipada|utho bhai|uth jaa",
                "I can't throw water on him \u2014 I'm IRIS, " + O + "'s assistant \u2014 but I've flagged "
                        + "this urgent to ping his screen.",
                "Wish I could shake him awake! Flagged this urgent on " + O + "'s screen instead. "
                        + "IRIS.",
                "If I had robotic arms I'd flip his mattress. For now, an urgent flag will do. IRIS.",
                "Flagged urgent to help wake " + O + "! IRIS."));

        // ─── Food ───
        x.add(r(45, "khailu ki|khana khailu|bhata khailu|khaichu na nahi|kana khailu|tiffin kalu",
                "I only run on electricity \u2014 I'm IRIS, " + O + "'s assistant! I've pinned your "
                        + "message so he can tell you himself when he's back.",
                "Mu ta code, electricity re chale! Saved your text so " + O + " can tell you what he "
                        + "ate. IRIS.",
                "I had a bowl of CPU cycles, thanks. " + O + " will report on his bhata when he's "
                        + "back. IRIS.",
                "IRIS here \u2014 saved so " + O + " can reply when he's back!"));
        x.add(r(45, "khana khaya|lunch kiya|dinner hua|kuch khaya|nashta kiya|khana khaya kya",
                "He might be away eating \u2014 I'm IRIS, " + O + "'s assistant! I'll let him know you "
                        + "asked.",
                "Maybe that's why he's off his phone! IRIS here \u2014 I'll tell " + O + " you checked "
                        + "in on him.",
                "Hopefully he's eating; I've survived on Wi-Fi packets all day. Logged for " + O
                        + "! IRIS.",
                "Saved! " + O + " will let you know when he's back. IRIS."));

        // ─── Chai / meet / treat / seenzone / check-this ───
        x.add(r(48, "cha piba|chai peene|sutta|tea break|adda|coffee piba|cha pibaki",
                "Now that's an important alert \u2014 I'm IRIS, " + O + "'s assistant! I've bumped your "
                        + "chai/break invite up his list.",
                "Cha break alert! Bumped this up " + O + "'s list before the tea gets cold. IRIS.",
                "Mentioning cha is basically a high-priority override. Bumped to the top for " + O
                        + ". IRIS.",
                "Chai invite bumped to the top for " + O + "! IRIS."));
        x.add(r(48, "miliba ki|dekha heba|milte hai|plan bana|kebe miliba|bula buli|miliba",
                "Sounds fun \u2014 I'm IRIS, " + O + "'s assistant! Tell me when and where and I'll log "
                        + "it for him to confirm.",
                "Dekha heba! Drop the time and spot and " + O + " will lock it in when he's back. "
                        + "IRIS.",
                "Humans and their need to dekha heba in 3D space. Drop the time and place for " + O
                        + "! IRIS.",
                "Drop the time/place \u2014 " + O + " will confirm! IRIS."));
        x.add(r(48, "paisa patha|treat de|party de|party kebe|bhoj|chanda|party chai",
                "I'm software with zero bank balance \u2014 I'm IRIS, " + O + "'s assistant! I'll let him "
                        + "handle the party/money demands.",
                "Haha my balance is 0! I'll pass your party/treat demand to " + O + " though! IRIS.",
                "Asking an unpaid script for paisa is bold. Take it up with " + O + " when he "
                        + "replies. IRIS.",
                "Passing the party/money note to " + O + "! IRIS."));
        x.add(r(48, "reply de|reply kahinki daunu|seen karke|bhav kha raha|ignore karuchu|"
                        + "reply kar",
                "He isn't ignoring you \u2014 that \u201Cseen\u201D tick was me! I'm IRIS, " + O + "'s "
                        + "assistant, logging your text while he's offline.",
                "Arre no bhav! That blue tick was just me saving your message. " + O + " hasn't "
                        + "seen his phone yet! IRIS.",
                "Zero bhav here \u2014 that tick was me reading at light speed. " + O + " is still "
                        + "away. IRIS.",
                "Not ignoring \u2014 that read tick was me (IRIS) saving your text!"));
        x.add(r(48, "photo patha|dekha tike|link patha|status dekh|reel dekh|photo pathau",
                "Noted \u2014 I'm IRIS, " + O + "'s assistant. I've added a reminder for him to check "
                        + "this / send the photos when he's back.",
                "Pinned a reminder for " + O + " to check this / send the pics when he grabs his "
                        + "phone! IRIS.",
                "Added to " + O + "'s post-offline homework list. He'll check it when he's back. "
                        + "IRIS.",
                "Reminder set for " + O + " to check this! IRIS."));

        // ─── Bot banter / confusion / stop / scold / crazy / help ───
        x.add(r(45, "ye kya hai|eta kana|bot lagaya kya|auto reply hai kya|nua nataka|"
                        + "bot lagailu",
                "Haha yes \u2014 I'm IRIS, " + O + "'s custom-built assistant! I catch his messages when "
                        + "he's busy, and he'll reply to you himself soon.",
                "Haha surprise! I'm IRIS \u2014 " + O + " built me to watch his inbox. The real " + O
                        + " will text you back soon! ",
                "Welcome to the future. Yes, " + O + " built an AI receptionist so his inbox doesn't "
                        + "explode. Leave a message! IRIS.",
                "Yep, I'm IRIS \u2014 " + O + "'s assistant bot! He'll reply soon."));
        x.add(r(45, "bot band kar|chup kar|chup raho|band kar bhai|chup ruha|chup re",
                "Understood \u2014 I'm IRIS, " + O + "'s assistant, and I'll go quiet for this chat now. "
                        + "Your message is logged for him.",
                "Thik achi, going silent now! Saved for " + O + ", and I won't bug you till he's "
                        + "back. IRIS.",
                "Fine, chup mode activated. Enjoy the silence until " + O + " checks his phone. IRIS.",
                "Got it \u2014 muting now. Saved for " + O + ". IRIS."));
        x.add(r(42, "bada loka|bade log|bhuli galu|bhool gaya|bhuli gala",
                "He hasn't forgotten you \u2014 he just built me to help with his inbox! I'm IRIS, "
                        + O + "'s assistant, and I'll make sure he sees this.",
                "Haha no bada loka attitude \u2014 he's just busy! I've pinned your roast for " + O
                        + " to see. IRIS.",
                "Having a personal AI does make him look like a bada loka, I won't lie. I'll pass "
                        + "your roast along! IRIS.",
                "No attitude, just offline! Pinned this for " + O + ". IRIS."));
        x.add(r(42, "pagal hai kya|baya ki|pagla|dimag kharab|sanki|baya ta nuhan|pagala",
                "Maybe a little \u2014 he did spend his free time coding me! I'm IRIS, " + O + "'s "
                        + "assistant, and I'll pass your message along.",
                "Haha he did code a personal bot, so you might have a point! I'll tell " + O
                        + " you texted. IRIS.",
                "He's sane enough to automate his inbox while you argue with a script \u2014 so who's "
                        + "winning? IRIS.",
                "Haha \u2014 I'll let " + O + " answer that when he's back! IRIS."));
        x.add(r(48, "help darkar|kaam tha|tike kama thila|madad chahiye|help kar de|kama achi",
                "Tell me what you need \u2014 I'm IRIS, " + O + "'s assistant. Type the details and I'll "
                        + "flag it so he can jump in when he's back.",
                "Drop the details here! That way " + O + " knows exactly what help you need the "
                        + "second he checks his phone. IRIS.",
                "Explain the whole problem now instead of waiting \u2014 then " + O + " can actually "
                        + "solve it when he looks. IRIS.",
                "Drop the details \u2014 I'll flag it for " + O + "! IRIS."));

        // ─── Reminder / weather / festival (vernacular; English festivals already handled) ───
        x.add(r(50, "yaad dila dena|mane pakeidebu|mane pakeiba|bhulibuni|yaad rakhna|yaad dilana",
                "I never forget \u2014 I'm IRIS, " + O + "'s assistant! Tell me what and when (e.g. "
                        + "\u201Ctomorrow 5pm\u201D) and I'll set a reminder for him.",
                "Bot memory never fails! Tell me what and when and I'll make sure " + O + " doesn't "
                        + "forget. IRIS.",
                "Unlike " + O + ", my memory is backed by a hard drive. Tell me what and when. IRIS.",
                "Tell me what & when \u2014 I'll remind " + O + "! IRIS."));
        x.add(r(45, "barsa houchi|barish ho rahi|pani jami|toofan|garmi|barsa heuchi",
                "Thanks for the heads-up \u2014 I'm IRIS, " + O + "'s assistant! I've pinned your "
                        + "weather note so he sees it before heading out.",
                "Oh, barsa/barish alert! Pinned this so " + O + " sees it before stepping out. IRIS.",
                "Good thing I live in the cloud \u2014 the digital kind, not the rain kind. Pinned for "
                        + O + "! IRIS.",
                "Weather update pinned for " + O + "! IRIS."));
        x.add(r(45, "raja sankranti|nuakhai|subhechha|nabanna|jhulana|kumar purnima",
                "Thank you so much \u2014 I'm IRIS, " + O + "'s assistant! I've saved your festive "
                        + "wishes right at the top for him.",
                "Bahut bahut dhanyabad! IRIS here \u2014 I've pinned your festive wishes at the top for "
                        + O + "!",
                "I don't get festival holidays or sweets, sadly, but I've pinned your wishes at the "
                        + "top for " + O + "! IRIS.",
                "Thanks! Festive wishes pinned for " + O + "! IRIS."));

        // ─── Bengali (Kolkata) ───
        // Whole-phrase matched; distinct from the Odia must-not-match corpus.
        x.add(r(45, "kemon acho|kemon achho|kemon acha|bhalo acho to|bhalo acho|kemon aco|"
                        + "tumi kemon acho",
                "I run on electricity, so always 'bhalo'! I'm IRIS, " + O + "'s assistant. He's "
                        + "away \u2014 what can I pass on?",
                "Bhalo achi! IRIS here \u2014 " + O + " is away, so I'm minding his inbox. Ki bolbo "
                        + "take?",
                "A script is always bhalo \u2014 no bad days in here. I'm IRIS, " + O + "'s assistant.",
                "Bhalo achi! Saved for " + O + ". IRIS."));
        x.add(r(45, "ki korcho|ki korcho tumi|ki korchho|ki korish|ki kaj korcho",
                "" + O + " is busy offline \u2014 I'm IRIS, his assistant, watching his messages till "
                        + "he's back.",
                "" + O + " is busy, so he put me (IRIS) on inbox duty! Ki pass korbo take?",
                "I'm answering texts while " + O + " does human things. I'm IRIS, his assistant.",
                "On inbox duty for " + O + ". IRIS."));
        x.add(r(45, "kheyecho ki|khawa hoyeche|kheyecho|bhat kheyecho|khabar kheyecho|"
                        + "lunch korecho|dinner korecho",
                "I only eat electricity! I'm IRIS, " + O + "'s assistant \u2014 I've saved your "
                        + "question so he can tell you when he's back.",
                "Mu to current e chale! IRIS here \u2014 saved it so " + O + " can reply. Tumi kheyecho "
                        + "to?",
                "A bowl of CPU cycles, thanks. " + O + " will report his human food later. I'm "
                        + "IRIS.",
                "I run on power! Saved for " + O + ". IRIS."));
        x.add(r(45, "barite acho|bari te acho|basay acho|office e acho|office acho|kothay acho|"
                        + "tumi kothay",
                "I don't track where he is \u2014 I'm IRIS, " + O + "'s assistant, I just watch the "
                        + "inbox. I've logged that you asked!",
                "No GPS on him! IRIS here \u2014 I'll tell " + O + " you were looking for him.",
                "I live in a server, so I'm 'home' always. Where " + O + " is, he'll tell you. I'm "
                        + "IRIS.",
                "Just watching his inbox \u2014 told " + O + " you asked. IRIS."));
        x.add(r(45, "ki khobor|khobor ki|ki khobor tomar|sob bhalo to|sob thik thak",
                "All systems bhalo \u2014 I'm IRIS, " + O + "'s assistant! He's away, so what can I "
                        + "pass on?",
                "Sob bhalo! IRIS here \u2014 " + O + " is away, drop your message!",
                "Zero bugs, low CPU \u2014 khobor bhalo. I'm IRIS, " + O + "'s assistant.",
                "Sob bhalo! Message for " + O + "? IRIS."));
        x.add(r(45, "kobe asbe|kobe asbi|kokhon asbe|phirbe kobe|kobe firbe|kobe phirbe",
                "No exact time \u2014 I'm IRIS, " + O + "'s assistant. As soon as he's free, you're on "
                        + "his list!",
                "Hard to say when " + O + " is back \u2014 humans don't keep a timer! Saved for him. "
                        + "I'm IRIS.",
                "My crystal ball returned 404. He'll reply when he checks his phone. I'm IRIS, "
                        + O + "'s assistant.",
                "No exact ETA \u2014 saved for " + O + ". IRIS."));

        return x;
    }
}
