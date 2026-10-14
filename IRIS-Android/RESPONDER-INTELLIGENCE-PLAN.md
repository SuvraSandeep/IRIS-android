# COMMS DECK — Responder Intelligence Plan

A staged plan to make IRIS *feel* intelligent on the messaging side **without a language
model**. Everything here is pattern-matching, memory, personalization and adaptation — all of it
on-device, offline, single-user, and (where it writes anything persistent) device-authenticated.

---

## Completion status (as of 16.22.0 / build 400)

**Offline-buildable scope is complete.** Every item that can be implemented and verified with the
offline suite is shipped and green. What remains needs Android permissions and real on-device
testing, and is deliberately deferred per this plan's own "do it last, on-device" rule.

| Item | Status | Shipped in |
| --- | --- | --- |
| S1/S2 PhraseMatcher + 1 — robust/loose matching | ✅ done | 16.6.0 |
| S3 + 2 — conversation memory (reminder edit/cancel) | ✅ done | 16.8.0 |
| 3 — learn from corrections | ✅ done | 16.9.0 |
| 4 — profile sharing (opt-in iris-share.json, PII-safe, witty, 2nd-person) | ✅ done | 16.13.0–16.17.0 |
| 6 — sentiment/urgency tone softening | ✅ done | 16.10.0 |
| 7 — Hinglish/Odia (≈45 scenarios, corpus-safe) | ✅ done | 16.11.0–16.12.0 |
| 8 — triage digest | ✅ done | 16.18.0 |
| 9.0 — keep-unread default | ✅ done | 16.7.0 |
| 9.2 — per-app tone | ✅ done | 16.21.0 |
| 9.3 — SMS OTP/shortcode safety | ✅ done | 16.7.0 |
| 9.5 — media-type labelling | ✅ done | 16.20.0 |
| Emoji understanding (bonus) | ✅ done | 16.19.0 |
| 9.1 — reply reach (reply-when-present) | ✅ already covered by NotificationReply | — |
| 9.4 — mark-as-read after reply | ✅ done | 16.7.0 |
| 5 — self-activating modes | ◑ decision core + auto-sleep done; driving/meeting signals deferred | 16.22.0 |
| 9.1 — Instagram deep-link fallback | ⬜ deferred (device-only) | — |
| 9.6 — deep-link "open pre-filled" | ⬜ deferred (voice-side, low value) | — |
| 9.4 — snooze / auto-dismiss | ✖ dropped on purpose (hides messages; fights keep-unread) | — |

### Deferred work — device-testing checklist

These require new permissions and can only be verified on a real device. The pure decision core
for the modes (`AutoModePolicy`) is already built and tested; only the signal collectors remain.

- [ ] **Auto-DRIVING signal.** Collect via a car-Bluetooth connect (needs `BLUETOOTH_CONNECT` on
      Android 12+ and a known-device allowlist the owner picks) and/or Activity Recognition
      `IN_VEHICLE` (needs `ACTIVITY_RECOGNITION`, sampled not streamed for battery). Debounce
      30–60s. Feed `Signals.driving` + `autoDriving` into `AutoModePolicy`. Verify: connecting the
      car stereo flips DRIVING on; disconnecting clears it; a manual mode is never overridden.
- [ ] **Auto-MEETING signal.** A calendar event in progress marked busy (needs Calendar read).
      Feed `Signals.meeting` + `autoMeeting`. Verify: mode ends a few minutes after the event; no
      event titles are ever read into a reply (privacy).
- [ ] **"Is he free tomorrow?"** read-only busy/free from the calendar WITHOUT disclosing event
      details.
- [ ] **Instagram deep-link reply fallback (9.1).** When a DM notification exposes no RemoteInput
      action, open the DM pre-filled instead; never report it as sent. App-version dependent.
- [ ] **Each auto-mode and the Instagram path OFF by default and independently toggleable.**

### Build-verification caveat

Releases 16.6.0 → 16.22.0 are all offline-suite-green but **none has been compiled against the
Android SDK**. The offline suite catches syntax and unresolved type names, NOT Android method
signatures or imports. Build 16.22.0 on CI and confirm it compiles before treating the plan as
closed; fix any Android-only errors it surfaces (the one class the offline checks cannot catch).

---

## Guiding principles (do not break these)

- **No LLM.** Intelligence = better understanding, context, and tailoring. IRIS must never *claim*
  to have read a message it only pattern-matched.
- **Silence is the safe default.** A missed match costs one unsent reply; a wrong confident reply
  sends a stranger a message from Suvra's account. When unsure, stay silent or ask.
- **Pure policy classes.** All decision logic lives in classes with **no Android imports**
  (`MessageCategory`, `ReplyTone`, `ResponderWindow`, `ResponderGuards`, `ReplyTemplates`,
  `ReminderParser`, `ResponderMode`, `SmartReplies`, and the new ones below). The Android layer
  (`AutoResponder`, `MainActivity`, listeners) only gathers facts and reports.
- **Every reply names IRIS and Suvra.** Transparency is never dropped; only verbosity varies.
- **Don't touch wake/owner-identity.** Per `AGENTS.md`, wake, speaker verification, model
  selection and authenticated-write controls are out of scope here and require explicit owner
  requests. None of this plan modifies them.
- **Ship in small verified steps.** Each item: new entry at the TOP of `IRIS-FEATURES.html`
  `FEATURES`, `BUILD_VERSION` + `versionCode` bump (feature = +0.1.0, fix = +0.0.1), full offline
  suite green, HTML validated, commit message delivered. The owner commits; the agent never does.
- **The offline suite cannot run the Android compiler.** It catches syntax and unresolved types,
  not Android method signatures/imports. Keep Android-only code thin; keep logic in pure classes
  that the suite fully compiles and runs.

## Current state (what we build on)

- `MessageCategory.classify(raw, mentionKeywords, irisSpokeLast)` → `Result{kind, detail}`,
  whole-word/phrase matching on `normalise()` output. Has fuzzy edit-distance for URGENT and for
  reminder day-words.
- `SmartReplies.match(body, tone)` → best keyword rule's per-tone reply (`{owner}` left for
  `ReplyTemplates.fill`). Gated by `handlesCategory()` to generic buckets only.
- `ResponderGuards.evaluate(message, policy)` → `Verdict`. Caps, cooldown, scope, loop, etc.
- `AutoResponder.consider(context, incoming)` orchestrates; `compose()` builds the reply.
- `ResponderSettings` (SharedPreferences) holds all owner settings, name rules, per-category
  enables, custom templates, mode, fingerprint/answered state.
- `ReminderStore` holds reminders + the per-conversation "awaiting a time" slot.
- `ResponderLog` append-ish decision log with `Entry{sender, incoming, category, tone, verdict,
  reply, sendResult, ...}`.
- `PersonalProfile` (iris-me.json) and `MemoryStore` exist but are barely used by the responder.
- `ResponderMode` {NONE, DRIVING, SLEEPING, MEETING, AWAY}, currently manual only.

---

# Shared infrastructure (build first — items 1 & 2 depend on it)

## S1. `PhraseMatcher` (pure)

A reusable matcher used by both `SmartReplies` and (optionally) `MessageCategory`.

Responsibilities:
- **Tokenize** `normalise()` output into words.
- **Drop stopwords** for matching purposes (`the, a, an, is, are, to, me, my, you, your, please,
  can, could, him, her, …`) — kept in a small list; never drop a word that is itself a keyword.
- **Canonicalize** each token through a **synonym map** (see S2) so `pics → photo`, `ring → call`.
- Provide two match predicates:
  - `anyPhrase(flat, phrases)` — current behaviour (ordered substring), kept for exact needs.
  - `allTokens(messageTokens, requiredTokens, fuzzy)` — order-free: every required (canonical)
    token must be present in the message's canonical token set, each either exactly or within an
    edit-distance budget.
- **Fuzzy per token:** budget = `len<=4 ? 0 : len<=6 ? 1 : 2`, first letter must match, length
  delta ≤ budget. Reuse the `editDistance` already written in `MessageCategory`/`ReminderParser`
  (extract to `PhraseMatcher` and have the others delegate, to keep one copy).
- **Scoring:** return a score, not just a boolean, so the caller can pick the best rule:
  `score = (#required tokens matched) + specificityBonus − fuzzyPenalty`. A rule only "matches"
  if **all** required tokens are covered.

Why all-tokens-must-match: avoids the false-positive explosion of OR-matching. "send the photos"
becomes required tokens `{send, photo}`; "send me the pics tomorrow" canonicalizes to
`{send, photo, tomorrow}` ⊇ `{send, photo}` → match. But "I sent a photo to mom" also contains
`{send, photo}` → would match. Mitigate with **intent anchors** (below).

Edge cases:
- **Over-matching short/common words.** Require ≥2 content tokens for a rule, OR 1 highly
  distinctive token (e.g., "otp", "diwali"). Rules declare their required set explicitly.
- **Negation.** "don't send photos", "no need to call" — detect a negator (`not, dont, no, never,
  stop`) immediately before/around the anchor and suppress the match (or route to a different
  rule). Pin with tests.
- **Media-placeholder collisions.** "sent a photo" is already `MEDIA_ONLY`; ensure canonicalization
  doesn't turn a media placeholder into a "send me photos" request. Order matters: category
  classification runs first; SmartReplies only sees generic buckets.
- **The Odia/Hinglish must-not-match corpus.** Re-run every existing must-not-match case; token-set
  matching must not claim them. This is the main regression risk of item 1.

Tests: `PhraseMatcherTest` — tokenization, stopword drop, synonym canonicalization, fuzzy budget
boundaries, all-tokens matching, negation suppression, and the full must-not-match corpus.

## S2. Synonym map (data)

A small curated `Map<String,String>` token → canonical token, in `PhraseMatcher`:
- photo/pic/pics/image/images/pictures/snap → `photo`
- call/ring/dial/phone/ringing → `call`
- free/available/avail → `free`
- send/share/forward/drop → `send`
- file/doc/document/pdf/attachment → `file`
- msg/message/text/txt → `message`
- remind/reminder/remember → `remind`
- meet/meetup/catchup → `meet`
- location/loc/pin/address → `location`
- money/cash/upi/gpay/phonepe/paytm → `pay`
- urgent/asap/emergency → leave to `MessageCategory` (don't duplicate the urgent path)

Keep it short and curated; a huge synonym map invites false positives. Each entry gets a test.

## S3. `ConversationContext` (pure state model + Android store) — needed by item 2

Pure model `ConversationContext.State`:
```
enum Pending { NONE, REMINDER_TIME, MEETING_TIME, CONFIRM_RING, CLARIFY }
class State {
  String lastCategory;        // last MessageCategory.Kind name
  String lastSmartRuleId;     // last SmartReplies rule that fired, if any
  Pending pending;            // open slot awaiting the sender's next message
  String pendingData;         // e.g. the reminder task text
  long   lastInboundMs;       // for TTL
  int    turnCount;           // messages in this thread within the window
}
```
Pure transition function `ConversationContext.next(State, inboundFacts) -> State` so the whole
state machine is offline-testable. The Android side (`ConversationStore`, SharedPreferences keyed
by `conversationKey`) only persists/loads and applies TTL (default 2h; configurable). Fold the
existing `ReminderStore` "awaiting a time" slot into this (or have `ReminderStore` delegate) so
there is one notion of "pending".

Edge cases: TTL expiry mid-thread; topic change (a new specific category clears a stale slot);
process restart (persist); the same number used by two people (can't tell — key by number, accept
it); group threads (don't carry 1:1 context into groups).

---

# 1. Understand more loosely (robust matching)

**Goal:** "send me the pics", "can you ring him", "u free?" all hit the right rule.

**Design:** `SmartReplies.Rule` gains an optional `requiredTokens` (canonical) alongside the
existing `any` phrases. `SmartReplies.match` becomes:
1. URL / punctuation-emoji special cases (unchanged).
2. Build canonical token set via `PhraseMatcher`.
3. For each rule: if it has `requiredTokens`, use `allTokens` scoring; else fall back to the
   existing `anyPhrase`. Track the best score; tie-break by `priority`.
4. Apply negation suppression.
5. Return the best rule's reply, or empty.

Migrate the ~90 existing rules: for the high-value ones, add `requiredTokens` (e.g. photos →
`{send, photo}`; call-him → `{call}` + anchor; where-is-he → `{where}` + a `he/suvra` anchor).
Keep `any` phrases as a fast exact path.

`MessageCategory`: apply the same canonicalization to its THANKS/GREETING/CHECK_IN/REMINDER phrase
sets so e.g. "thx a lot" or "u up?" classify. Do **not** loosen URGENT beyond its current fuzzy
(owner said leave urgent alone) — keep URGENT exactly as-is.

**Edge cases:** negation; the must-not-match corpus (Odia, "robot vacuum", Twilio auto-reply
text, "the assistant will send the file"); very short messages (fall back to exact/`SHORT`);
synonym collisions ("free" = available vs "free gift" spam — spam rule should win by priority).

**Tests:** extend `SmartRepliesTest` and `MessageCategoryTest` with loose-match positives and a
dedicated negation + must-not-match block. Target: the 190 smart checks + the 159 category checks
all still pass, plus new ones.

**Risk:** medium (false positives). Mitigated by all-tokens-must-match + anchors + the
must-not-match regression corpus. **Effort:** 1 feature release. **Version:** +0.1.0.

---

# 2. Remember the conversation (multi-turn context)

**Goal:** follow-ups and edits feel connected.

**Design:** On each `consider()`:
1. Load `ConversationContext.State` for `conversationKey`.
2. If `pending != NONE` and the message isn't a clear new topic, route to the slot handler:
   - `REMINDER_TIME` → existing reminder-time path (already works).
   - `MEETING_TIME` → confirm/schedule a meeting note.
   - `CLARIFY` → interpret as the answer to the last question.
3. Detect **edit intents** on the last action: "actually make it 6", "change it to tomorrow",
   "cancel that" → re-parse a time via `ReminderParser` and update/cancel the last reminder for
   this conversation (store the last reminder id in context).
4. Detect **continuation** of the previous reply: after IRIS answered "when's he back?", a message
   like "ok tell him to call me" (while `irisSpokeLast`) → treat as a request to the owner, log a
   callback note.
5. Write the new state (lastCategory, pending, lastInboundMs, turnCount++).

**Reference resolution (light):** "him/her/them" in a message to the owner's phone almost always
means the owner → map to Suvra. "that/it" → the subject of the last reply/last reminder. Keep
conservative; when ambiguous, don't guess.

**Edge cases:** stale slot (TTL) → treat next message fresh; conflicting new intent clears slot;
two pendings (shouldn't happen — one slot); restart persistence; group vs 1:1 (no cross-carry);
the sender ignores the question and says something unrelated → after N unmatched, drop the slot
gracefully (the reminder flow already stores undated and gives up — generalize that).

**Tests:** `ConversationContextTest` (pure transitions: open slot, fill, edit, cancel, expire,
topic-change, give-up). Integration logic kept in the pure model so the suite covers it.

**Risk:** medium (state bugs → wrong routing). Keep the state machine pure and exhaustively
tested. **Effort:** 1 feature release. **Version:** +0.1.0.

---

# 3. Learn from your corrections

**Goal:** IRIS adapts to how Suvra wants it to reply, over time, on-device.

**Design:**
- **UI (`MainActivity`):** long-press a decision-log row → menu:
  - "Teach a better reply" → text box; saved as an override.
  - "Always say this to <sender>" → reuses the existing `NameRule` SAY mechanism (per name).
  - "Never reply to messages like this" → disable that category for that sender, or add a NEVER
    name rule.
  - "Good reply / Bad reply" → thumbs signal.
- **Storage:** new `LearnedReplies` (own prefs file) keyed by a **scope**:
  - per-sender + category (`sender\u0001CATEGORY`),
  - per matched smart-rule id (`rule\u0001<id>`),
  each → preferred text + good/bad counters. Bounded (e.g. 200), device-authenticated writes
  (reuse `authenticateThen`), resettable, excluded from backup (like the archive).
- **Precedence in `AutoResponder`:** learned override (most specific scope that matches) **>**
  name-rule reply **>** smart reply **>** category template. A learned reply bypasses
  category-silence exactly like a name rule (`forceReply`), still subject to all safety/scope/caps.
- **Adaptive ranking (optional, later):** a smart rule with repeated "bad" marks for a sender gets
  down-ranked for that sender; "good" marks reinforce. Keep counters small and bounded.
- **Transparency:** a learned reply that names neither IRIS nor Suvra triggers the same non-blocking
  warning the template editor already shows ("they'll think it was you"). Saved as written.

**Edge cases:** conflicting learned entries (most-specific scope wins; show the owner which applied
in the log); migration/versioning of the store; an edited reply with placeholders (`{name}` etc.)
runs through `fill`; privacy (learned text can contain anything the owner typed — it's his);
clearing/reset path and a "forget what you learned about this sender" action.

**Tests:** `LearnedRepliesTest` (scope precedence resolution, round-trip, bounded eviction, counter
updates). The resolution order is pure and fully testable.

**Risk:** low-medium (precedence confusion). Make the log show *why* a reply was chosen (learned vs
rule vs template). **Effort:** 1 feature release (UI + store). **Version:** +0.1.0.

---

# 4. Use what it already knows about you

**Goal:** replies are tailored by relationship and (opt-in) profile facts.

**Design:**
- **Relationship-aware content** (not just tone): `AutoResponder` already resolves
  `effectiveContactRelation`. Add relation-specific flavouring:
  - FAMILY → warmer wording + offer the ring path when appropriate (already family-gated).
  - WORK → include an ETA-style line and stay professional.
  - FRIEND → casual.
  Implement as relation-aware variant selection in `ReplyTemplates`/`SmartReplies` (a `relation`
  parameter picking a sub-set), or as a suffix line. Keep every variant naming IRIS + Suvra.
- **Profile-answerable questions** (opt-in): `PersonalProfile` gains a **shareable allowlist** —
  the owner marks which fields IRIS may disclose (e.g. `"share": ["role","company","city"]`), set
  in `iris-me.json`. New SmartReplies rules "what does he do / where does he work / which city" →
  answered from profile **only if** the field is shareable; otherwise the existing "ask him" reply.
  **Never** shareable: phone numbers, address, email, financial, passwords — hard-blocked
  regardless of allowlist (reuse the security rules).
- **Known-contact personalization:** greet a saved contact by resolved name; reference the
  relationship ("I'll let your brother know" only if the owner set that relation — careful, could
  leak relationship info to a spoofed number; default to neutral unless saved + relation set).

**Edge cases:** missing/partial profile; unsaved sender (no personalization, no profile sharing);
group messages; a spoofed number claiming to be family (relation is keyed to the saved contact, not
the claim — safe); privacy review of every field that can leave the phone (default deny).

**Tests:** `ProfileSharingTest` (field allowlist gating, hard-blocked fields never leak);
relation→content mapping cases.

**Risk:** medium (privacy). Default-deny on sharing; explicit allowlist; hard-block PII. **Effort:**
1 feature release. **Version:** +0.1.0.

---

# 5. Make modes self-activating

**Goal:** DRIVING/MEETING/SLEEPING turn themselves on, so modes stop feeling manual.

**Design:**
- **Pure `AutoModePolicy`:** input = set of active signals + owner settings + current manual mode
  and its *source*; output = resolved `ResponderMode`. Precedence and anti-flap rules live here,
  fully testable.
- **Signals (Android, each optional and permissioned):**
  - **DRIVING:** Bluetooth connect to a known car device, or Activity Recognition `IN_VEHICLE`
    (debounced ~30–60s). `AutoModeManager` listens; feeds `AutoModePolicy`.
  - **MEETING:** a Calendar event in progress (CalendarContract, read permission) marked busy.
  - **SLEEPING:** inside the quiet-hours window (already known) — optional, since quiet hours
    already change wording; auto-SLEEPING mostly adds the "call to wake" redirect.
- **Manual override wins:** track `modeSource` (MANUAL vs AUTO). A manually set mode is never
  overridden by auto; auto only sets when source is AUTO/NONE. Clearing a manual mode returns
  control to auto. Surface this in the UI ("Driving — set automatically").
- **"Is he free tomorrow?"** → new `CalendarQuery` (read-only, permissioned) answers busy/free for
  a window **without disclosing event details** (privacy): "He looks free after 3pm" / "He's busy
  most of tomorrow — want me to note a request?"

**Edge cases:** Bluetooth to non-car devices (require a known-device allowlist the owner picks);
signal flapping (debounce + minimum dwell); meeting overrun (end mode a few minutes after event
end, or when the next signal clears); Activity Recognition battery cost (sample, don't stream);
calendar privacy (never read event titles aloud/into replies); permission missing → feature simply
off, never an error; two signals conflicting (DRIVING beats MEETING).

**Tests:** `AutoModePolicyTest` (truth table of signals × manual override × precedence × debounce
decisions — all pure). `CalendarQuery` logic (busy/free computation) pure with injected events.

**Risk:** higher (Android signals, permissions, battery, privacy). Do this **last**; keep each
signal independently toggleable and off by default. **Effort:** 1–2 feature releases. **Version:**
+0.1.0 each.

---

# 6. Read emotion and urgency, lightly

**Goal:** prioritize and soften for distress/anger; catch real urgency without the word "urgent".

**Design:** pure `SentimentScore.score(text)` → `{valence (-1..1), distress (0..1),
urgency (0..1)}` from:
- lexicons: negative/distress words (`sad, scared, help, accident, hospital, emergency, crying,
  please, stuck`), anger words, positive words; emoji sentiment; intensifiers (`very, so`),
  **negation** handling.
- urgency cues: distress lexicon, imperatives, time pressure ("right now", "immediately"),
  repetition (several messages in seconds — already available via burst/`answeredRecently`).

Use it to:
- **Soften tone:** high distress → force an empathetic variant (never SARCASTIC). Add a
  `ReplyTone` override: distress ⇒ gentle/professional.
- **Prioritize:** high urgency+distress → raise log priority and surface in the digest; optionally
  relax a cap for a genuinely distressed *saved* contact (conservative; never for unsaved).
- Keep the urgent **redirect-to-call in quiet hours** rule intact; sentiment informs wording, not
  the quiet-hours policy.

**Edge cases:** sarcasm ("great, just great" — can't reliably detect; don't try); negation ("not
good" ≠ good); false distress idioms ("dying laughing", "killed it"); non-English (lexicon is
English + common Hinglish — see item 7); never escalate an *unsaved* number on sentiment alone
(spam says "urgent" too).

**Tests:** `SentimentScoreTest` (labeled phrases, negation, emoji, idioms-that-must-not-trigger).

**Risk:** medium (false distress). Keep effects conservative (tone + priority, not new sends).
**Effort:** 1 feature release. **Version:** +0.1.0.

---

# 7. Stop going silent on your real languages

**Goal:** recognize transliterated Hinglish/Odia greetings & check-ins and reply in kind, instead
of silence.

**Design:**
- Add curated transliterated phrase sets to `MessageCategory` (and matching SmartReplies replies):
  - greetings: `namaskar, kemiti achha/achhu, bhala achha, kaise ho, kaisa hai, kemiti`
  - check-in: `kahan achha/achha ki, kahan ho, kaha achha, online achha ki, kuade achha`
  - thanks: `dhanyabad` (already partly)
  - well-being: `bhala achhu, theek achhi`
- Optionally **reply in kind:** a parallel set of Odia/Hinglish reply variants (owner-editable), or
  keep English replies that name IRIS. Start with English replies (lower risk), add vernacular
  variants later if the owner wants.
- Light **language tag** heuristic (script + keyword) to pick the reply language.

**Edge cases — THIS IS THE RISKY ONE:** the existing MUST-NOT-MATCH corpus is real Odia the owner
received that must stay NONE — e.g. "Bhala laguni" (= *not* feeling good), "Mu mora faeda
dekhibii", "Besi bhabuchu boliki amiti laguchi ki". New keywords must be **narrow** and **whole-
phrase**, and the full must-not-match corpus must still classify as NONE. "bhala achha" (good) vs
"bhala laguni" (not good) is exactly the kind of pair that breaks if done carelessly. Curate with
a native-meaning check and pin every case.

**Tests:** `MessageCategoryTest` gains a Hinglish/Odia positive block AND re-asserts the entire
existing must-not-match corpus. No regression allowed.

**Risk:** medium-high (false positives in a language we can't fully parse). Go slow; whole-phrase
only; expand only phrases the owner confirms. **Effort:** 1 feature release (then iterative).
**Version:** +0.1.0.

---

# 8. Smarter "what did I miss" (triage digest)

**Goal:** turn the count-based summary into a prioritized briefing.

**Design:** pure `NotificationTriage.summarize(items)` where each item =
`{sender, app, whenMs, category, awaitingReply, saved, relation}`:
- cluster by sender; collapse rollups/dupes;
- rank: family > urgent/distress > delivery/waiting > work > everyone else;
- flag who is **awaiting a reply** (IRIS answered vs not; sender messaged after IRIS spoke);
- produce a short briefing string: "3 people need you: Mom (2 messages), a delivery is waiting, and
  work can wait."
- never read message bodies aloud by default (privacy) — names, counts, categories only.

**Integration:** feed from the data `IrisNotificationListener`/`ResponderLog` already have; wire
into the existing "what did I miss / summarise my notifications" voice/text path.

**Edge cases:** spam excluded (already filtered); group chats counted once; privacy (no bodies);
empty state; very large backlogs (cap + "and N more").

**Tests:** `NotificationTriageTest` (clustering, ranking, awaiting-reply detection, caps) — pure.

**Risk:** low. **Effort:** 1 feature release. **Version:** +0.1.0.

---

# 9. Notification-panel actions & reply reach (all the tricks)

**Goal:** use everything a `NotificationListenerService` can do with WhatsApp, Instagram and SMS —
while guaranteeing the owner can still see what came in.

## 9.0 Keep messages visible — "mark unread" by default (owner's rule)

The owner's explicit requirement: after IRIS handles a message, it must **stay unread** so he knows
which chats to open.

- **Reality:** IRIS *reading* a notification via the listener does **not** mark the chat read in the
  app. A chat is only marked read by triggering the app's own "Mark as read" action or by opening
  it. There is **no public "mark as unread"** action for WhatsApp/Instagram through the panel.
- **Therefore "mark unread" = preserve-unread:** the default behaviour is **never trigger
  mark-as-read and never dismiss** after an auto-reply. The unread badge and the notification stay,
  so the owner sees exactly which chats need him. This is honest and reliable; document that it is
  "keep unread", not a synthetic unread flag.
- **Setting:** "Keep messages unread after I reply" — **default ON**. Opt-in opposite,
  "Mark as read after I reply" — **default OFF** (triggers the app's Mark-as-read action when one
  exists).
- **SMS nuance:** replying via the notification's RemoteInput does not mark the SMS thread read;
  preserve-unread holds. If IRIS ever uses `SmsManager` directly, it must not mark the inbound read
  either.
- **Edge:** after a reply, some apps re-post the notification (now showing "You: …"). IRIS must not
  treat its own reply re-post as a new inbound (already handled by the answered-fingerprint state) —
  and must verify the unread state survives the re-post rather than being cleared by it.

## 9.1 Reply reach (per channel)

- **WhatsApp:** RemoteInput reply — works today; sends directly, no app open.
- **SMS:** RemoteInput reply from the notification **and** direct `SmsManager` send to any number
  (with `SEND_SMS`). Use the notification reply to answer in-thread; reserve `SmsManager` for new
  sends the panel can't do.
- **Instagram:** use the RemoteInput reply action **when the app includes one** (version-dependent);
  when absent, fall back to a **deep link that opens the DM pre-filled** for a one-tap finish —
  never report that as "sent".

## 9.2 Per-app reply policy

A per-app action matrix the owner controls: `reply` / `archive-only` / `never`, plus per-app
default tone. E.g. archive Instagram but don't auto-reply it; always-professional on SMS. Resolved
in a pure helper so it's testable.

## 9.3 SMS safety — never auto-reply to OTP / shortcodes (safety, additive)

Hard rule, independent of every other setting and separate from the urgent path (which is left
untouched): **never auto-reply** to an SMS that is an OTP/2FA/verification code, is from a lettered
**shortcode** (e.g. `VM-HDFCBK`, `AX-SBIINB`), or is a known transactional/bank sender. Detect via
sender shape (letters/no normal number) + a content lexicon ("OTP", "code", "do not share", "OTP is
valid"). Replying to these leaks nothing useful and tells a bank gateway the number is live. Pure
classifier, labelled corpus.

## 9.4 Mark-as-read / dismiss / snooze (all opt-in, OFF by default)

Because these **hide** messages, they conflict with 9.0 and ship **off**:
- Trigger the app's "Mark as read" action (opt-in).
- Snooze or auto-dismiss low-priority notifications per owner rules (opt-in).
Each requires the owner to turn it on and accept that handled messages may then be hidden.

## 9.5 Richer media capture

Classify and log **photo / voice-note / document / sticker / GIF / video** distinctly from the
message's MIME type and placeholder text, so the archive and the digest can say "sent a voice note"
vs "sent a photo" instead of a generic placeholder. Image bytes are already captured; extend the
labelling and (optionally) capture other small attachment types within the existing image budget.

## 9.6 Deep-link "open pre-filled"

For a genuine *new* send IRIS can't perform (WhatsApp disallows programmatic send), offer a one-tap
deep link that opens the chat with text pre-filled, so the owner finishes with one tap. Clearly a
hand-off, never logged as delivered.

**Edge cases:** Instagram's reply action appearing/vanishing across app versions; per-app RemoteInput
result-key differences; reply re-post loops; "Mark as read" action label/availability differences;
dual-SIM SMS; privacy (never read OTP contents into the log or any reply); an app with no reply
action at all (archive-only, already handled as `NO_REPLY_ACTION`).

**Tests:** pure `SmsSafety` classifier (OTP/shortcode/transactional detection) over a labelled
corpus incl. the owner's real shortcodes; per-app policy resolution; media-type classification from
MIME/placeholder; a pure model of the preserve-unread decision (asserts the default never triggers
mark-as-read). The action-*triggering* itself is Android-only and can't be unit-tested — keep that
layer thin and guarded.

**Risk:** medium — triggering app actions and the Instagram reply action are app-version dependent
and only verifiable on-device. Keep the trigger layer thin; keep SMS-safety, per-app policy, media
classification and the unread decision pure and fully tested. **Effort:** 1–2 feature releases.
**Version:** +0.1.0 each.

---

# Recommended sequence

1. **S1/S2 + item 1 — robust matching.** Biggest perceived win, unblocks everything, pure, low
   Android risk. Must pass the must-not-match corpus.
2. **Item 9.0 + 9.3 — keep-unread default + SMS OTP/shortcode safety.** Both small and high-value:
   preserve-unread is almost free (just never auto-mark-read/dismiss) and the OTP rule is a real
   safety fix. Ship early.
3. **S3 + item 2 — conversation context.** Makes follow-ups/edits coherent.
4. **Item 3 — learn from corrections.** Turns IRIS adaptive; reuses auth + name-rule plumbing.
5. **Item 6 — sentiment/urgency.** Small, pure, improves tone and prioritization.
6. **Item 7 — Hinglish/Odia.** Careful, iterative; big win for real contacts.
7. **Item 4 — profile/relationship-aware.** Privacy-gated content tailoring.
8. **Item 8 — triage digest.** Pure, self-contained.
9. **Item 9.1–9.2, 9.5–9.6 — reply reach, per-app policy, media labelling, deep-link.** The rest of
   the panel tricks; some pieces (Instagram reply, action triggering) are app-version dependent and
   device-only to verify.
10. **Item 5 — self-activating modes.** Most Android/permission/battery risk; do last, each signal
    off by default and independently toggleable.
11. **Item 9.4 — mark-as-read / snooze / dismiss (opt-in).** Last, because it hides messages and
    conflicts with the keep-unread default; ships off.

# Cross-cutting checklist (every item)

- [ ] Logic in a pure class with no Android imports; Android layer stays thin.
- [ ] Offline test file added and registered in `scripts/test-speech.sh`.
- [ ] Full suite green (`python tests/run-offline-suites.py`), `ParseSources` clean,
      `check-unresolved-types.py` clean.
- [ ] Must-not-match / existing regressions re-run (especially items 1 and 7).
- [ ] Persistent writes device-authenticated and excluded from backup where sensitive.
- [ ] No PII leaves the phone; profile sharing is opt-in and PII is hard-blocked.
- [ ] New entry at TOP of `FEATURES` in `IRIS-FEATURES.html`; `BUILD_VERSION` + `versionCode`
      bumped; HTML validated (JS-array eval, not JSON.parse).
- [ ] Commit message delivered in the reply; the owner commits.
- [ ] Wake / owner-identity / audio paths untouched.
