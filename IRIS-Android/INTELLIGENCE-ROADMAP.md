# IRIS Intelligence Roadmap

## Current State (v1.1.0)

### What IRIS can do today
- Custom wake phrase detection (DTW acoustic matching)
- Speaker verification via neural voiceprint (TFLite, when model present)
- Voice-activated calling with 12+ English phrasings + Hindi/Hinglish
- Contact matching: exact, partial, word overlap, Soundex, Levenshtein, trained phrases
- Quick actions: time, battery, help, stop
- Redial / call back last person
- Time-aware greetings
- Contact disambiguation when names are similar
- Teach IRIS corrections after failed matches
- Voice + touch confirmation before calling
- Encrypted profiles, logs, biometric auth for export/import
- Lock screen operation with foreground service
- Bluetooth/wired/USB headset audio routing
- Quick Settings tile

---

## Phase 1: Personal Intelligence (v1.2.0) — Pure Java, no models

### 1.1 Contact Labels & Relationships
**What:** Train relationship labels like "wife", "brother", "boss", "doctor".
**Commands enabled:**
- "Call my wife" → resolves to labeled contact
- "Ring my brother" → resolves to labeled contact
- "Phone the doctor" → resolves to labeled contact

**Storage:** New `relationships` map in ProfileStore JSON:
```json
{
  "relationships": {
    "wife": { "name": "Priya", "number": "+91..." },
    "brother": { "name": "Rahul", "number": "+91..." },
    "office": { "name": "Office", "number": "011-..." }
  }
}
```

**UI:** In profile details dialog, add "Set label" option (wife, husband, brother, sister, mom, dad, boss, doctor, office, friend, custom).

**Files:** ProfileStore.java, IrisListeningService.java (pattern matching), MainActivity.java (label UI)

### 1.2 Call History Queries
**What:** Ask IRIS about past calls.
**Commands enabled:**
- "Who did I call last?" → speaks last called contact
- "Who did I call today?" → lists today's calls
- "How many times did I call Mom?" → speaks count
- "When did I last call Rahul?" → speaks relative time ("2 hours ago")

**Storage:** Already tracked in ProfileStore (callCount, lastCalled per entry).

**Files:** IrisListeningService.java (new HISTORY_PATTERN + handleHistory method)

### 1.3 Smart Call Timing
**What:** Context-aware call safety checks.
**Behavior:**
- Late night (11 PM – 6 AM): "It's 2 AM. Are you sure you want to call Mom?"
- DND active: "Do Not Disturb is on. Still want to call?"
- Repeated calls: "You've called Rahul 3 times today. Try again?"

**Implementation:** Check `NotificationManager.getCurrentInterruptionFilter()` for DND. Check `Calendar.HOUR_OF_DAY` for late night. Check `ProfileStore.Entry.callCount` with date filter.

**Files:** IrisListeningService.java (in requestCallConfirmation)

### 1.4 Location-Aware Greetings (optional, needs permission)
**What:** "You're at home" / "You seem to be driving" awareness.
**Skip for now** — needs ACCESS_FINE_LOCATION, adds complexity. Revisit later.

---

## Phase 2: Conversational Intelligence (v1.3.0) — Pure Java

### 2.1 Multi-Step Conversation Memory
**What:** IRIS remembers context within a session.
**Behavior:**
- "Call Mom" → "Which Mom? Home or Office?" → "Home" → calls
- "Call Rahul" → confirmed → call ends → "How was the call?"
- "Call him" → resolves "him" to last mentioned male contact
- "Cancel. Try his office number" → switches to alternate number

**Implementation:** Session context object holding last mentioned contact, last action, last gender reference.

**Files:** IrisListeningService.java (new SessionContext inner class)

### 2.2 Follow-Up Actions
**What:** Suggest next actions after events.
**Behavior:**
- After failed call: "Mom didn't answer. Try again in 5 minutes?" → sets delayed retry
- After missed call notification: "Mom tried to call. Call back?"
- After training: "Want to test it now?"

**Files:** IrisListeningService.java, possibly a new NotificationActionReceiver

### 2.3 Proactive Suggestions
**What:** IRIS suggests actions based on patterns.
**Behavior:**
- "You usually call Mom around this time"
- "It's been a week since you called Dad"
- Show suggestions as notification or in Assistant tab

**Implementation:** Analyze ProfileStore call patterns (day of week, time of day).

**Files:** IrisListeningService.java or new RoutineSuggester.java, MainActivity.java (UI)

---

## Phase 3: Audio Intelligence (v1.4.0) — Small TFLite models

### 3.1 Offline Keyword Spotting (~1 MB model)
**What:** Replace Android's SpeechRecognizer for command recognition with a custom lightweight keyword spotter.
**Why:** Faster, always-offline, lower battery, no Google dependency.
**Vocabulary:** call, dial, phone, ring, stop, time, battery, help, cancel, yes, no + contact names
**Model:** Custom-trained keyword CNN or use Google's speech_commands model adapted for IRIS vocabulary.
**Files:** New KeywordSpotter.java, IrisListeningService.java

### 3.2 Emotion/Urgency Detection (~2 MB model)
**What:** Detect voice emotion (calm, stressed, panicked) from audio features.
**Behavior:**
- Panicked voice: "You sound urgent. Emergency call?" → offer 112/911
- Stressed: skip confirmation, call immediately
- Calm: normal flow

**Model:** Small emotion classifier trained on speech emotion datasets (RAVDESS, IEMOCAP).
**Files:** New EmotionDetector.java, IrisListeningService.java

### 3.3 Noise-Adaptive Listening
**What:** Detect environment type from ambient audio and adjust sensitivity.
**Environments:** Quiet room, street, car, crowd, wind
**Behavior:**
- Noisy: tighten wake threshold, boost VAD sensitivity
- Quiet: relax thresholds for lower false negatives
- Show environment label: "🚗 Car mode active"

**Implementation:** Extend WakeWordEngine's noise floor tracking + small environment classifier.
**Files:** WakeWordEngine.java, IrisListeningService.java

---

## Phase 4: SMS & Notification Intelligence (v2.0.0) — Needs permissions

### 4.1 Read Last Message
**Commands:** "Read my last message", "Who texted me?"
**Permissions:** READ_SMS
**Behavior:** Speaks sender name + message content via TTS

### 4.2 After Failed Call
**Behavior:** "Mom didn't answer. Send a message instead?"
**Permissions:** SEND_SMS (optional — can just open messaging app)

### 4.3 Notification Summary
**Commands:** "Any notifications?", "What did I miss?"
**Permissions:** BIND_NOTIFICATION_LISTENER_SERVICE
**⚠️ Play Store concern:** This is a sensitive permission. May trigger Play Protect review.

---

## Phase 5: On-Device LLM (v3.0.0) — Large model, major feature

### 5.1 Natural Language Understanding
**What:** Full conversational AI using on-device LLM (Gemma 2B / Phi-3 Mini / TinyLlama).
**Commands:** Anything natural language.
- "Hey, can you call my brother? The one in Bangalore, not Delhi"
- "I need to talk to someone at the office"
- "Who should I wish happy birthday today?"

**Cost:** 1-2 GB model, 2-4s inference, significant battery.
**Framework:** MediaPipe LLM Inference API or ONNX Runtime.
**Files:** New LlmEngine.java, intent extraction pipeline

### 5.2 Multilingual Auto-Detection
**What:** Auto-detect speech language without user setting.
**Model:** Language ID model (~500 KB TFLite) classifies audio as Hindi/English/Tamil/etc.
**Behavior:** Automatically set SpeechRecognizer language. "I detected Hindi. Switching."

### 5.3 Voice Cloning for TTS (far future)
**What:** IRIS speaks in a customized voice instead of Android TTS robot voice.
**Model:** Small voice synthesis model trained on user-selected voice.

---

## Implementation Priority

| Priority | Feature | Version | Effort | Impact |
|----------|---------|---------|--------|--------|
| 🔴 NOW | Contact labels & relationships | 1.2.0 | Low | High |
| 🔴 NOW | Call history queries | 1.2.0 | Low | High |
| 🔴 NOW | Smart call timing | 1.2.0 | Low | Medium |
| 🟡 NEXT | Multi-step conversation | 1.3.0 | Medium | High |
| 🟡 NEXT | Follow-up actions | 1.3.0 | Medium | Medium |
| 🟡 NEXT | Proactive suggestions | 1.3.0 | Medium | Medium |
| 🟢 LATER | Offline keyword spotting | 1.4.0 | High | High |
| 🟢 LATER | Emotion detection | 1.4.0 | High | Medium |
| 🟢 LATER | Noise-adaptive listening | 1.4.0 | Medium | Medium |
| 🔵 FUTURE | Read SMS | 2.0.0 | Low | High |
| 🔵 FUTURE | Notification summary | 2.0.0 | Medium | High |
| ⚪ FAR | On-device LLM | 3.0.0 | Very High | Very High |
| ⚪ FAR | Multilingual auto-detect | 3.0.0 | High | Medium |
| ⚪ FAR | Voice cloning TTS | 3.0.0 | Very High | Medium |

---

## Versioning Reference

- Major feature → +1.0.0
- Small feature / small bug → +0.1.0
- Build failure fix OR issue-fix → +0.0.1
- Cosmetic / UI-only polish → +0.0.1
- versionCode +1 on every release
- okhttp pinned at 4.12.0 (not currently used)
