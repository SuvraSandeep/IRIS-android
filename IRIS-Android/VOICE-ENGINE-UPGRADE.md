# IRIS Robust Voice Engine — Sherpa-ONNX Integration

## The Problem

Current IRIS uses 3 fragile components:
1. **Wake detection:** Custom Goertzel+DTW — triggers on random sounds
2. **Speech recognition:** Android SpeechRecognizer — unreliable, hangs, some OEMs broken
3. **TTS:** Android TextToSpeech — robotic, fails silently on many devices

## The Solution: Sherpa-ONNX

**Sherpa-ONNX** (by k2-fsa/Next-gen Kaldi) is a single open-source library that provides:

| Feature | What it does | Model size |
|---|---|---|
| **Streaming STT** | Real-time speech-to-text, works while user speaks | ~15 MB |
| **Keyword Spotting** | Neural wake word detection (replaces our DTW) | ~5 MB |
| **TTS (Piper voices)** | Natural-sounding offline text-to-speech | ~20 MB |
| **VAD** | Voice Activity Detection (Silero VAD) | ~2 MB |
| **Speaker ID** | Speaker verification/identification | ~5 MB |

**Total: ~47 MB of models, all fully offline, 100% free (Apache 2.0 license)**

### Why Sherpa-ONNX?

- ✅ **100% free** — Apache 2.0 license, no API keys, no subscriptions
- ✅ **100% offline** — no internet needed, ever
- ✅ **All-in-one** — STT + TTS + wake word + VAD + speaker ID in one library
- ✅ **Android SDK** — Java API, published on JitPack
- ✅ **20+ languages** — English, Hindi, and more
- ✅ **Actively maintained** — 15k+ GitHub stars, frequent releases
- ✅ **Small footprint** — runs on phones with 2GB RAM

## Architecture: Before vs After

### Before (fragile)
```
Wake: Custom Goertzel+DTW (12 features, easy false triggers)
STT:  Android SpeechRecognizer (unreliable, OEM-dependent)
TTS:  Android TextToSpeech (robotic, fails silently)
VAD:  Custom RMS threshold (misses quiet speech, triggers on noise)
```

### After (robust)
```
Wake: Sherpa-ONNX Keyword Spotter (neural, custom keywords)
STT:  Sherpa-ONNX Streaming ASR (Zipformer/Paraformer model)
TTS:  Sherpa-ONNX Piper TTS (natural VITS neural voice)
VAD:  Sherpa-ONNX Silero VAD (state-of-the-art, tiny model)
Speaker: Sherpa-ONNX Speaker ID (replaces our custom TFLite)
```

## Gradle Dependency

```groovy
dependencies {
    // Sherpa-ONNX — all-in-one voice engine
    implementation 'com.k2fsa.sherpa:sherpa-onnx-android:1.10.37'
    
    // Remove: TFLite (no longer needed for speaker verification)
    // implementation 'org.tensorflow:tensorflow-lite:2.16.1'
}
```

**One dependency replaces both Android SpeechRecognizer AND TFLite.**

## Models Needed (downloaded once, stored in assets)

| Model | File | Size | Source |
|---|---|---|---|
| STT (English) | `sherpa-onnx-streaming-zipformer-en-20M` | ~20 MB | [HuggingFace](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) |
| STT (Hindi) | `sherpa-onnx-streaming-zipformer-hi` | ~20 MB | HuggingFace |
| TTS (English) | `en_US-amy-medium.onnx` + `.json` | ~20 MB | [Piper voices](https://huggingface.co/rhasspy/piper-voices) |
| TTS (Hindi) | `hi_IN-swara-medium.onnx` + `.json` | ~20 MB | Piper voices |
| VAD | `silero_vad.onnx` | ~2 MB | Included with sherpa-onnx |
| Speaker ID | `3dspeaker_speech_eres2net_base.onnx` | ~5 MB | [3D-Speaker](https://huggingface.co/csukuangfj/3dspeaker) |

**Total: ~87 MB** (can be reduced to ~42 MB by using only English)

### Model download strategy
- Models are NOT in the APK (too large)
- On first launch: "IRIS needs to download voice models (~40 MB). This is a one-time setup."
- Download from GitHub Releases or HuggingFace
- Store in app's internal storage
- Works fully offline after download

## New Class: `VoiceEngine.java`

Replaces: `WakeWordEngine.java` (for wake), Android `SpeechRecognizer` (for STT), Android `TextToSpeech` (for TTS), `SpeakerVerifier.java` + `MelSpectrogram.java` (for speaker ID)

```java
public final class VoiceEngine {
    // Initialization
    void init(Context context)                    // loads all models
    boolean isReady()                              // all models loaded?
    void close()                                   // release resources
    
    // Wake word detection
    void startWakeDetection(WakeListener listener) // neural keyword spotting
    void stopWakeDetection()
    
    // Speech-to-text (streaming)
    void startListening(SttListener listener)      // real-time transcription
    void stopListening()
    
    // Text-to-speech
    void speak(String text, TtsListener listener)  // natural Piper voice
    void stopSpeaking()
    boolean isSpeaking()
    
    // Speaker verification
    float[] extractSpeakerEmbedding(short[] audio) // voiceprint
    float verifySpeaker(short[] audio, float[] voiceprint) // similarity
    
    // VAD
    boolean isSpeech(short[] audio)                // is this speech?
}
```

## Migration Plan

### What stays
- `ProfileStore.java` — contact/phrase storage (unchanged)
- `MemoryStore.java` — memory system (unchanged)
- `MemoryParser.java` — NL parsing (unchanged)
- `BehaviorAnalyzer.java` — pattern detection (unchanged)
- `SecureStore.java` — encryption (unchanged)
- `LogStore.java` — logging (unchanged)
- `AppSettings.java` — settings (unchanged)
- `IrisOrbView.java` — UI (unchanged)
- All layouts, drawables, resources (unchanged)

### What gets replaced
| Old | New | Reason |
|---|---|---|
| `WakeWordEngine.java` | `VoiceEngine.startWakeDetection()` | Neural > Goertzel+DTW |
| `SpeechRecognizer` (Android) | `VoiceEngine.startListening()` | Always works, no OEM issues |
| `TextToSpeech` (Android) | `VoiceEngine.speak()` | Natural voice, never fails |
| `SpeakerVerifier.java` | `VoiceEngine.verifySpeaker()` | Same quality, one less dependency |
| `MelSpectrogram.java` | (removed) | Sherpa handles internally |
| TFLite dependency | (removed) | Sherpa-ONNX replaces it |

### What gets modified
| File | Changes |
|---|---|
| `IrisListeningService.java` | Use VoiceEngine instead of WakeWordEngine, SpeechRecognizer, TextToSpeech |
| `MainActivity.java` | Use VoiceEngine for training capture, dry-run tests, speak-to-add-memory |
| `app/build.gradle` | Replace TFLite with sherpa-onnx dependency |

## Version

- versionName: 4.0.0 (major feature — complete voice engine replacement)
- versionCode: 188
- okhttp: pinned at 4.12.0 (not applicable)

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Sherpa-ONNX APK size increase (~8 MB native libs) | Acceptable for the quality improvement |
| Model download on first launch (~40 MB) | Show progress, WiFi recommendation |
| ONNX Runtime compatibility | Well-tested on Android 8+, same as our minSdk |
| Migration complexity | Keep old code as fallback, feature-flag the switch |

## Implementation Steps

1. Add sherpa-onnx dependency, remove TFLite
2. Create VoiceEngine.java wrapping all Sherpa APIs
3. Create model downloader (first-launch setup)
4. Replace wake detection in IrisListeningService
5. Replace speech recognition in IrisListeningService
6. Replace TTS in IrisListeningService  
7. Replace speaker verification
8. Update training to use VoiceEngine
9. Test and tune thresholds
10. Remove old WakeWordEngine, MelSpectrogram, SpeakerVerifier (keep as legacy fallback)
