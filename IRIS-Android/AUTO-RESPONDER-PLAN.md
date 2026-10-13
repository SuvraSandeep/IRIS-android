# COMMS DECK: auto-responder plan

Status: **plan awaiting approval. Nothing implemented.**

Target: **15.0.0** / versionCode 370. Major bump — IRIS begins sending messages on Suvra's behalf,
which is a genuine change in what the app does, not a feature addition.

---

## 1. Decisions already made by the owner

| # | Decision |
| --- | --- |
| 1 | Rename Receipt Vault, and put the auto-responder in a tab **inside** it |
| 2 | **Dry run first** — armed but silent until proven |
| 3 | Every reply must make clear **IRIS is Suvra's personal intelligence**, replying instead of him |
| 4 | Default tone is **professional** |
| 5 | Cyberpunk UI, self-aware AI voice, first name or STG |
| 6 | A tab for choosing which contacts get replies |
| 7 | Apps with no reply capability are ignored |
| 8 | Must distinguish group vs personal, saved vs unsaved contact, and @mentions |
| 9 | Name **COMMS DECK** approved |
| 10 | Quiet hours default 23:00–07:00, **customisable** |
| 11 | Cooldown default 2 hours, **customisable** |
| 12 | **Everything** is customisable, including the reply text itself |
| 13 | `URGENT` does **not** pierce quiet hours. Instead the quiet-hours reply gains an urgent variant telling the sender to **call**, since a call will wake him and a message will not |
| 14 | Auto-disarm 12 hours by default, customisable, with "until I switch it off" available |
| 15 | Shortforms recognised: ty, thx, tq, gm, gn, hbd, hny, wyd, k |
| 16 | `ACKNOWLEDGEMENT` ("ok", "hmm", "theek") is **ignored in professional and casual, but answered in sarcastic** |
| 17 | Contacts carry a **relationship** — family, work, friend — with their own tone and reply variants |
| 18 | The responder has its **own app list**, separate from the archive's, default empty |
| 19 | SMS may be enabled, with the shortcode caveat below |

### Which apps the responder may speak through (decision 18)

The responder does **not** inherit the archive's ticked-app list. Those lists answer different
questions: the archive's is "what do I want a record of", the responder's is "what may IRIS send
messages through on my behalf".

Coupling them would be actively dangerous. Ticking Instagram to catch deleted DMs would silently
also authorise outgoing messages through Instagram, which is not what that tick meant. So the
responder keeps its own list, **default empty**, requiring explicit opt-in per app.

Only apps that have been observed offering a reply action can be added, and the Responder tab
shows which those are. An app with no reply action is skipped silently by the guards rather than
reported as broken, because there is nothing wrong: it simply cannot be answered.

### SMS, and the shortcode flaw it exposed (decision 19)

SMS works like any other app: if the messaging app attaches a reply action, the responder can use
it. Most default SMS apps including Google Messages do, which is why a smartwatch can reply to a
text. The PROBE diagnostic confirms it per-app on the owner's own device.

SMS is nonetheless the riskiest app to enable, and for reasons that do not apply to WhatsApp:

- It carries one-time passcodes, bank alerts and promotional spam.
- Sending may cost money, and replying to a premium or international number costs more.
- Senders are often **alphanumeric shortcodes** such as `VM-HDFCBK` or `AX-AMAZON`.

That last point exposed a real flaw in the saved-contact check. `ContactNumber.looksLikePhoneNumber`
returns false for `VM-HDFCBK` because it contains letters, and the naive conclusion from that is
"this is a name, so it must be a saved contact" -- which is exactly backwards. The
saved-contacts-only default, the very guard that should protect against replying to spam, would
have waved it through.

**Correction:** `savedContact` must mean "this sender was verified to exist in the contact store",
not "the sender did not look like a phone number". `ContactResolver` therefore needs a positive
`isSaved(name or number)` lookup, and anything it cannot confirm counts as unsaved. The direction
of the uncertainty matters: an unconfirmed sender being treated as unsaved costs a silence, while
the reverse sends a stranger a message.

### Pipeline order, revised by decision 16

Tone was originally resolved after deciding whether to answer. Decision 16 inverts that: whether a
category earns a reply now depends on the tone, because "ok" deserves silence when IRIS is being
professional and deserves a remark when it is being sarcastic.

```
1. resolve TONE      per-contact > relationship > name pattern > time window > global default
2. classify CATEGORY
3. decide SILENCE     answers(category, tone)   <- tone participates here
4. choose CONTENT     quiet hours > category > default
```

Tone and content are still independent of each other. Tone simply has to be known first.

Owner: **Suvra Sandeep Dwibedy**, STG (Suvra The Great). Replies refer to him as *Suvra*. UI copy
may use either.

### Voice rule for replies

IRIS speaks as a personal intelligence that works for Suvra and is quietly proud of it. It is
deferential without ever using the word "boss", and never pretends to be him. The recipient should
finish the message knowing three things: this is not Suvra, it is his intelligence, and their
message has been recorded.

---

## 2. Rename

"Receipt Vault" described a read-only archive. The screen will now also send, so the name is wrong.

Proposal: **COMMS DECK** — fits the existing Command Deck palette tokens (`deck_accent`,
`deck_card`), and covers both directions. Alternatives if preferred: SWITCHBOARD, SIGNAL DECK,
INTERCEPT.

Sub-tabs inside it:

```
COMMS DECK
  [ ARCHIVE ]   existing Vault: capture, search, images, swipe-delete
  [ RESPONDER ] auto-reply rules, tone, time windows, dry-run, arm switch
  [ CONTACTS ]  who gets replies and who never does
```

Sub-tabs are in-screen, not bottom navigation. The bottom bar already holds five tabs (Assistant,
Training, Activity, Memory, Settings) and a sixth truncates the labels — the same reason the Vault
became a section rather than a tab.

---

## 3. Architecture: two independent axes

Resolving "what to say" and "how to say it" separately avoids a rule for every combination.
Concretely, "Good morning" from *Rajesh Bhai Eric Kolkata* at 23:30 resolves as **quiet-hours
content** in **professional tone**, from two small tables rather than one large one.

```
CONTENT   quiet hours  >  message category  >  default  >  SILENCE
TONE      per-contact  >  name pattern  >  time window  >  global default (professional)
```

Silence is the terminal default. IRIS has no language model and cannot answer an arbitrary message,
so anything unmatched gets no reply at all rather than a generic one.

---

## 4. Everything is customisable

Decision 12 changes the architecture, not just the settings screen. Nothing here may be a
hardcoded constant consulted at the point of use: every value below is a **stored setting with a
shipped default**, and the pure classes take them as parameters rather than reading them.

| Customisable | Default |
| --- | --- |
| Quiet hours start / end | 23:00 / 07:00 |
| Work hours start / end | 09:00 / 18:00 |
| Extra named time windows | none |
| Cooldown per conversation | 2 hours |
| Daily cap per contact | 3 |
| Daily cap overall | 30 |
| Auto-disarm duration | 12 hours, customisable, or "until switched off" |
| Each category on / off | see §5 |
| **Every reply variant, editable per category per tone** | §11 |
| Global default tone | PROFESSIONAL |
| Name-pattern tone rules | one rule: "eric" → PROFESSIONAL |
| Mention keywords | suvra, stg |
| Scope | personal only |
| Saved / unsaved contacts | saved only |
| Per-contact tone and ALLOW/NEVER | inherit |

Two consequences worth stating before implementation:

**Reset to defaults is mandatory.** If every string is editable, the owner can edit his way into a
responder that says nothing useful. Reset must exist per category and globally, and the shipped
defaults must survive as read-only constants so reset has something to restore.

**Transparency cannot be enforced on custom text.** Decision 3 requires every reply to identify
IRIS. That is testable for the shipped defaults and a test will assert it. It cannot be enforced on
text the owner writes himself without refusing to save his edit, which would be a worse product.
Resolution: the editor shows a **non-blocking warning** when a custom variant omits both "IRIS" and
"Suvra", explaining that the recipient will believe Suvra wrote it. The owner may save anyway. The
honest position is that transparency is guaranteed by default and the owner can knowingly opt out
of it, rather than pretending it is enforced.

---

## 5. Message categories

Each is independently switchable, with its own reply variants.

| Category | Matches | Notes |
| --- | --- | --- |
| `GREETING` | hi, hello, hey, yo, namaste, hii, heyy | Repeated-letter tolerant |
| `TIME_GREETING` | good morning / afternoon / evening / night | Reply acknowledges the actual hour |
| `FESTIVAL` | happy diwali, eid mubarak, merry christmas, happy new year, happy holi, subho bijoya, happy durga puja | Matched by **phrase, never by date** — Diwali moves yearly and a date table would rot |
| `WEEKEND` | happy weekend, happy friday | Checked against the real day |
| `SHORT` | one or two words not matching anything else | The "hello"/"hi" case generalised |
| `QUESTION` | ends with `?` | Cannot be answered, only acknowledged |
| `URGENT` | urgent, asap, emergency, call me, important | Does **not** pierce quiet hours. See below |
| `MEDIA_ONLY` | 📷 Sent a photo, Sent a sticker | Optional, off by default |
| `THANKS` | thanks, thank you, tysm | |
| `CHECK_IN` | are you there, online?, you up | The probe caught "Aeee" and "Online asa" from Bidisha — this category exists because of real traffic |
| `MENTION` | Suvra, STG, @suvra in a group | See §6 |

### Urgent during quiet hours (decision 13)

`URGENT` does not override quiet hours, because "urgent" is a word spammers use freely and a 3am
message should not be able to talk IRIS into a different behaviour. Quiet hours still win.

What changes is the *text*. Quiet hours carry a dedicated urgent variant that redirects the sender
to the one channel that actually reaches a sleeping person:

> "Suvra is asleep and a message won't wake him. I'm IRIS, his personal intelligence — if this
> genuinely cannot wait until morning, call him. The phone will ring; this will not."

This is strictly better than piercing. A spammer typing "urgent" gets told to call and is
ignored; a real emergency gets told exactly how to get through. IRIS never has to judge which is
which, which is the part it would get wrong.

Content resolution is therefore: **quiet hours wins, and the urgent category selects which
quiet-hours variant is used.**

### Non-English safety

The probe returned Odia transliterated into Latin script: *"Mu mora faeda dekhibii"*, *"Bhala
laguni"*. Greeting matching must be **whole-word and anchored**, or substrings inside other
languages will false-positive. Every pattern will carry a regression check using the probe's real
strings as must-not-match cases.

---

## 5a. Tone

Four tones. `PROFESSIONAL` is the global default per decision 4.

- `PROFESSIONAL` — measured, complete sentences, no jokes
- `CASUAL` — relaxed, contractions, still clear
- `SARCASTIC` — dry. Opt-in per contact only; never reachable by default
- `BRIEF` — one short line, for people who dislike bots

Resolution order: explicit per-contact tone → name-pattern rule → active time window → global
default.

Name-pattern rules are a small ordered list. The owner's first rule: **name contains "eric" →
PROFESSIONAL**, because his Ericsson colleagues appear in contacts as "Rajesh Bhai Eric Kolkata",
"Debraj Naskar Eric Kol". Patterns are case-insensitive substring matches on the resolved contact
name, with the first match winning so order is meaningful and editable.

Because `SARCASTIC` can only be set per-contact, a work contact can never receive it by accident
even if the global default is later changed.

---

## 6. Targeting

### Group versus personal

Three modes: personal only (default), groups only, both.

Group detection, in confidence order:
1. `FLAG_GROUP_SUMMARY` — already refused by `replyAction`
2. `EXTRA_IS_GROUP_CONVERSATION`
3. More than one distinct sender in `EXTRA_MESSAGES`
4. Title carries a count decoration — `MessageRollup.groupFromTitle` already parses
   "Swiss Group (3 messages): Sender"

Any one signal marks it a group. Deliberately biased toward *over*-detecting groups: replying to a
group when the owner asked for personal only is embarrassing and public, while missing a group
reply is merely a silence.

### Mentions in a group

When group replies are enabled, an optional stricter mode: **reply only when Suvra is mentioned.**
Matches "suvra", "stg", "@suvra" as whole words, case-insensitive. Mentions outrank the normal
category rules, since being addressed directly is the one group case worth answering.

### Saved versus unsaved contacts

Determined with the existing `ContactNumber` / `ContactResolver`: a sender that still looks like a
phone number after resolution is not in contacts. Separate switches for each, both defaulting to
**saved only**. An unsaved number is as likely to be a delivery driver or a spammer as a person,
and auto-replying tells a stranger the phone is live and unattended.

### Contacts tab

- Search across installed-app contacts seen in the archive
- Three states per contact: **ALLOW**, **NEVER**, inherit default
- `NEVER` outranks everything, including mentions and urgent
- Default mode switch: reply to everyone except NEVER, or only to ALLOW
- Per-contact tone override set here
- Shows resolved names, so numbers appear as people

---

## 7. Time rules

- **Quiet hours** with their own content, e.g. 23:00–07:00. Must handle the **wrap across
  midnight**, which is the obvious bug in any naive from/to comparison.
- **Work hours** forcing professional tone
- Weekday / weekend distinction
- Any number of extra named windows

`{hours_until_morning}` must be computed across midnight: at 23:30 with a 07:00 end the answer is
7, not −16.

Clock safety: a timezone change, DST shift or manually moved clock must never produce a negative
duration or a nonsense window. Bad clock input resolves to "no window active" rather than
guessing, so a confused clock produces normal replies rather than wrong ones.

---

## 8. Safety and rate limiting

Non-negotiable, each tied to a specific failure:

| Guard | Failure it prevents |
| --- | --- |
| **One reply per burst** | Bidisha's notification held **7 messages**. Without this, 7 replies. |
| Per-conversation cooldown (default 2h) | Hammering someone who sends often |
| Daily cap per contact, and overall | A runaway rule |
| Loop detection — incoming text matches our own last sent reply | Two auto-responders talking forever |
| Never reply to a roll-up | "14 messages from 3 chats" is not a message |
| Never reply to a deletion placeholder | Answering "This message was deleted" |
| Never reply to a flagged row | Already deleted by the sender |
| Pause while the phone is unlocked and in use | Suvra is plainly available |
| Auto-disarm after N hours (default 12) | Leaving it armed for weeks by accident |
| Never reply to Suvra's own messages | Own account on another device |
| Authentication required to arm | `AGENTS.md` locks authenticated write controls |

### Apps without reply capability

`replyAction` returns null when an app offers no reply action, more than one text field, or is a
group summary. Those notifications are skipped silently. The Responder tab will additionally
**list which installed apps have ever offered a reply action**, so the owner can see that e.g.
Clash of Clans will never be answered, rather than wondering why.

---

## 9. Transparency (decision 3)

Every auto-reply identifies itself. Not a footnote — built into the sentence, because a trailing
marker is easy to miss on a watch or a lock screen.

No template may omit it. A test will assert that **every variant of every tone mentions both IRIS
and Suvra**, so a later edit cannot quietly produce something that reads as though Suvra wrote it.

---

## 10. Dry run (decision 2)

Three states: `OFF` → `DRY_RUN` → `LIVE`.

In `DRY_RUN` the full pipeline executes — targeting, category, tone, rate limits — and the chosen
reply is recorded with the reason it was chosen. **Nothing is sent.** The Responder tab shows the
last 50 decisions, including the silences and why.

Going `LIVE` requires authentication and an explicit confirmation naming what will happen.

### Explain view

Any archive row can be tapped to show: would IRIS have replied, which category matched, which tone
resolved and why, which guard blocked it. A rules engine is opaque otherwise, and the owner needs
to trust this before it speaks for him.

---

## 11. Sample replies

Shipped defaults, all editable. Each category carries several variants chosen at random so repeat
senders do not get identical text.

**Quiet hours — PROFESSIONAL**
- "Suvra is asleep. I'm IRIS, the personal intelligence he keeps for exactly this — your message is
  recorded and he'll see it in the morning."
- "It's {time} and Suvra is resting. This is IRIS, his personal intelligence. I've logged your
  message; he'll respond after {hours_until_morning} hours or so."

**Quiet hours — CASUAL**
- "Suvra's asleep. I'm IRIS, his personal intelligence — I mind the messages while he's out. He'll
  get this in the morning."

**Quiet hours — SARCASTIC**
- "Suvra is unconscious, so you've got IRIS instead — his personal intelligence, and the one of us
  that doesn't need sleep. He'll read it when he resurfaces."

**Greeting — PROFESSIONAL**
- "Hello. Suvra isn't available at the moment. I'm IRIS, his personal intelligence; I've noted your
  message and he'll reply when he's free."

**Time greeting — PROFESSIONAL**
- "Good {part_of_day} to you as well. Suvra is away from his phone — this is IRIS, his personal
  intelligence. Your message is with him."

**Festival — PROFESSIONAL**
- "Thank you, and the same to you. Suvra isn't at his phone right now; I'm IRIS, his personal
  intelligence, and I've passed your wishes on to him."

**Short / one word — PROFESSIONAL**
- "Hello. Suvra is unavailable. I'm IRIS, his personal intelligence — tell me a little more and
  he'll have the context when he reads it."

**Question — PROFESSIONAL**
- "That's one for Suvra himself. I'm IRIS, his personal intelligence; I've recorded the question and
  he'll answer properly when he's back."

**Urgent — PROFESSIONAL** *(normal hours)*
- "Understood as urgent. I'm IRIS, Suvra's personal intelligence — I've flagged this above
  everything else. If it cannot wait, call rather than message."

**Urgent during quiet hours — PROFESSIONAL** *(decision 13: redirect, do not pierce)*
- "Suvra is asleep and a message won't wake him. I'm IRIS, his personal intelligence — if this
  genuinely cannot wait until morning, call him. The phone will ring; this will not."
- "It's {time} and Suvra is asleep. I'm IRIS, his personal intelligence. I've marked this urgent,
  but if it's truly immediate, call — a call gets through and a message doesn't."

**Mention in group — PROFESSIONAL**
- "Suvra has been mentioned and isn't here to answer. This is IRIS, his personal intelligence; the
  mention is logged and he'll pick it up."

**Thanks — BRIEF**
- "Noted, and passed to Suvra. IRIS."

Placeholders: `{time}`, `{part_of_day}`, `{hours_until_morning}`, `{day}`, `{name}`.

---

## 12. Edge case catalogue

**Targeting** — group misdetected as personal; mention inside a quoted message; sender name empty;
sender is a number with no contact; same contact across two apps; work-profile duplicate; two
accounts in one app; contact renamed between capture and reply; contact in both ALLOW and a NEVER
pattern (NEVER wins).

**Capability** — no reply action; several text fields; group summary; PendingIntent already
cancelled; app uninstalled while armed; notification dismissed between decision and send.

**Content** — roll-up; deletion placeholder; empty text; emoji only; extremely long; our own reply
echoed back; Odia or other non-English text matching a greeting pattern by accident; a message that
is literally "hi" inside a longer sentence; message containing a reply marker we generated.

**Burst and rate** — seven messages in one notification; two notifications a second apart; cooldown
boundary exactly; cap reached mid-burst; cap resetting at midnight.

**Time** — quiet hours wrapping midnight; `{hours_until_morning}` across midnight; DST shift; clock
moved backwards; timezone change while armed; window where start equals end.

**State** — armed across a reboot (honour the stored disarm time, never silently re-arm); dry run
switched to live mid-burst; authentication cancelled while arming; archive wiped while armed;
notification access revoked while armed.

---

## 13. Must not break

- `activeForReply` keeps its contract: never send from cached history.
- `NotificationReplyActivity` behaviour unchanged; the verified send is extracted and shared, not
  rewritten.
- `NotificationStore` untouched.
- No message body reaches `LogStore` or `CommandHistory`. The ledger records that a reply was sent
  and to which package — never the text of the incoming message.
- Archive capture must not be delayed by responder work; the responder runs after capture and off
  the notification thread.
- Every send recorded in `ActionLedger`, honestly marked as submitted-not-verified.

---

## 14. Phases

- [ ] **1** Rename to COMMS DECK; sub-tab shell with ARCHIVE moved in unchanged.
- [ ] **2** Pure `MessageCategory` — classification with the probe's real non-English strings as
      must-not-match cases.
- [ ] **3** Pure `ReplyTone` + resolution, including the name-pattern list and the "eric" rule.
- [ ] **4** Pure `ResponderWindow` — quiet hours, midnight wrap, clock safety.
- [ ] **5** Pure `ResponderGuards` — burst, cooldown, caps, loop detection, blocked kinds.
- [ ] **6** Pure `ReplyTemplates` — variants, placeholders, and the test that every variant names
      both IRIS and Suvra.
- [ ] **7** `ResponderSettings` persistence; contact ALLOW/NEVER store.
- [ ] **8** Extract the verified send into shared `NotificationReply.send(...)`.
- [ ] **9** Wire the responder into the listener in `DRY_RUN` only. Decision log. No sending.
- [ ] **10** RESPONDER tab UI: state switch, categories, tones, windows, decision log, explain view.
- [ ] **11** CONTACTS tab UI: search, ALLOW/NEVER, per-contact tone.
- [ ] **12** Enable `LIVE` behind authentication and confirmation.
- [ ] **13** Version bump, features entry, full verification.

Phases 2–6 are pure and fully offline-testable, which is most of the risk. Phase 9 ships a
responder that cannot send anything.

---

## 15. Verification

Offline suites for every pure class, registered in `scripts/test-speech.sh`; type and R.id checks
extended; XML validation.

**On-device, unprovable offline:** dry-run decisions look right across a normal day; a group is
never answered in personal-only mode; an unsaved number is ignored; a seven-message burst produces
exactly one decision; quiet hours fire correctly either side of midnight; an app with no reply
action is skipped; and finally, that a real reply arrives in the chat reading as IRIS rather than
as Suvra.

---

## 16. Open questions

All resolved.

| Question | Answer |
| --- | --- |
| Name | **COMMS DECK** approved |
| Quiet hours default | 23:00–07:00, customisable |
| Cooldown default | 2 hours, customisable |
| Urgent piercing quiet hours | **No.** Redirect to a phone call instead |
| Auto-disarm | **12 hours by default, customisable, with "until I switch it off" available** |

### Auto-disarm, recorded

A safety timer. Arming records the moment the responder switches itself off, so it cannot keep
answering on Suvra's behalf for weeks after he has forgotten it is on.

This is the feature's worst realistic failure. Not a crash — a responder quietly telling
colleagues "Suvra is asleep" on a Tuesday afternoon three weeks later, with nothing on screen to
explain why. Every other guard in §8 limits how *often* it speaks; only this limits how *long*.

Shipped as 12 hours, editable like every other value, with **"stay armed until I switch it off"**
as an explicit choice rather than an accidental default. When that choice is active a permanent
indicator is required, because nothing else would stop it being forgotten.

`ResponderSettings` therefore stores a duration **and** an "indefinite" flag, rather than a magic
duration value standing in for forever.

---

## 17. Phase 1 scope, for reference during implementation

1. Rename every "RECEIPT VAULT" string to "COMMS DECK".
2. New shell layout: title, neon rule, three sub-tab controls, a content container.
3. Existing archive body moves into the shell as the ARCHIVE section, with its wiring unchanged.
4. RESPONDER and CONTACTS sections exist as placeholders stating what they will hold.
5. Entry point, voice fallback and authentication prompt updated to the new name.
6. No responder logic whatsoever.
