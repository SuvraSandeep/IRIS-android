# Training rebuild: implemented scope and remaining evidence

Base: main 0261ebb2d5e908714a9dd05c03f0b98ef3e89d80. Version 8.29.0 / 324.

## Implemented
- Authenticated owner enrollment now has five normal and five soft sentence recordings. These check usable audio and a finite 128-component speaker embedding, not exact ASR spelling. Four separate short wake-phrase takes still validate phrase and identity before authenticated saving. This deliberately separates identity enrollment from phrase verification, as authorized by the roadmap.
- Existing-owner refinement checks each new sample against the current profile; import retains its separate fresh-validation flow. Speaker thresholds/model/preprocessing are unchanged. Initial sentence takes are explicitly owner-directed, not passive automatic learning.
- Offline English TTS example (prefers installed Indian English). No network-required voices selected. Recordings and playback share a lease. Preview stops the live listener first, waits for microphone release, has a deadline, and keeps a brief post-output lease. Typing cancels a pending example; playback is manual, not per keystroke.
- Opt-in diagnostic PCM can be played locally; it expires after two minutes and is not uploaded. Audio diagnostics show actual observed input, duration, energy estimate and clipping, plus whether a speaker vector was extracted. Energy estimates are not calibrated speech probabilities.
- All training action rows now stack vertically and use content-driven heights. Keyboard is dismissed before enrollment. Phrase/example/profile information is separated and progress wording reflects sentence versus phrase stages.
- Saving checks the authenticated, versioned profile through the production read path and restores the previous root if readback differs. The overview reports real sample counts, encoder, revision and timestamp. No invented accuracy percentage.

## Validation
Run local scripts/test-speech.sh. Android CI runs all JUnit tests, including new enrollment-quality tests, playback lease/cancellation tests and Robolectric measurement of real training XML at 320/360/412dp and font scales 1/1.5/2. These are not screenshots or physical-device tests. APK assembly is required before release.

## Not yet established or shipped
This release is a first implementation increment, not the entire roadmap. The existing Vosk full-vocabulary exact phrase gate remains for final wake verification and live wake. Therefore Hello Iris / Good Good recognition can still fail there. A replacement customizable keyword detector, model benchmark and calibrated acoustic tolerance are not enabled without real held-out positive/negative audio. No fake alias mappings, target-only grammar, global threshold reduction or weakened identity gate was added.

Also outstanding: alternative speaker-model evaluation, dynamic sample-count selection, phone/reboot/whisper/headset validation, responsive screenshot/keyboard/inset matrix on actual devices, debounced automatic TTS preference, and fully extracting orchestration from MainActivity. Existing encrypted transfer and correction/rollback features are retained, not represented as new work.

## Device acceptance steps
1. On Training enter a phrase, choose Hear this phrase, and verify playback works offline. Stop/edit/navigate during playback. It must never record or wake itself.
2. Choose Teach IRIS my voice; read the displayed sentences normally then softly. Recognition text must not block these identity takes. Silence/invalid evidence must be rejected.
3. For microphone diagnosis, enable Keep one diagnostic recording before a take; use Hear my diagnostic recording afterward. Confirm complete captured speech and observed mic route. Export is optional and separate.
4. Complete four independent wake-phrase checks and authenticate to save. Check the displayed revision, then force-stop/reopen and reboot to verify the same saved profile.
5. Try large fonts and a small screen. Check all buttons are readable and scrollable, including keyboard-open and error states.
6. Test non-owner speech and media playback separately. Do not conclude owner-only accuracy from one successful owner take.

Primary API references: https://developer.android.com/reference/android/speech/tts/Voice and https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener. Offline voice availability depends on the installed TTS engine; device verification remains necessary.
