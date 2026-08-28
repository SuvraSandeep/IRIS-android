# IRIS v5.0.0 — Complete Voice Engine & Training Overhaul

## Problem Statement

The current IRIS has three fundamental problems:
1. **Training doesn't work reliably** — VAD-based capture is unpredictable
2. **IRIS doesn't speak** — Android TTS is broken on the device
3. **Wake detection triggers on random sounds** — DTW is too fragile

All three stem from using DIY/built-in Android components that are unreliable.

## Solution: Replace ALL voice components with Vosk

**Vosk** is the best choice because:
- ✅ 100% free, open source (Apache 2.0)
- ✅ 100% offline — no API keys, no internet after model download
- ✅ Available on Maven Central: `com.alphacephei:vosk-android:0.3.75`
- ✅ Includes: STT + speaker identification + grammar mode
- ✅ Small model: 50 MB (English) or 20 MB (small English)
- ✅ Supports 20+ languages including Hindi and Indian English
- ✅ Battle-tested — used in production apps worldwide

For TTS, we keep Android TTS but fix it properly with a **test-on-startup** approach.

---

## Part 1: New Training Module

### Design Philosophy
- **Timed recording, not VAD** — record exactly 3 seconds, no guessing
- **Visual countdown** — 3, 2, 1, 🔴 NOW
- **Live waveform** — user sees their voice being captured
- **Audio feedback** — beep before, chime after
- **Only 3 samples** — enough for matching, less tedious
- **Clear progress** — big dots, step number, completion celebration

### Training Flow (User's Perspective)

```
╔══════════════════════════════════════╗
║  🎙️  TRAIN YOUR WAKE PHRASE         ║
║                                      ║
║  Type what IRIS should listen for:   ║
║  ┌──────────────────────────┐       ║
║  │  Nova                    │       ║
║  └──────────────────────────┘       ║
║                                      ║
║  You'll say it 3 times so IRIS       ║
║  learns your voice.                  ║
║                                      ║
║  [ Begin Training → ]                ║
╚══════════════════════════════════════╝
              ↓ tap Begin
╔══════════════════════════════════════╗
║  Sample 1 of 3       ● ○ ○          ║
║                                      ║
║  Get ready to say "Nova"...          ║
║                                      ║
║         ╭─────────╮                  ║
║         │   3     │  ← big number   ║
║         ╰─────────╯                  ║
╚══════════════════════════════════════╝
              ↓ countdown
╔══════════════════════════════════════╗
║  Sample 1 of 3       ● ○ ○          ║
║                                      ║
║  🔴  SAY "Nova" NOW                 ║
║                                      ║
║  ▓▓▓▓▓▓▓░░░░░░░░░  ← live level    ║
║  ▓▓▓▓▓▓▓▓▓▓░░░░░░                  ║
║  ▓▓▓▓▓▓▓▓▓▓▓▓░░░░  ← 3 sec timer  ║
║                                      ║
║  [ Cancel ]                          ║
╚══════════════════════════════════════╝
              ↓ 3 seconds pass
╔══════════════════════════════════════╗
║  Sample 1 of 3       ● ○ ○          ║
║                                      ║
║  ✅  Perfect! Clear audio captured.  ║
║                                      ║
║  Next sample in 2 seconds...         ║
║                                      ║
╚══════════════════════════════════════╝
              ↓ auto-advance
╔══════════════════════════════════════╗
║  Sample 2 of 3       ● ● ○          ║
║         ╭─────────╮                  ║
║         │   3     │                  ║
║         ╰─────────╯                  ║
╚══════════════════════════════════════╝
              ↓ ... repeat for 3 ...
╔══════════════════════════════════════╗
║  🎉  TRAINING COMPLETE!             ║
║                                      ║
║  IRIS now responds to "Nova"         ║
║  spoken in YOUR voice.               ║
║                                      ║
║  [ 🧪 Test Now ]    [ ✅ Done ]     ║
╚══════════════════════════════════════╝
```

### Technical: Timed Recording Implementation

```java
// Instead of WakeWordEngine.captureOne() with unpredictable VAD:
class TimedRecorder {
    static short[] record(int durationMs, LevelCallback onLevel) {
        AudioRecord mic = new AudioRecord(..., 16000, MONO, PCM_16BIT, ...);
        mic.startRecording();
        short[] buffer = new short[16000 * durationMs / 1000]; // exact size
        int offset = 0;
        long startTime = System.currentTimeMillis();
        while (offset < buffer.length) {
            int read = mic.read(buffer, offset, Math.min(512, buffer.length - offset));
            offset += read;
            // Report live audio level for waveform display
            onLevel.onLevel(rms(buffer, offset - read, read));
        }
        mic.stop();
        mic.release();
        return buffer; // Exactly durationMs of audio, guaranteed
    }
}
```

**Why this is better:**
- Recording ALWAYS works — no VAD to confuse
- ALWAYS takes exactly 3 seconds — predictable
- Live level callback drives the waveform UI
- No background thread magic — simple, debuggable

---

## Part 2: Fix TTS (IRIS Must Speak)

### Current problem
Android TextToSpeech initializes asynchronously. On some devices:
- The callback never fires
- The engine is ready but `speak()` returns error
- The language isn't available
- Audio is routed to the wrong output

### Solution: Test TTS on startup, report status

```java
// In IrisListeningService.onCreate():
textToSpeech = new TextToSpeech(this, status -> {
    ttsReady = status == TextToSpeech.SUCCESS;
    if (ttsReady) {
        textToSpeech.setLanguage(Locale.getDefault());
        // TEST IT: speak a silent utterance to warm up the engine
        textToSpeech.speak(" ", TextToSpeech.QUEUE_FLUSH, null, "warmup");
        LogStore.append(this, "TTS", "Ready: " + textToSpeech.getDefaultEngine());
    } else {
        LogStore.append(this, "TTS", "FAILED to initialize (status " + status + ")");
        // Try system default engine explicitly
        String engine = textToSpeech.getDefaultEngine();
        LogStore.append(this, "TTS", "Default engine: " + engine);
    }
});
```

Also add a TTS test button in Settings so you can verify it works:
```
[ 🔊 Test Voice ] → speaks "Hello, I am IRIS. I can hear and speak."
```

### Fallback chain
1. Android TextToSpeech (default engine)
2. If that fails → try Google TTS engine explicitly
3. If that fails → use ToneGenerator beeps for critical feedback
4. Log everything so we can debug

---

## Part 3: Better Wake Detection

### Current problem
DTW with 10 Goertzel bands is primitive. Even with tightened thresholds, it can't distinguish between similar sounds.

### Solution: Use Vosk grammar mode for wake detection

Vosk supports a **grammar mode** where you give it a list of words to listen for. This is a **neural speech recognizer** constrained to your wake phrase — dramatically more accurate than DTW.

```java
// Vosk grammar mode for wake word
Recognizer recognizer = new Recognizer(model, 16000,
    "[\"nova\", \"[unk]\"]"); // Only recognize "nova" or unknown

// In the audio loop:
if (recognizer.acceptWaveForm(buffer, count)) {
    String result = recognizer.getResult();
    // result: {"text": "nova"} or {"text": ""}
    if (result.contains("nova")) {
        // WAKE DETECTED — with neural accuracy!
    }
}
```

**Why this is massively better:**
- Neural speech model vs 10-frequency-band comparison
- Understands speech, not just spectral patterns
- Rejects non-speech sounds (clicks, taps, music) because it's a speech model
- Supports any word/phrase — not just acoustic patterns
- Same model also does command recognition

### Keep DTW as secondary check
After Vosk detects the wake word, run DTW on the audio as a **speaker similarity check** — this acts as a lightweight voice gate even without a full speaker model.

---

## Part 4: Vosk Integration Architecture

### Dependencies

```groovy
dependencies {
    // Vosk — offline STT + speaker ID (Maven Central, no JitPack needed)
    implementation 'com.alphacephei:vosk-android:0.3.75'
    
    // Remove sherpa-onnx (replaced by Vosk)
    // implementation 'com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5'
}
```

### Models (bundled in assets or downloaded on first launch)

| Model | Size | Purpose |
|---|---|---|
| `vosk-model-small-en-us-0.15` | 40 MB | English STT + wake word grammar |
| `vosk-model-small-hi-0.22` | 42 MB | Hindi STT (optional) |
| `vosk-model-spk-0.4` | 13 MB | Speaker identification/verification |

**Option A: Bundle in APK** — app is ~55 MB (acceptable)
**Option B: Download on first launch** — app is ~5 MB, downloads 40 MB once

### What Vosk replaces

| Component | Before | After (Vosk) |
|---|---|---|
| Wake detection | WakeWordEngine (Goertzel+DTW) | Vosk grammar mode (neural) |
| Command STT | Android SpeechRecognizer | Vosk streaming recognizer |
| Speaker ID | SpeakerVerifier (no-op placeholder) | Vosk speaker vectors |
| Training capture | WakeWordEngine.captureOne() (VAD) | TimedRecorder (3 sec fixed) |

### What stays

| Component | Why |
|---|---|
| Android TextToSpeech | Vosk doesn't do TTS. Android TTS is the only free option. |
| WakeWordEngine DTW | Kept as secondary speaker voice check after Vosk wake |
| ProfileStore | Unchanged — stores contacts, phrases, voiceprint |
| MemoryStore | Unchanged — stores memories |
| All UI layouts | Unchanged (except training views) |

---

## Part 5: New Classes

| File | Lines | Purpose |
|---|---|---|
| `TimedRecorder.java` | ~80 | Fixed-duration audio recording with live level callback |
| `VoskEngine.java` | ~250 | Vosk wrapper: init, wake grammar, streaming STT, speaker vectors |

### Classes to remove or deprecate
| File | Status |
|---|---|
| `VoiceEngine.java` | Remove (replaced by VoskEngine) |
| `MelSpectrogram.java` | Remove (Vosk handles features internally) |
| `SpeakerVerifier.java` | Keep but delegate to Vosk speaker vectors |
| `WakeWordEngine.java` | Keep for DTW speaker check only |

---

## Part 6: Training UI Redesign

### New `view_training.xml` structure

```
┌─ 🎙️ WAKE PHRASE ────────────────────┐
│                                      │
│  [Status: not trained / trained]     │
│  [Input field for phrase]            │
│                                      │
│  ── TRAINING WIZARD ──               │
│  [Countdown display: 3, 2, 1, NOW]  │
│  [Live audio level bar]             │
│  [Step dots: ● ● ○]                │
│  [Quality feedback per sample]       │
│                                      │
│  [Begin/Retrain]  [Test]            │
└──────────────────────────────────────┘

┌─ 📇 CONTACT COMMANDS ───────────────┐
│  [Same countdown-based flow]         │
│  [But using Vosk STT for text]      │
└──────────────────────────────────────┘

┌─ 🧪 TEST & TRANSFER ────────────────┐
│  [Test wake]  [Test command]         │
│  [🔊 Test Voice]                    │
│  [Export]  [Import]                  │
└──────────────────────────────────────┘
```

### Live Audio Level Bar

A horizontal bar that bounces with mic input during recording:
```
▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░  (quiet)
▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░  (speaking)
▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  (loud)
```

Implemented as a simple `View` with `setLevel(float)` that draws a colored rectangle.

---

## Part 7: TTS Debugging in Settings

Add to Settings tab:
```
┌─ 🔊 VOICE OUTPUT ──────────────────┐
│  TTS Engine: Google TTS             │
│  Status: ✅ Ready                   │
│  Language: en-IN                    │
│                                      │
│  [ 🔊 Test Voice ]                  │
│  Speaks: "Hello, I am IRIS."       │
└──────────────────────────────────────┘
```

If TTS fails, show:
```
│  Status: ❌ Not working             │
│  Try: Settings → Apps → Google TTS  │
│       → Enable / Update             │
```

---

## Implementation Order

| Step | What | Priority |
|------|------|----------|
| 1 | Add Vosk dependency, remove sherpa-onnx | Build |
| 2 | Create TimedRecorder.java | Training |
| 3 | Rewrite training to use TimedRecorder | Training |
| 4 | Fix TTS with warmup + test button | Speech |
| 5 | Create VoskEngine.java | Core |
| 6 | Replace wake detection with Vosk grammar | Core |
| 7 | Replace Android SpeechRecognizer with Vosk STT | Core |
| 8 | Add Vosk speaker vectors for verification | Security |
| 9 | Bundle or download Vosk models | Packaging |
| 10 | Training UI with countdown + waveform | Polish |

## Version

- versionName: 5.0.0 (major — complete voice engine overhaul)
- versionCode: 191
- okhttp: pinned at 4.12.0 (not applicable)

## Model Download Strategy

Since Vosk models are 40-50 MB, we have two options:

### Option A: Include in APK (recommended for now)
- Download the model zip, extract into `app/src/main/assets/`
- APK size: ~55 MB (acceptable — WhatsApp is 200 MB)
- Zero setup for user — just install and use

### Option B: Download on first launch
- APK size: ~5 MB
- First launch shows: "Downloading voice model (40 MB)..."
- Better for Play Store size limits later

For testing, **Option A** is better because it eliminates one more thing that can go wrong.

**However**, we can't bundle 40 MB in the GitHub zip (too large). So we'll use Option B for CI builds and include download instructions.
