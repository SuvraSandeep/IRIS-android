# IRIS Personal Memory & Voice Security — v2.0.0

## Vision

IRIS becomes a **personal AI that knows you** — your preferences, habits, people, schedule, and rules. It has a persistent memory that you can teach, correct, and export. It recognizes your voice and challenges strangers with biometric authentication.

---

## Part 1: IRIS Memory System

### What is "Memory"?

Memory is a collection of **facts, preferences, and rules** that IRIS knows about you. Unlike settings (which are toggles/dropdowns), memory is **free-form knowledge** that you teach IRIS through conversation or a dedicated UI.

### Memory Categories

| Category | Examples | How IRIS uses it |
|----------|---------|-----------------|
| **About Me** | "My name is Sandeep", "I work at Ericsson", "I live in Bangalore" | Personalizes responses, context |
| **People** | "Rahul is my brother", "Priya is my wife", "Dr. Sharma is my dentist" | Resolves relationships for calling |
| **Preferences** | "I prefer Hindi greetings", "Don't call anyone after 10 PM", "Always confirm before calling boss" | Customizes behavior dynamically |
| **Rules** | "Never auto-call premium numbers", "Always use speakerphone for office calls", "Wake me up only for Mom's calls after midnight" | Overrides default behavior |
| **Schedule** | "I'm in meetings 10 AM to 12 PM on weekdays", "Don't disturb on Sundays before 10 AM" | Smart DND, timing awareness |
| **Corrections** | "When I say 'ring office' I mean the Bangalore office, not Delhi", "My brother's nickname is Chhotu" | Improves accuracy over time |

### Memory Storage Format

```json
{
  "schema": "iris-memory",
  "version": 1,
  "createdAt": 1693000000000,
  "updatedAt": 1693000000000,
  "memories": [
    {
      "id": "m_001",
      "category": "about_me",
      "key": "name",
      "value": "Sandeep",
      "source": "user_input",
      "createdAt": 1693000000000,
      "confidence": 1.0
    },
    {
      "id": "m_002",
      "category": "preference",
      "key": "no_calls_after",
      "value": "22:00",
      "source": "user_input",
      "createdAt": 1693000000000,
      "confidence": 1.0
    },
    {
      "id": "m_003",
      "category": "people",
      "key": "brother",
      "value": "Rahul",
      "detail": "+91-9876543210",
      "source": "user_input",
      "createdAt": 1693000000000,
      "confidence": 1.0
    },
    {
      "id": "m_004",
      "category": "correction",
      "key": "ring office",
      "value": "Bangalore office",
      "detail": "+91-80-12345678",
      "source": "voice_correction",
      "createdAt": 1693000000000,
      "confidence": 0.95
    },
    {
      "id": "m_005",
      "category": "rule",
      "key": "midnight_exception",
      "value": "Only Mom and Wife can trigger wake after midnight",
      "source": "user_input",
      "createdAt": 1693000000000,
      "confidence": 1.0
    }
  ]
}
```

### Memory UI — New "Memory" Tab

Replace the current 4-tab layout with 5 tabs:
**🎯 Assistant | 🎙 Training | 🧠 Memory | 📊 Activity | ⚙ Settings**

#### Memory Tab Layout

```
┌─────────────────────────────────────┐
│  🧠  IRIS MEMORY                     │
│  12 memories • Last updated 2h ago   │
│                                      │
│  [ + Add Memory ]  [ 🔍 Search ]     │
└─────────────────────────────────────┘

┌─ 👤 ABOUT ME ────────────────────── ┐
│  Name: Sandeep                       │
│  Work: Ericsson                      │
│  City: Bangalore                     │
│  Language: Hindi preferred           │
│                        [ Edit ] [×]  │
└─────────────────────────────────────┘

┌─ 👥 PEOPLE ──────────────────────── ┐
│  Wife → Priya (+91...)              │
│  Brother → Rahul (+91...)           │
│  Boss → Mr. Verma (+91...)          │
│  Doctor → Dr. Sharma (+91...)       │
│                        [ Edit ] [×]  │
└─────────────────────────────────────┘

┌─ ⚙️ PREFERENCES ─────────────────── ┐
│  Don't call anyone after 10 PM       │
│  Always confirm before calling boss  │
│  Hindi greetings preferred           │
│                        [ Edit ] [×]  │
└─────────────────────────────────────┘

┌─ 📋 RULES ───────────────────────── ┐
│  Only Mom/Wife can wake after midnight│
│  Never auto-call premium numbers     │
│                        [ Edit ] [×]  │
└─────────────────────────────────────┘

┌─ 🔧 CORRECTIONS ─────────────────── ┐
│  "ring office" → Bangalore office    │
│  "chhotu" → Rahul (brother)         │
│                        [ Edit ] [×]  │
└─────────────────────────────────────┘

──────────────────────────────────────
[ 📤 Export Memory ]  [ 📥 Import ]
```

#### Add Memory Dialog

When user taps "+ Add Memory":

```
┌─────────────────────────────────────┐
│  Teach IRIS something new            │
│                                      │
│  Category: [About Me ▼]             │
│                                      │
│  What should IRIS remember?          │
│  ┌─────────────────────────────┐    │
│  │ My name is Sandeep          │    │
│  └─────────────────────────────┘    │
│                                      │
│  [ Cancel ]        [ Remember ]      │
└─────────────────────────────────────┘
```

### How Memory Affects Behavior

IRIS checks memory at key decision points:

| Decision Point | Memory Check |
|---|---|
| Wake phrase detected | Rules: "only Mom/Wife after midnight" → check caller |
| Before calling | Preferences: "don't call after 10 PM" → warn/block |
| Contact resolution | People: "brother = Rahul" → resolve relationship |
| Correction learning | Corrections: "ring office = Bangalore office" |
| Greeting | About Me: "Name is Sandeep" → "Good morning, Sandeep" |
| Unknown command | Rules: check if any rule applies before rejecting |

### Memory Export/Import

Same pattern as profile export — biometric-gated, JSON format:
- Export: `IRIS-memory-2026-08-28.irismemory`
- Import: merges by `id`, newer `updatedAt` wins on conflict
- Encrypted at rest via SecureStore (same AES-GCM pattern)

---

## Part 2: Voice Security — Stranger Detection & Challenge

### Current Flow
```
Wake detected → Speaker verification → Match? → Proceed / Silently ignore
```

### New Flow with Challenge
```
Wake detected
    ↓
Speaker verification
    ├── Owner (similarity ≥ 0.70) → Proceed normally
    ├── Unknown (similarity 0.45–0.70) → CHALLENGE MODE
    │       ↓
    │   "I don't recognize your voice. Unlock the phone to continue."
    │       ↓
    │   Show notification with biometric/PIN prompt
    │       ├── Authenticated → Proceed + ask "Should I remember this voice?"
    │       │       ├── Yes → Re-enroll voiceprint (average with new sample)
    │       │       └── No → One-time access only
    │       └── Not authenticated / timeout → "Access denied." → Rearm
    │
    └── Stranger (similarity < 0.45) → Silently ignore + log
```

### Why the 3-Tier System?

- **≥ 0.70 (Owner):** You, in your normal voice. Proceed.
- **0.45–0.70 (Unknown):** Could be you with a cold, on speakerphone, in a noisy room, or a family member. Challenge with biometric.
- **< 0.45 (Stranger):** Clearly not you. Silently ignore — don't even reveal IRIS is listening.

### Voice Re-Enrollment

When the owner authenticates via biometric after a challenge, IRIS asks:

"Was that your voice? Should I update my memory of how you sound?"

If yes → average the new audio embedding with the stored voiceprint:
```
new_voiceprint = normalize(0.7 * old_voiceprint + 0.3 * new_embedding)
```

This lets the voiceprint adapt over time (voice changes with colds, aging, different mics).

### Stranger Behavior Options (stored in Memory)

User can set these via the Memory tab:

| Memory Rule | Behavior |
|---|---|
| "Allow wife to use IRIS" | Challenge → biometric → if authenticated, allow |
| "Block all strangers" | Anything below 0.70 is silently ignored |
| "Ask for all calls" | Always require biometric before placing a call |
| "Family can use basic commands" | Allow time/battery but not calls without auth |

---

## Part 3: Implementation Plan

### New Files

| File | Size | Purpose |
|------|------|---------|
| `MemoryStore.java` | ~250 lines | Memory CRUD, search, export/import, encrypted storage |
| `view_memory.xml` | ~200 lines | Memory tab layout |

### Changed Files

| File | Changes |
|------|---------|
| `IrisListeningService.java` | Memory-aware decision making, voice challenge flow, re-enrollment |
| `MainActivity.java` | New Memory tab, add/edit/delete memory UI, 5-tab layout |
| `activity_main.xml` | 5th tab button for Memory |
| `ProfileStore.java` | Voiceprint re-enrollment method |
| `SpeakerVerifier.java` | 3-tier verification (owner/unknown/stranger) |
| `AppSettings.java` | Voice challenge mode setting |
| `view_settings.xml` | Voice challenge mode selector |

### New Dependencies

**None needed for basic memory.** All memory is stored as encrypted JSON via existing SecureStore.

**For smart memory understanding (Phase 2 of memory):**
- Optional: Small on-device LLM for natural language memory extraction
  ("Remember that Rahul's birthday is March 15" → auto-categorize as People/Schedule)
- For now: manual categorization only

### Storage

| Data | File | Encryption |
|------|------|------------|
| Memories | `iris_memory_v1.enc` | AES-256-GCM via SecureStore |
| Profile (contacts, wake, voiceprint) | `iris_profile_v2.enc` | AES-256-GCM via SecureStore |
| Settings | SharedPreferences | Not encrypted (non-sensitive) |
| Logs | `iris_activity_v2.enc` | AES-256-GCM via SecureStore |

### Memory-Aware Command Processing

```
handleCommand(heard):
  1. Check trained phrases (existing)
  2. Check memory corrections ("ring office" → Bangalore office)
  3. Check memory relationships ("call my brother" → Rahul)
  4. Check memory preferences ("don't call after 10 PM" → block/warn)
  5. Quick actions (existing)
  6. Redial (existing)
  7. Call patterns (existing)
  8. Memory-enhanced no-match ("I don't know that. Want to teach me?")
```

### Voice Challenge Flow (detailed)

```java
// In IrisListeningService.onWakeDetected:
float similarity = speakerVerifier.cosineSimilarity(embedding, voiceprint);

if (similarity >= 0.70f) {
    // OWNER — proceed normally
    proceedToCommand();

} else if (similarity >= 0.45f) {
    // UNKNOWN — challenge with biometric
    LogStore.append("CHALLENGE", "Voice similarity " + similarity);
    broadcastMessage("I don't recognize your voice. Please verify.");
    
    // Show notification with unlock prompt
    showVoiceChallengeNotification(rawAudio);
    
    // Start 30-second timeout
    handler.postDelayed(() -> {
        broadcastMessage("Verification timed out.");
        rearmAfterAction();
    }, 30_000);

} else {
    // STRANGER — silently ignore
    LogStore.append("STRANGER", "Voice rejected, similarity " + similarity);
    startWakeDetection(); // silently rearm
}
```

### Biometric Challenge Notification

```
┌─────────────────────────────────────┐
│ 🔒 IRIS Voice Verification          │
│                                      │
│ Someone triggered your wake phrase.  │
│ Verify your identity to continue.    │
│                                      │
│ [ Dismiss ]        [ Verify →  ]     │
└─────────────────────────────────────┘
```

Tapping "Verify" → BiometricPrompt / device credential → if success:

```
"Verified. Was that your voice just now?"
   [ Yes, update my voiceprint ]
   [ No, one-time access ]
```

---

## Part 4: Export/Import Format

### Memory Export File: `.irismemory`

```json
{
  "schema": "iris-memory",
  "version": 1,
  "exportedAt": 1693000000000,
  "device": "Samsung Galaxy S21",
  "memoryCount": 12,
  "memories": [ ... ]
}
```

### Import Merge Strategy

| Scenario | Action |
|---|---|
| New memory (id doesn't exist locally) | Add it |
| Same id, same content | Skip |
| Same id, different content | Keep newer (by updatedAt) |
| Same key in same category | Keep both, mark conflict for user review |

### Import requires biometric authentication (same as profile import).

---

## Part 5: Suggested Default Memories

On first launch, IRIS could pre-populate with helpful suggestions:

```
"Would you like to tell me about yourself?"
→ "What's your name?"
→ "What language do you prefer for greetings?"
→ "Should I avoid calling anyone late at night?"
→ "Is there anyone who should always get through, even at midnight?"
```

This creates an **onboarding flow** that doubles as memory population.

---

## Version Plan

| Version | What ships |
|---------|-----------|
| **v2.0.0** | MemoryStore, Memory tab UI, memory-aware command processing, memory export/import |
| **v2.1.0** | Voice challenge flow (3-tier verification), biometric challenge, voiceprint re-enrollment |
| **v2.2.0** | Memory onboarding flow, smart suggestions, schedule-based DND |
| **v3.0.0** | Natural language memory extraction (on-device LLM) |

### Versioning
- v2.0.0: Major feature (memory system) → +1.0.0 from 1.2.0
- versionCode: 184 (next after 183)
- okhttp: pinned at 4.12.0 (not applicable)

---

## Models Needed

| Model | Size | Required for | When |
|-------|------|-------------|------|
| `speaker_model.tflite` | ~3-5 MB | Speaker verification (already planned) | v1.0.0 (done) |
| None | 0 | Memory system (pure JSON storage) | v2.0.0 |
| None | 0 | Voice challenge (uses existing speaker model) | v2.1.0 |
| `intent_classifier.tflite` | ~2 MB | Natural language memory extraction | v3.0.0 |
| `gemma-2b.onnx` | ~1.5 GB | Full conversational AI | v3.0.0 |

**v2.0.0 and v2.1.0 need NO new models.** Pure Java + existing infrastructure.
