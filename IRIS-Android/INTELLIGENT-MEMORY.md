# IRIS Intelligent Memory System — v3.0.0

## Vision

IRIS should feel like a personal assistant that **learns by watching and listening**, not one you have to manually configure. It should:

1. **Learn from your behavior** — auto-detect patterns from call history
2. **Learn from your voice** — "Remember that Rahul's birthday is March 15"
3. **Protect itself** — fingerprint required for training and sensitive operations
4. **Present memory beautifully** — not a boring list, but an organized brain
5. **Be smart about preferences** — suggest rules based on patterns it notices

---

## Part 1: Auto-Learning from Behavior

### What IRIS observes and learns automatically

| Observation | Auto-generated memory | Type |
|---|---|---|
| You call Mom every day at 9 PM | "routine: Call Mom around 9 PM daily" | schedule |
| You never call after 11 PM | "preference: Quiet hours after 11 PM" | preference |
| You call Rahul most on weekends | "pattern: Rahul is a weekend contact" | pattern |
| You always confirm calls to Boss | "preference: Always confirm for Boss" | preference |
| You called an unknown number | "observation: New number +91... called at 3 PM" | observation |
| You rejected a voice 3 times | "security: Frequent stranger attempts detected" | security |

### How auto-learning works

New class: `BehaviorAnalyzer.java`

After every call (in `performCall` and `recordCall`):
1. Analyze call history for patterns
2. Generate suggested memories with `confidence < 1.0` and `source = "auto_learned"`
3. Show suggestions as a card in the Memory tab: "IRIS noticed you call Mom every evening. Save this pattern?"
4. User can accept, edit, or dismiss

Auto-learned memories have a **yellow ⚡ badge** to distinguish from user-created ones.

---

## Part 2: Voice-to-Memory ("Remember that...")

### Natural language memory input via voice

When IRIS is listening (command phase), it should recognize memory commands:

| Voice command | What IRIS does |
|---|---|
| "Remember that Rahul's birthday is March 15" | Creates memory: people/rahul birthday = March 15 |
| "Remember my wife's name is Priya" | Creates memory: people/wife = Priya |
| "Don't call anyone after 10 PM" | Creates preference: no calls after = 10 PM |
| "I work at Ericsson" | Creates memory: about_me/work = Ericsson |
| "Forget that" | Deletes last auto-created memory |
| "What do you know about me?" | Reads back all About Me memories |
| "What do you remember?" | Reads back memory count and highlights |

### Implementation

New patterns in `IrisListeningService.java`:

```java
MEMORY_PATTERN = "^(?:remember|note|save|store)\\s+(?:that\\s+)?(.+)$"
FORGET_PATTERN = "^(?:forget|delete|remove)\\s+(?:that|the last (?:memory|thing))$"
RECALL_PATTERN = "^(?:what do you (?:know|remember)|tell me about (?:me|myself)|my (?:memories|info))$"
```

### Smart Parsing (without LLM)

For v3.0.0 (no LLM), use rule-based parsing:

```
"Remember that Rahul's birthday is March 15"
  → key: "rahul birthday"
  → value: "March 15"
  → category: "people" (detected "rahul" matches a contact)

"Don't call anyone after 10 PM"
  → key: "no calls after"
  → value: "10 PM"
  → category: "preference" (detected "don't" + "call" + time)

"I work at Ericsson"
  → key: "work"
  → value: "Ericsson"
  → category: "about_me" (detected "I" + personal statement)
```

New class: `MemoryParser.java` (~150 lines)
- Extracts key/value from natural language
- Auto-categorizes based on keywords
- Handles "my X is Y", "X's Y is Z", "don't X after Y", "I am/work/live X"

---

## Part 3: Biometric Protection for Training

### What requires fingerprint/PIN

| Action | Current | New |
|---|---|---|
| Wake phrase training | No auth | ✅ Fingerprint required |
| Contact command training | No auth | ✅ Fingerprint required |
| Voiceprint re-enrollment | No auth | ✅ Fingerprint required |
| Adding/editing memory | No auth | ✅ Fingerprint required |
| Deleting memory | No auth | ✅ Fingerprint required |
| Export profile | Fingerprint | ✅ Stays |
| Export memory | Fingerprint | ✅ Stays |
| Import profile | Fingerprint | ✅ Stays |
| Import memory | Fingerprint | ✅ Stays |
| Viewing logs | No auth | No auth (not sensitive) |
| Changing settings | No auth | No auth (convenience) |

### Implementation

Wrap training start and memory mutations with `authenticateThen()`:
- `beginWakeTraining()` → `authenticateThen("Train wake phrase", this::beginWakeTraining)`
- `requestContactForTraining()` → `authenticateThen("Train contact", ...)`
- `showAddMemoryDialog()` → `authenticateThen("Add memory", ...)`
- Memory delete → `authenticateThen("Delete memory", ...)`

---

## Part 4: Beautiful Memory UI

### Current: Flat list with delete buttons (boring)
### New: Organized brain with categories, badges, and suggestions

```
┌─────────────────────────────────────┐
│  🧠  IRIS MEMORY                     │
│  23 memories • 4 auto-learned       │
│                                      │
│  ┌─────────┐ ┌─────────┐           │
│  │ + Add   │ │ 🎤 Speak│           │
│  └─────────┘ └─────────┘           │
└─────────────────────────────────────┘

┌─ ⚡ IRIS NOTICED ─────────────────── ┐
│  💡 You call Mom every evening       │
│     around 9 PM                      │
│  [ Save pattern ]  [ Dismiss ]       │
│                                      │
│  💡 You haven't called Dad           │
│     in 2 weeks                       │
│  [ Remind me ]     [ Dismiss ]       │
└─────────────────────────────────────┘

┌─ 👤 ABOUT ME ────────────────────── ┐
│  Name: Sandeep                       │
│  Work: Ericsson                      │
│  City: Bangalore                     │
│  Language: Hindi preferred           │
└─────────────────────────────────────┘

┌─ 👥 PEOPLE ──────────────────────── ┐
│  Wife: Priya • +91...               │
│  Brother: Rahul • Birthday Mar 15   │
│  Boss: Mr. Verma                     │
│  ⚡ Rahul is a weekend contact      │
└─────────────────────────────────────┘

┌─ 🛡️ PREFERENCES & RULES ──────────── ┐
│  🔇 Quiet hours after 11 PM         │
│  ✅ Always confirm for Boss          │
│  ⚡ Auto-detected: No calls on      │
│     Sundays before 10 AM            │
└─────────────────────────────────────┘

┌─ 🔧 CORRECTIONS ─────────────────── ┐
│  "ring office" → Bangalore office    │
│  "chhotu" → Rahul                    │
└─────────────────────────────────────┘

──────────────────────────────────────
[ 📤 Export ]  [ 📥 Import ]  [ 🗑 Clear ]
```

### Key UI improvements

1. **⚡ IRIS NOTICED** section at top — auto-learned suggestions with accept/dismiss
2. **🎤 Speak** button — tap to dictate a memory via voice
3. **Category cards** with emoji headers and colored borders
4. **Badges** — ⚡ for auto-learned, 🔒 for fingerprint-protected
5. **Swipe to delete** (or long-press → confirm dialog)
6. **Search** with category filter

---

## Part 5: Speak-to-Add-Memory Flow

### How it works

1. User taps "🎤 Speak" in Memory tab
2. IRIS: "What should I remember?" (TTS)
3. SpeechRecognizer starts listening
4. User: "Remember that my sister's name is Neha"
5. MemoryParser extracts: category=people, key=sister, value=Neha
6. IRIS: "Got it. Your sister is Neha." (TTS + Toast)
7. Memory saved, list refreshes

### If parsing is uncertain

1. User: "Rahul likes pizza"
2. MemoryParser: not sure about category
3. IRIS: "I'll remember that under People. Is that right?" (TTS)
4. Show confirmation dialog with category picker
5. User confirms or changes category

---

## Part 6: Memory-Powered Intelligence

### How memories make IRIS smarter

| Memory | How IRIS uses it |
|---|---|
| "name: Sandeep" | "Good morning, Sandeep." in greeting |
| "no calls after: 10 PM" | Blocks/warns for late calls |
| "routine: Call Mom at 9 PM" | At 9 PM: "It's about time to call Mom. Should I?" |
| "rahul birthday: March 15" | On March 15: "It's Rahul's birthday! Want to call?" |
| "wife: Priya" | "Call my wife" → resolves to Priya |
| "pattern: Rahul weekend" | On weekday: "You usually call Rahul on weekends" |
| "correction: ring office → Bangalore" | Auto-resolves "ring office" |

### Proactive notifications (using AlarmManager)

- Birthday reminders → notification at 9 AM on the day
- Routine call reminders → notification at the usual time
- "Haven't called Dad in 2 weeks" → weekly check notification

---

## Files to Create

| File | Lines | Purpose |
|------|-------|---------|
| `MemoryParser.java` | ~200 | Natural language → memory extraction |
| `BehaviorAnalyzer.java` | ~180 | Call pattern detection, auto-learning |
| `view_memory.xml` | ~250 | Complete redesign of Memory tab |

## Files to Change

| File | Changes |
|------|---------|
| `MemoryStore.java` | Add auto-learn methods, suggestion system, confidence tracking |
| `IrisListeningService.java` | Memory voice commands, behavior analysis after calls, proactive suggestions |
| `MainActivity.java` | Redesigned Memory tab, speak-to-add, suggestion cards, biometric gates on training |
| `activity_main.xml` | Already has 5 tabs (done) |
| `ProfileStore.java` | Call pattern helpers for BehaviorAnalyzer |

## No New Dependencies

Everything is pure Java + existing Android APIs:
- `SpeechRecognizer` for voice-to-memory (already used)
- `BiometricPrompt` for training protection (already used)
- `AlarmManager` for proactive reminders (new but standard Android API)
- No LLM, no new TFLite models

## Version

- versionName: 3.0.0 (major feature — intelligent memory)
- versionCode: 186
- okhttp: pinned at 4.12.0 (not applicable)

## Implementation Order

| Step | What | Effort |
|------|------|--------|
| 1 | `MemoryParser.java` — NL parsing engine | Medium |
| 2 | `BehaviorAnalyzer.java` — pattern detection | Medium |
| 3 | Biometric gates on training/memory | Low |
| 4 | Voice memory commands in IrisListeningService | Medium |
| 5 | Redesigned Memory tab with suggestions | Medium |
| 6 | Speak-to-add flow | Low |
| 7 | Proactive notifications (AlarmManager) | Medium |
