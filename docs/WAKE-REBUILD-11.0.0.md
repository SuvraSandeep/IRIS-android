# Wake and training rebuild — 11.0.0 (340)

## What changed

Phone and headset use one take transaction: capture → quality → phrase → owner → paired append → checkpoint. Start and Done are explicit; automatic capture uses a 700 ms pause. Four natural variants are followed by four independent checks replayed through the actual streaming detector. Verification uses the exact detected feature frames and the same owner-audio context as runtime. The saved profile remains unchanged until authenticated save. Failed calibration retains three examples and identifies the replacement. Duplicate/stale callbacks cannot append an extra pair.

The live detector confirms candidate features before pausing the search, instead of extracting a differently trimmed phrase after detection. Negative feedback remains authoritative. FFT/DTW runs on a bounded worker drain, outside the audio-priority capture thread. Owner verification keeps incoming PCM buffered; after rejection, search resumes through buffered audio instead of resetting and throwing it away. Dynamic-programming arrays are reused. Audio overruns are explicit diagnostics.

Cold startup now waits for all required speaker models before checking fingerprints. Previously, ECAPA loading could be reported as a permanent model mismatch with no retry. Draft restore now uses variant matching and the same dual-model decision as enrollment, instead of the old three-example/Vosk-only check. New checkpoints bind the ECAPA fingerprint too. Compatible old Vosk-only drafts retain examples but repeat live checks; saved profiles remain compatible.

Training puts phone setup and live testing first, with matching settings and feedback below the profile. The recording action changes into Done while listening. Progress counts four examples and four checks separately. Spoken synthetic examples are hidden because recordings, not spelling or synthetic pronunciation, define the sound.

## Architecture choice and limits

The recorded-sound requirement is retained: labels are not transcript gates. Neural keyword engines such as sherpa-onnx and openWakeWord were evaluated at documentation level. Sherpa custom keywords require tokenized words; openWakeWord custom neural models require a separate dataset/training pipeline. Neither is silently substituted for arbitrary user-taught sounds. This release rebuilds the existing sound-based path; it does not ship a new pretrained neural keyword model.

ECAPA and Vosk still verify the owner; thresholds are not lowered and a missing required identity signal fails closed. Four held-out checks, device authentication, revision guards, negative feedback and sensitive-action confirmations remain. Phone/headset hardware accuracy, replay resistance, CPU/battery use and first-call success must be measured on a device. Offline commands retain buffered handoff; external recognition providers still require their own microphone handoff.

## Validation

Regression coverage includes real synthesized PCM replay at different gains/frame offsets, repeated calls, negative-candidate vetoes, buffer boundaries, manual recorder completion, paired-append integrity, draft migration and model-readiness ordering. Android unit tests and APK build are required before delivery. Synthetic audio tests demonstrate pipeline behavior, not real-world accuracy.

Install, freshly train phone and headset, use the live test and optionally label 50–100 reliability-study attempts under your real conditions. Include other voices and confusing sounds before changing strictness.
