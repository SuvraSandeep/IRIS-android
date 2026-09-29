# Voice adaptation and explicit variation feedback — 11.1.0

## Research and design

Speaker identity and phrase detection are different tasks. SpeechBrain's speaker-recognition interface compares ECAPA embeddings using cosine similarity (https://speechbrain.readthedocs.io/en/latest/API/speechbrain.inference.speaker.html). A speaker embedding is not a waveform-equality or pitch-equality test. The threshold printed in that model's example is not transferable to IRIS's weighted two-model pipeline.

openWakeWord uses a phrase detector with a separate optional verifier; its training guidance uses varied positives, confusing negatives, noise and room augmentation, and deployed-environment false-activation testing (https://github.com/dscripka/openWakeWord). This supports the separation of phrase and identity, but is not validation of IRIS's thresholds or an Android drop-in. No new openWakeWord model is bundled in this release.

## Implemented

IRIS already uses ECAPA + Vosk for identity and a separate streaming acoustic phrase matcher. The rejected-owner feedback path explicitly refused ECAPA profiles, preventing normal current profiles from learning borderline voice changes. It now uses both captured vectors. Each authenticated update blends 10% normalized new evidence into each available model's centroid, without lowering the owner threshold or modifying phrase evidence.

Corrections require a matching phrase, valid required models, a score within 0.10 of the saved owner threshold (absolute floor 0.55), and minimum agreement from each model for dual-model profiles. Every original held-out check and the new example must pass after updating; negative examples still veto acceptance. Phone and headset are isolated. The pre-adaptation centroids are persisted as anchors; updated centroids must remain within cosine 0.97 of them. Six updates maximum, revision guards, device authentication and rollback remain required. These conservative bounds are engineering limits, not a measured false-accept guarantee. Large changes require fresh authenticated refinement.

Training → Feedback → Teach a natural phrase variation explicitly records one attempt, including when background detection created no event. It uses existing pinned speaker models, confirmed microphone route, audio quality checks and temporary PCM erased after analysis. Speaker identity must already pass before expanding phrase examples; the phrase must already pass before adapting a borderline voice. Both failing never permits changing both identity and phrase from one attempt. Model setup and analysis are time-bounded; cancel/backgrounding releases capture and preserves the saved profile.

Training prompts encourage varied pace, softness and expression with the same phrase; the summary correctly identifies the actual ECAPA + Vosk identity checks. Existing training imports remain supported.

## Validation and remaining architecture work

Tests cover dual-model adaptation, unchanged thresholds/phrase evidence, phone/headset isolation, retained held-out styles, missing/corrupt vectors, other speakers, negative evidence, anchor integrity and repeated-feedback drift. Offline regression and Android build/test/lint results are recorded in the PR. No device false-accept/first-call rate is claimed.

The phrase path remains a small recorded-example acoustic matcher. Replacing it with a pretrained, noise-augmented neural keyword model and collecting separate longer speaker-enrollment utterances remain unimplemented. This release fixes practical adaptation/feedback gaps; it is not the full neural wake architecture. No promise of waking every time, noise separation or replay resistance is made.
