# IRIS v6.0.0 — Conversational AI Agent

## The Transformation

**From:** A voice dialer that only understands "Call X"
**To:** A conversational AI assistant that chats naturally AND can call your contacts, check info, and manage your memory — all through natural conversation.

Example conversation:
```
You:  "Hey Nova"
IRIS: "Hi Sandeep, what's up?"
You:  "I'm bored, who did I talk to today?"
IRIS: "You called Rahul this morning and Mom around noon. Want to call someone?"
You:  "Yeah call my brother"
IRIS: "Calling Rahul now." → [places call]

You:  "Hey Nova, what time is it and remind me my wife's name"
IRIS: "It's 4:30 PM. Your wife is Priya. Anything else?"
```

---

## Two-Layer Architecture

### Layer 1: Robust Voice I/O (the ears and mouth)

| Component | Technology | Role |
|---|---|---|
| **Wake word** | Vosk grammar mode | Neural detection of your phrase only |
| **Speech-to-text** | Vosk full recognizer | Convert your speech to text |
| **Text-to-speech** | Android TextToSpeech | Speak responses back (works on your phone) |
| **Speaker check** | Vosk speaker vectors | Verify it's your voice |

### Layer 2: Conversational Brain (the intelligence)

| Component | Technology | Role |
|---|---|---|
| **LLM** | Gemma 3 / FunctionGemma via MediaPipe | Understand and respond conversationally |
| **Function calling** | AI Edge Function Calling SDK | Translate chat into actions (call, check time, etc.) |
| **Context** | MemoryStore + conversation history | Personalized, context-aware responses |

---

## How the Agent Works

```
You speak → Vosk STT → text
                        ↓
              ┌─────────────────────┐
              │   Gemma LLM Agent   │
              │  (with your memory  │
              │   as context)       │
              └─────────────────────┘
                        ↓
         ┌──────────────┴──────────────┐
         ↓                             ↓
   Function call?                Just chat?
         ↓                             ↓
   Execute tool:                 Generate reply:
   - callContact(name)           "I'm doing great,
   - checkTime()                  how about you?"
   - checkBattery()                    ↓
   - recallMemory(topic)         Android TTS speaks it
   - saveMemory(fact)
         ↓
   Speak result via TTS
```

### The LLM's Tools (functions it can call)

```java
// These are registered with FunctionGemma / AI Edge FC SDK:

callContact(String name)          // "call my brother" → callContact("Rahul")
checkTime()                       // "what time is it"
checkBattery()                    // "how much battery"
saveMemory(String fact)           // "remember I like tea"
recallMemory(String topic)        // "what's my wife's name"
listRecentCalls()                 // "who did I call today"
setReminder(String what, time)    // "remind me to call mom at 6"
stopListening()                   // "go to sleep"
```

The LLM decides which tool to call based on what you say — this is **function calling**, the same tech that powers ChatGPT plugins, running fully on-device.

---

## Model Choice

### Option A: Gemma 3 1B (recommended)
- Size: ~550 MB (int4 quantized)
- Runs on phones with 4GB+ RAM
- Good conversation quality
- Supports function calling
- Via MediaPipe LLM Inference API

### Option B: FunctionGemma 270M (lightweight)
- Size: ~150 MB
- Runs on almost any phone
- Specialized for function calling (less chatty)
- Best for "understand command → act" not deep conversation

### Option C: Gemma 3n E2B (best quality)
- Size: ~1.5 GB
- Needs 6GB+ RAM
- Best conversation + multimodal
- For flagship phones

**Recommendation:** Start with **Gemma 3 1B** — good balance of conversation quality and size. Since you don't care about APK size, we bundle it (or download on first launch).

---

## Dependencies

```groovy
dependencies {
    // Voice I/O
    implementation 'com.alphacephei:vosk-android:0.3.75'
    
    // On-device LLM
    implementation 'com.google.mediapipe:tasks-genai:0.10.24'
}
```

Both are free, both run 100% on-device.

---

## Models (downloaded at build time by CI, bundled in APK)

| Model | Size | Purpose |
|---|---|---|
| `vosk-model-small-en-us-0.15` | 40 MB | STT + wake word |
| `gemma3-1b-it-int4.task` | 550 MB | Conversational LLM |
| `vosk-model-spk-0.4` | 13 MB | Speaker verification |

**Total APK: ~650 MB.** Large, but you said you don't care about size. This is the tradeoff for a real on-device AI.

**Alternative:** Download models on first launch (APK stays ~10 MB, downloads 650 MB once over WiFi).

---

## New Classes

| File | Purpose |
|---|---|
| `VoskEngine.java` | Vosk STT + wake + speaker (already created, needs model) |
| `TimedRecorder.java` | Timed recording for training (already created) |
| `LlmAgent.java` | Gemma LLM wrapper with function calling |
| `AgentTools.java` | The functions the LLM can call (call, time, memory, etc.) |
| `ConversationManager.java` | Manages dialog history and context |

## Classes Modified

| File | Change |
|---|---|
| `IrisListeningService.java` | Route recognized text through LlmAgent instead of regex |
| `MainActivity.java` | Add conversation view, chat history display |
| CI workflow | Download Vosk + Gemma models, bundle in APK |

## Classes Removed

| File | Reason |
|---|---|
| `WakeWordEngine.java` | Replaced by Vosk grammar mode |
| Regex CALL_PATTERN etc. | Replaced by LLM function calling |

---

## Conversation Flow in Detail

### System prompt (gives IRIS its personality + tools)
```
You are IRIS, a personal assistant on Sandeep's phone.
You can call contacts, check time/battery, and remember facts.
Be warm, brief, and natural. When the user wants an action,
call the right function. Otherwise, just chat.

Known about the user: {memory injected here}
Recent calls: {call history injected here}

Available functions: callContact, checkTime, checkBattery,
saveMemory, recallMemory, listRecentCalls, stopListening.
```

### Turn handling
1. Vosk transcribes: "call my brother"
2. Inject into LLM with system prompt + memory context
3. LLM outputs: `callContact("Rahul")` (resolved via memory: brother=Rahul)
4. Execute: show confirmation → place call
5. LLM also generates: "Calling Rahul now."
6. TTS speaks it

### For pure chat
1. Vosk: "how are you"
2. LLM: "I'm doing well, thanks for asking! What can I help with?"
3. TTS speaks it — no function called

---

## UI: New Conversation View

The Assistant tab becomes a **chat interface**:
```
┌─────────────────────────────────────┐
│  🎯  IRIS                            │
│                                      │
│  ┌─────────────────────────────┐    │
│  │ IRIS: Good evening, Sandeep! │    │
│  └─────────────────────────────┘    │
│              ┌──────────────────┐    │
│              │ You: call rahul  │    │
│              └──────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │ IRIS: Calling Rahul now 📞  │    │
│  └─────────────────────────────┘    │
│                                      │
│         ╭─────────╮                  │
│         │  ORB    │  ← tap to talk  │
│         ╰─────────╯                  │
└─────────────────────────────────────┘
```

Shows the conversation like a messaging app. The orb is the mic button.

---

## Implementation Phases

### Phase A: Robust Voice (v5.x — do first)
1. CI downloads Vosk model, bundles in APK
2. Wake detection via Vosk grammar mode
3. STT via Vosk
4. Fix TTS to speak reliably
5. Training via TimedRecorder
**Result: rock-solid wake + speak + recognize**

### Phase B: Conversational Agent (v6.0.0)
6. Add MediaPipe tasks-genai dependency
7. CI downloads Gemma model, bundles in APK
8. Create LlmAgent with function calling
9. Create AgentTools (call, time, memory functions)
10. Route STT text through LlmAgent
11. Chat UI in Assistant tab
**Result: conversational AI that can call contacts**

---

## Why This Is The Right Architecture

This is **exactly** how modern AI assistants work:
- **Voice I/O** separate from **intelligence** (Vosk ears + Gemma brain + Android mouth)
- **Function calling** lets the LLM take real actions (industry standard — ChatGPT, Gemini, Claude all use it)
- **On-device** = private, offline, no API keys, no subscriptions
- **Memory as context** = personalized responses

FunctionGemma was literally built by Google for this exact use case: "translate natural language into executable actions" on-device.

---

## Version Plan

| Version | Ships |
|---|---|
| **v5.0.0** (in progress) | Vosk voice engine, reliable wake+STT+TTS |
| **v6.0.0** | Gemma LLM agent, function calling, chat UI |

- v6.0.0: major feature → from 5.x
- versionCode continues incrementing
- okhttp: pinned at 4.12.0 (not applicable)

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Gemma too slow on old phones | Offer FunctionGemma 270M as lite option |
| 650 MB APK too big for some | Download-on-first-launch option |
| LLM hallucinates wrong contact | Always confirm before calling |
| Model needs 4GB+ RAM | Detect RAM, fall back to regex mode on low-end |

---

## Decision Needed

1. **Which model?** Gemma 3 1B (550 MB, good chat) or FunctionGemma 270M (150 MB, action-focused)?
2. **Bundle or download?** Bundle in APK (huge APK) or download on first launch (small APK, one-time download)?
3. **Do Phase A (voice) first, or both together?**

My recommendation: **Gemma 3 1B, download on first launch, do Phase A first** to get voice solid, then Phase B for the brain.
