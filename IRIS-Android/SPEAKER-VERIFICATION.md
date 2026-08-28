# IRIS Speaker Verification — v1.0.0

## Goal

IRIS should only respond to the **owner's voice**. Anyone else saying the wake phrase or a call command should be silently ignored. This makes IRIS a personal AI that recognizes not just *what* is said but *who* is saying it.

## Current State

- Wake detection: Goertzel DSP + DTW pattern matching → matches sound pattern, no speaker identity
- Speech recognition: Android SpeechRecognizer → text only, no speaker info
- Anyone who mimics the wake phrase can trigger IRIS

## Architecture: On-Device Neural Speaker Embedding

### Model Choice

**ECAPA-TDNN (Emphasized Channel Attention, Propagation and Aggregation TDNN)**
- State of the art for speaker verification
- Small variant: ~2-5 MB TFLite model, 11.6M FLOPS
- Outputs a 192-dimensional speaker embedding vector
- Trained on VoxCeleb1/2 (7000+ speakers)
- Text-independent: works regardless of what words are spoken

### How It Works

```
Audio → Mel Spectrogram → ECAPA-TDNN Model → 192-dim Embedding Vector
                                                    ↓
                                             Cosine Similarity
                                                    ↓
                                           Owner? (≥ threshold)
```

**Enrollment (training):**
1. User records 5-10 voice samples (during wake phrase + contact training)
2. Each sample → model → 192-dim embedding
3. Average all embeddings → **owner voiceprint** (stored encrypted)

**Verification (runtime):**
1. Wake phrase detected by DTW (existing system)
2. Extract embedding from the same audio that triggered wake
3. Compare with stored voiceprint via cosine similarity
4. If similarity ≥ 0.70 → owner confirmed → proceed to command
5. If similarity < 0.70 → not owner → ignore, rearm wake

## New Classes

### `SpeakerVerifier.java`
```
- loadModel(Context)           → loads TFLite model from assets
- extractEmbedding(short[])    → audio → mel → inference → float[192]
- cosineSimilarity(a, b)       → similarity score 0.0–1.0
- verify(short[], voiceprint)  → boolean (is owner?)
- close()                      → release model resources
```

### `MelSpectrogram.java`
```
- compute(short[] audio, int sampleRate) → float[][] mel spectrogram
- Converts 16kHz PCM → 80-band log-mel spectrogram
- 25ms window, 10ms hop, 80 mel filter banks
- Normalized per-channel
```

## Changes to Existing Files

### `WakeWordEngine.java`
- After wake detection, pass the detected utterance audio to `SpeakerVerifier`
- New callback: `onWakeDetected(double distance, short[] audio)` (add audio param)
- Listener gets both DTW distance AND raw audio for verification

### `IrisListeningService.java`
- Hold a `SpeakerVerifier` instance (loaded once in onCreate)
- In `startWakeDetection()` callback:
  - On wake detected → verify speaker
  - If verified → proceed to command phase (existing flow)
  - If not verified → log "Wake phrase matched but speaker not recognized", rearm
- New setting: `settings.speakerVerification()` (on/off toggle)

### `ProfileStore.java`
- Store voiceprint as `float[192]` in encrypted profile JSON
- `getVoiceprint()` / `setVoiceprint(float[])`
- Voiceprint included in `.irisprofile` exports (with warning)
- During import: voiceprint is imported but marked as "needs re-enrollment"

### `MainActivity.java` (Training)
- Wake phrase training: after 5 samples (increased from 3), also compute embeddings
- Store average embedding as voiceprint
- Show enrollment status: "✅ Voice enrolled" / "⚠️ Voice not enrolled"
- New "Re-enroll Voice" button if voiceprint exists

### `AppSettings.java`
- `speakerVerification()` / `setSpeakerVerification(boolean)` — default ON
- `speakerThreshold()` / `setSpeakerThreshold(float)` — default 0.70

### `view_settings.xml`
- New switch: "Only respond to my voice" in Privacy & Safety section

## File Additions

| File | Size | Purpose |
|------|------|---------|
| `app/src/main/assets/speaker_model.tflite` | ~3-5 MB | ECAPA-TDNN model |
| `SpeakerVerifier.java` | ~200 lines | TFLite inference wrapper |
| `MelSpectrogram.java` | ~120 lines | Audio → mel feature extraction |

## Dependencies

Add to `app/build.gradle`:
```groovy
dependencies {
    implementation 'org.tensorflow:tensorflow-lite:2.16.1'
    implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'
}
```

Note: okhttp stays pinned at 4.12.0 (not affected — TFLite has no okhttp dependency).

Also update `gradle.properties`:
```
android.useAndroidX=true
```
TFLite requires AndroidX. This means `Activity` → `AppCompatActivity` migration or using `android.enableJetifier=true`.

## Training Flow Change

### Current: 3 wake samples
### New: 5 wake samples (for both DTW + speaker enrollment)

```
Step 1/5  →  Say "Nova"  →  ✅ Clear, DTW template stored
Step 2/5  →  Say "Nova"  →  ✅ Clear, DTW template stored
Step 3/5  →  Say "Nova"  →  ✅ Clear, DTW template stored
Step 4/5  →  Say "Nova"  →  ✅ Voice enrollment sample 1/2
Step 5/5  →  Say "Nova"  →  ✅ Voice enrollment sample 2/2

Voice enrolled! IRIS now recognizes your voice.
```

Samples 1-3: DTW templates (existing)
Samples 1-5: ALL used for speaker embedding (more samples = better voiceprint)

## Runtime Flow

```
Wake phrase armed
    ↓
DTW detects wake phrase (existing)
    ↓
Speaker verification ← NEW
    ├── Owner confirmed → "Awake. What are we doing?"
    └── Not owner → silently rearm (optional log: "Unrecognized speaker")
    ↓
Command recognition (existing)
    ↓
(Optional) Verify speaker again on command audio
    ├── Owner confirmed → proceed to contact resolution
    └── Not owner → "I only take commands from my owner."
```

## Privacy

- Voiceprint is a 192-float vector, NOT raw audio
- Stored encrypted via SecureStore (AES-256-GCM)
- Cannot reconstruct voice from embedding (one-way)
- Model runs 100% on-device, no network calls
- Exported profiles include voiceprint (with biometric auth gating)

## Performance

- Model load: ~200ms (one-time at service start)
- Mel spectrogram: ~5ms for 3-second clip
- TFLite inference: ~15-30ms on mid-range phone
- Total verification overhead: ~20-35ms per wake detection (imperceptible)

## Version

- versionName: 1.0.0 (major feature — speaker verification)
- versionCode: 180
- okhttp: pinned at 4.12.0 (not applicable)

## Risk: TFLite + AndroidX Migration

The biggest risk is that adding TFLite requires `android.useAndroidX=true`, which means:
1. All support library references must use AndroidX equivalents
2. Current `Activity` stays as-is (framework Activity doesn't need AndroidX)
3. But TFLite's `org.tensorflow:tensorflow-lite` pulls in AndroidX transitively
4. Need to add `android.enableJetifier=true` to gradle.properties

Alternative: Use ONNX Runtime for Android instead of TFLite (doesn't require AndroidX).

## Phases

### Phase 1: Model + Verifier (this PR)
- Add TFLite dependency, model, SpeakerVerifier, MelSpectrogram
- Wire into WakeWordEngine + IrisListeningService
- Update training to 5 samples + enrollment
- Settings toggle

### Phase 2: Command verification (follow-up)
- Also verify speaker during command recognition
- Rejection response: "I only take commands from my owner."
- Re-enrollment flow if voice changes
