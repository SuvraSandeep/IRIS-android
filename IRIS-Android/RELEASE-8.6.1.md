# IRIS 8.6.1 — Indian-accent speech and NLP fixes

Based on upstream b294fd9 (8.6.0). Preserves camera early-stop and watch reply notifications. Does not include the separate, unpublished capture/training patch from the earlier task.

## Fixed

- New/default preferences use en-IN and system recognition without forcing an on-device model. Previously the “Google” command path defaulted to on-device-only creation. Existing explicit preferences are not silently overwritten; the Settings accuracy preset explains provider processing before applying.
- The build now bundles vosk-model-small-en-in-0.4 into model-en-in. Previously it bundled US English while runtime downloads targeted Indian English. Asset installation uses a staged Indian-model directory and validates model files; it does not depend on an absent StorageService uuid asset.
- The ready cue follows recognition readiness, rather than playing before the microphone is listening.
- One retry for unclear, low-confidence or transient recognition failures; explicit repeat prompt when falling back to offline speech. No infinite Android/Vosk fallback recursion when both engines are unavailable.
- Generation-checked Android callbacks ignore cancelled/replaced recognizers. No commands run from partial results or lower-ranked contact guesses.
- Common Indian-English command forms (“make a call to”, “give X a call”, “switch on the torch”, “click one screenshot”) map to existing handlers.
- Learned aliases apply only to exact, unambiguous command prefixes. They no longer fuzzily replace names or words inside message bodies.
- Information shortcuts no longer swallow messages/searches/reminders just because they contain “time” or “battery”.
- Common speech hints contain fixed command vocabulary, not the user's address book.

## Verify

Run bash scripts/test-speech.sh (Java 17) for pure-Java regression and source syntax checks. GitHub Actions runs it before the Android build.

Physical-device acceptance:

1. Apply Settings → Set up Indian English accuracy; verify en-IN and on-device preference OFF. System speech may use the installed provider online even with Server mode OFF.
2. In a quiet room using the phone mic, test “call Maa”, “could you switch on the torch?”, “give Rahul a call”, “click one screenshot” and normal sentences after the ready cue.
3. Check transcript against what was spoken. A wrong transcript is recognition; correct transcript/wrong action is parsing. Compare phone mic and headset separately.
4. Test a draft SMS containing “time”, “battery” and learned alias words; verify text is unchanged and not routed to time/battery status. Cancel rather than sending during tests.
5. Test no speech, a network failure, and missing model/provider: one bounded retry or clear fallback, no duplicate action or loop.
6. Test airplane mode with the Indian offline model installed. It has limited vocabulary/accuracy; Hindi needs an appropriate system-language model.
7. Verify the existing watch notification and camera stop features remain intact.

No recorded user-accent evaluation has been performed. Text regression checks and successful compilation cannot prove real-world transcription accuracy. Voice training here learns aliases/voice identity; it does not fine-tune the speech recognizer.

API references: [Android speech recognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [recognition options](https://developer.android.com/reference/android/speech/RecognizerIntent), [Vosk model catalogue](https://alphacephei.com/vosk/models).
