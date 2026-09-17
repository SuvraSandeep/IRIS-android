# Recorded sound wake — 8.30.0

User-authorized change: learn the sound the owner records, without grading its pronunciation or comparing it with a transcript. This extends the unmerged v8.29 PR; main was 0261ebb2d5e908714a9dd05c03f0b98ef3e89d80 when checked.

## Behavior
- Type a label, then record the chosen wake sound five times normally and five times softly. The user directs enrollment after device authentication. Usable audio and valid speaker evidence are required. No ASR text gate is called for these takes.
- Four fresh recordings independently check both the sound pattern and owner embedding. They do not tune the acceptance threshold. Save only after validation and device authentication; retain rollback and verify readback.
- Schema 5 stores versioned sound features and the existing separate speaker evidence. The typed label is not an acoustic target. Older profiles stay active until replacement; a fresh setup is necessary to create missing sound examples. Older apps reject schema 5 rather than silently downgrade it.
- The same sound matcher is used for held-out checks, the Test button and background listening. Background sound mode segments raw PCM with the bounded endpoint, bypasses transcription entirely, compares sound features, then checks the owner. The service still checks profile changes, playback context, cooldown and identity before starting command listening.
- The feature does not grant permissions or remove action confirmations/lock-screen gates. Wake identity is not a guarantee that every subsequent command comes from the owner, and does not replace Android authentication.

## Matcher implementation
SoundPattern is a local Java log-mel/DTW matcher, not a newly trained neural speech model. It uses 24 spectral bands, 25ms windows and 20ms steps; trims surrounding quiet audio, represents internal silence, normalizes frame energy, and compares ordered sequences with constrained dynamic time warping. It requires agreement with three enrollment examples and rejects invalid dimensions/non-finite values and excessive duration differences. Threshold calibration uses enrollment examples only, under a fixed upper cap; held-out validation cannot loosen it. These engineering limits are not population-calibrated security guarantees.

The separate Vosk speaker encoder and owner thresholds are unchanged. A matching sound alone never admits a wake. Missing/invalid schema-5 evidence cannot fall back to text-only or speaker-disabled wake.

## Feedback and privacy
- “Another person's voice woke IRIS” proposes a speaker negative, preserving saved owner validation takes.
- “That was not my wake sound” proposes a sound negative, preserving every retained sound validation take. It does not change speaker strictness or lower the sound threshold.
- “My voice was missed” starts fresh authenticated recording and validation. The app does not invent an unavailable missed recording or passively enroll unknown voices.
- Corrections require a current accepted event, matching profile revision, review, authentication and protected commit. Incompatible corrections reject; rollback is available.
- Recent accepted sound features and embeddings are retained only in bounded process memory (12 events / 2 minutes), then zeroed. This is biometric evidence; it is not written to routine logs. Raw PCM is not retained except existing explicit diagnostic recording controls.
- Encrypted transfer includes the owner's sound/voice banks; sound and speaker negatives are excluded to avoid transferring other people's evidence. Recipient therefore needs fresh validation and may need to reapply corrections. Transfer parsing remains bounded (4 MiB plaintext limit), accommodating the new feature matrices.

## Verification
Run scripts/test-speech.sh plus Android unit tests/APK build. New synthetic-audio tests cover gain/pace changes, reversed temporal patterns, internal pauses, truncated duration, silence, clipping and malformed features. Sound profile tests cover round-trip, dual sound/owner gating, correction protection, threshold changes and schema downgrade rejection. Existing Android layout/playback tests remain required.

These tests are not evidence of real-world false-wake rates, whisper accuracy, replay resistance or battery performance. Test on the actual phone before relying on owner-only behavior: new recordings, other people saying the same sound, the owner saying different things, TV/media, replay of owner audio, supported headsets, pause/resume and profile changes. Do not lower thresholds automatically to hide misses. If rejection remains poor, collect consented local evaluation data and compare a learned acoustic embedding backend behind this same two-check contract.

## Try it
Install this APK; open Training → Record my wake sound. Complete a fresh setup (older profiles lack sound matrices). Optional spoken-label playback is only a convenience and never the training reference. After Save, use Test saved voice, then review recent wake events for corrections. Avoid sensitive-action testing while measuring false wakes.
