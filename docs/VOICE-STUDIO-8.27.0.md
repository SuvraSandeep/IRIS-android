# Voice studio and recognition changes — 8.27.0

The owner reports zero successful “Hello Iris” training takes across 30–40 attempts. This is not treated as proof of unclear speech.

## Evidence
The actual bundled vosk-model-small-en-in-0.4 archive was downloaded and loaded with Vosk in a local probe. Both words exist: hello word ID 70560, iris ID 52927. Missing vocabulary is therefore ruled out for that model. The previously supplied screen recording contains no decodable speech; it cannot establish the present recognition failure. No current recording of the owner's failed take was available.

## Recognition change
Fresh enrollment uses the offline `vosk-model-small-en-us-0.15` pack. It is bundled in the APK and has a bounded runtime download fallback. This is a different English acoustic/language model, not a grammar forced to answer the typed phrase. Profiles record the recognizer model; once saved, the service loads the same pack for speech recognition. Existing profiles continue using their previous model until a fresh profile is saved. Older profiles must be freshly enrolled to migrate to this pack; they are not silently relabeled as compatible.

This also means the active speech engine, including commands, follows the enrolled profile's pack. Do not interpret the pack's US name as evidence that it will necessarily outperform the Indian-English model for this owner. The model change needs device validation; no accuracy claim is made. Model selection for new-version profiles takes precedence over the older high-accuracy voice option to keep training/live identity aligned. A future separate command-engine model selection can decouple these choices.

Official model information: https://alphacephei.com/vosk/models lists small-en-us-0.15 as a 40MB Android-capable Apache-2.0 model. Metrics there use different datasets from the Indian-English model and must not be compared as this user's accent accuracy.

## UI change
A focused voice-studio card replaces the dense setup panel. The phrase is prominent, progress is visible, microphone bars react to actual capture levels and each take starts only when the owner taps Record. Secondary command/contact practice and voice/privacy tools are collapsed. Disabled alternate-phrase inputs are hidden rather than falsely claiming a phrase was added. Repeated rejections direct the user to diagnostics instead of asking for endless repeats.

## Diagnostic recording
Voice tools can explicitly retain ONE next training take in memory for two minutes. Nothing is uploaded. A separate authenticated WAV export lets the owner listen locally or choose to share it. This is needed to distinguish missing microphone audio from a decoder error. By default no raw diagnostic take is retained. Training cancellation never replaces saved identity.

## Tests and limits
Regression checks cover the guided layout, explicit record control, PCM WAV structure, profile decoder metadata, endpoint/cancellation, encryption and profile validation. Android compilation and CI profile tests remain required. UI is implemented in Android resources and a custom audio-driven View; no fabricated screenshot is presented as a device render. Physical-phone UI/recognition and real accent accuracy must be verified with the APK.

If the new pack still rejects a clear take, capture ONE diagnostic WAV and inspect that audio; do not advise another 30–40 repetitions or weaken exact-phrase/owner gates. Broader KWS benchmarking remains an evidence-driven follow-up.
