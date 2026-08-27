# IRIS v0.2.2

**Intelligent Responsive Interaction System**

IRIS is a native Android personal-assistant MVP focused on calling. It can wait for a user-trained custom wake phrase, listen for a short call command, resolve the contact, and require confirmation before calling.

## The v0.2 experience

1. Train any wake phrase—for example `Nova`, `Computer`, `Wake up`, or a phrase in your own language.
2. Say the trained phrase once.
3. IRIS opens a short command window and responds through the active phone, wired, USB, Bluetooth Classic, or BLE headset microphone.
4. Say `Call Maa`, `Ring Rahul`, `Dial Office`, or a trained personal phrase.
5. If contact names are similar, IRIS asks which one you meant.
6. Confirm by saying `Call` / `Yes`, saying `Cancel` / `No`, tapping the dialog, or using the notification.

## Custom trainable wake phrase

The wake phrase is not hard-coded. The user types any label and records it three times. IRIS extracts compact acoustic features from 16 kHz microphone audio, calibrates a Dynamic Time Warping threshold from the three samples, and stores the templates locally.

- No raw wake-word recordings are retained.
- Wake detection does not run full speech-to-text continuously.
- Templates, label, and sensitivity travel inside the exported `.irisprofile`.
- A transferred profile may be retrained on the destination phone for the best microphone match.
- Wake templates improve personalization but are not a high-security biometric identity system.

## Included features

### Listening and recognition

- Three modes: **Custom wake phrase**, **Tap to talk**, and **Continuous experimental**.
- On-device Android speech recognition preferred where the phone supports it.
- Offline language-model download request on supported Android versions.
- System recognizer fallback when on-device recognition is unavailable.
- English India, Hindi India, Hinglish, or system-language commands.
- Live recognition-engine and microphone status.
- Automatic headset connection/disconnection handling.
- Manual microphone preference: Automatic, Bluetooth, Wired/USB, or Phone.
- Quick Settings tile for arming or stopping IRIS.

### Calling intelligence

- `Call`, `Dial`, `Phone`, and `Ring` command forms.
- Exact, partial, word, edit-distance, phonetic, learned-phrase, and nickname matching.
- Contact disambiguation instead of silently guessing.
- Voice and touch confirmation.
- Contact photo inside the IRIS orb when available.
- Most-called contact summary stored locally.
- Short codes, emergency-length numbers, and likely premium `1900` numbers open the dialer instead of auto-calling.
- Direct-call permission fallback opens the dialer safely.
- Calls can require phone unlock by default.

### Training

- Three acoustic wake-phrase samples with signal-to-noise quality feedback.
- A no-call wake-phrase test.
- Three contact-command samples with Clear, Usable, or Quiet feedback.
- Encrypted training manager with inspect, add phrase/nickname, and delete controls.
- Safe command test that displays the matched contact without placing a call.
- After an unmatched command, **Teach IRIS what you meant** maps the correction to a selected contact.
- Profile merge import avoids duplicate phrases.

### Interface

- Animated IRIS orb with resting, wake-armed, listening, thinking, confirmation, and contact states.
- Voice-reactive orb halo during recognition.
- Live transcript and state cards.
- Sarcastic, Warm, Professional, or Silent personality.
- Optional voice replies and haptic feedback.
- Standard, Large, and Extra-large text modes.

### Privacy

- Local profile and activity files are AES-GCM encrypted with a non-exportable Android Keystore key.
- System biometric or device-credential confirmation protects profile and log exports.
- Logging modes: Commands only, Full transcripts, or Off.
- Automatic log deletion after 1, 7, or 30 days, or never.
- Raw microphone audio is not saved.
- Explicit `.irisprofile` export/import is used for portability because device Keystore keys cannot be transferred.

The Android speech-recognition provider may still use a remote service when the phone has no on-device recognizer. IRIS identifies the selected recognition path in the interface. Continuous experimental mode can consume significantly more battery and network data.

## Build directly from the ZIP with GitHub Actions

1. Create an empty GitHub repository.
2. Upload `IRIS-Android-v0.2.2-source.zip` to the repository root without extracting it.
3. Save the supplied standalone YAML as `.github/workflows/build-apk.yml`.
4. Open the repository's **Actions** tab.
5. Choose **Build IRIS Android APK** and select **Run workflow**.
6. The workflow automatically extracts the ZIP and builds the correct project directory.
7. Download the `IRIS-v0.2.2-debug-apk` artifact when the run succeeds.
8. Extract and install `IRIS-v0.2.2-debug.apk`.

The same workflow also supports repositories where the project was already extracted. The standalone `build-iris-apk.yml` supplied beside the ZIP is identical to the workflow included inside the source archive.

## First-use test checklist

1. Grant microphone, contacts, phone, notifications, and Bluetooth access when applicable.
2. Open **Training**, enter a custom wake phrase, and record three clear samples.
3. Use **Test** to verify the wake phrase without calling.
4. Train one contact with three natural phrases.
5. Use **Test a trained command — no call**.
6. Choose **Settings → Custom wake phrase**.
7. Arm the orb, speak the wake phrase, then say the call command.
8. Verify voice, dialog, and notification confirmation.
9. Connect/disconnect a headset and verify the microphone label changes.
10. Export and re-import an `.irisprofile` after authentication.

## Compatibility and build versions

- Android 8.0+ (`minSdk 26`)
- Android 15 compile target (`compileSdk 35`)
- Java 17
- Android Gradle Plugin 8.7.3
- Gradle 8.9 in GitHub Actions

## Project structure

```text
app/src/main/java/com/iris/assistant/
├── MainActivity.java            # UI, training, settings, authentication, transfer
├── IrisListeningService.java    # wake/command state machine, recognition, calls
├── WakeWordEngine.java          # custom acoustic templates, VAD, feature extraction, DTW
├── ProfileStore.java            # contacts, phrases, wake templates, call statistics
├── SecureStore.java             # Android Keystore AES-GCM storage
├── LogStore.java                # encrypted retention-aware activity timeline
├── AppSettings.java             # user-selectable behavior
├── IrisOrbView.java             # animated, voice-reactive assistant orb
└── IrisTileService.java         # Quick Settings control
```

## Production note

The workflow produces a debug-signed APK for testing. A Play Store release still needs a private release signing key, a privacy policy, store declarations for sensitive permissions, device testing across manufacturers, accessibility QA, and a clear disclosure for foreground microphone use.
