# Phrase-specific recognition and guided setup — 8.28.0

Base main: 7828ab08f719e25c7dfdb14f0d8552d418f69c82 (merged PR #11).

## Observation
The owner reports “Hello Iris” decoding as “hello I this,” “hello Alice,” or “hello Heidi’s,” while “Hello Nova” succeeded on the first attempt. This supports a phrase-specific decoding problem; it does not prove the microphone/recognizer is reliable in every condition. This release does not pretend to repair the acoustic distinction or silently treat those other names as Iris.

## User flow
1. Enter a phrase or explicitly choose Try Hello Nova. The saved profile is unchanged.
2. Check my phrase runs one ordinary full-vocabulary recognition take before loading the speaker model. No embedding is added to enrollment and no identity is written.
3. The screen shows YOUR CHOSEN PHRASE and IRIS HEARD separately. Similar-name/missing-word/extra-word results do not pass.
4. On success, choose Continue · train my voice. The existing 14-take enrollment then starts at zero, including independent validation and authenticated saving.
5. On failure, retry or choose another phrase. Advanced diagnostics remain available; users are not encouraged to repeat an unrecognized name indefinitely.

## UI
- Clear phrase-first introduction and CHECK → VOICE → VERIFY navigation.
- Inline decoded-word comparison, a deliberate Continue action and an explicit phrase-change action.
- Main buttons have flexible height and minimum touch targets for larger text.
- Saved-profile test is labeled separately from phrase preview.
- Advanced controls are grouped under Understand & Improve, Backup & Restore and Optional Audio Diagnostics.
- Existing waveform is driven by actual microphone samples; no fabricated recognition animation.

## Safety and limits
Phrase preview never replaces or weakens speaker checks. Changing the input text or choosing a suggestion cannot change the active profile. Alice, I this, Heidi’s, an incomplete phrase and extra speech remain rejected for Hello Iris. Hello Nova is a suggestion motivated by this owner's observation, not a universal accuracy claim. No models, identity thresholds or accepted aliases changed in this release.

## Verification
Local speech/recorder/crypto regressions and XML usability checks run before push. New PhraseCheckSession tests exercise similar-name rejection, required explicit continuation, cancellation and switching phrases without carrying over approval. Android CI must compile and run all unit tests, build the APK and produce the configured lint report. Actual-device UI rendering and this owner's recognition accuracy remain unverified here.
