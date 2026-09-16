# Owner training repair — 8.25.1

Base: main 8604ff7d91c9cfde4982c06ed6d96ffd6f0f8d0f.

## Reproduced cause
The supplied September 16 screen recording displays `Offline speaker model failed to load: ClassNotFoundException` before audio capture. VoskEngine loads `org.vosk.SpkModel` reflectively. The actual pinned vosk-android 0.3.75 AAR contains `org.vosk.SpeakerModel`, not that class. Both enrollment embedding and live wake also call nonexistent `setSpkModel`; the actual method is `setSpeakerModel(SpeakerModel)`. Downloading models, changing timeout values or collecting more recordings cannot fix these Java API errors.

## Changes
- Use typed SpeakerModel construction, attachment and close throughout VoskEngine. Dependency API changes now fail Android compilation instead of silently breaking enrollment at runtime.
- Add a JUnit contract test against the actual Gradle dependency and run it as a required CI step before APK assembly.
- Validate mfcc.conf as well as model weights, mean and transform; the native speaker loader requires all four.
- Serialize speaker installation across engine instances, not merely per engine, because extraction targets are shared.
- Prevent owner enrollment, command practice, contact training and wake testing from starting over another active training/test session. Command practice no longer unnecessarily initializes the speaker model. Its initialization callback ignores a cancelled/replaced engine.
- Retain exact phrase validation, 5 normal + 5 soft enrollment takes, 4 held-out verification takes, strictness, timeout/cancellation and authenticated atomic saving. This repairs the broken model integration rather than replacing functioning enrollment screens.

## Validation and limits
Local regression suites and Java syntax checks passed. The real 0.3.75 AAR was inspected with javap to confirm both API signatures. Android CI performs dependency contract tests, APK assembly and lint. No physical Android device was available here; local platform fakes do not verify microphones, Bluetooth or speaker accuracy.

Device acceptance: install 8.25.1, choose a distinctive multiword phrase (for example Hello Iris), start training, verify model preparation reaches the recording countdown, finish 14 accepted takes, authenticate and save, then test normal and soft speech. Wrong phrases must retry; cancellation must preserve the old profile. Try a second person's voice and playback as negative cases. A very short phrase such as `hi` may have too little speaker evidence; the safe response is rejection, never reducing identity checks. Pure whispers and replay resistance are not guaranteed by this speaker model.
