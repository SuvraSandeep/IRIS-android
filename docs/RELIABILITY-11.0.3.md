# Wake and command investigation — 11.0.3

Compared current main db6fdf8 (11.0.0), unmerged PR20 (11.0.2), version 7.37.0 at ebf91932 and version 8.6.1 at a82e76d4. This revision extends PR20 and includes its earlier lifetime/storage fixes. Main had not merged PR20 at investigation time; the version installed on the reporting phone is unknown.

## Reproduced wake mismatch

SoundPattern.distance accepts durations down to 0.45 times a stored example. StreamingWakeDetector independently required 0.65 times. A deterministic test compresses a valid forty-frame phrase into twenty frames: saved phrase evidence accepts it, while the old live detector misses it. The new test failed before the change and passes after aligning the duration gates. Full phrase checks, negative feedback and owner identity remain required; no similarity thresholds changed.

The live worker also performed up to four native speaker inferences for each rejected candidate, appending up to two seconds of unrelated trailing sound. Those retries blocked analysis of subsequent calls. It now verifies each candidate once using the original bounded phrase context, matching LiveWakeProbe enrollment verification; rejection immediately resumes acoustic search. A short phrase with insufficient identity evidence still cannot authorize wake. This change does not make the speaker model infallible.

## Command regressions

Both historical versions defaulted to system speech recognition; current builds default to the small offline Indian English model. The old versions also defaulted to speaker verification OFF. Rolling them back wholesale would remove the current owner-only requirement.

The buffered command path omitted QuietAudioProcessor processing present in ManagedSpeechService. It now uses the same 20 ms stateful, bounded gain updates, tested against the older microphone path. Decoder batches are capped at 200 ms so accumulated audio cannot monopolize a single decode operation.

An unconditional command-model preload callback could start Vosk whenever the phase was COMMAND, including during an active Android recognition session. Prewarming now happens only for the selected offline provider. CommandLoadGate requires a still-current explicit offline request; switching providers, stopping or rearming cancels it. Existing provider settings are preserved. Settings → Indian English accuracy → Apply selects en-IN and system speech, with the existing disclosure that the provider may receive audio. Language accuracy depends on the installed provider and the actual voice; code tests cannot establish it.

## Native memory and crash limits

The ECAPA ONNX session no longer uses the CPU arena or shape-based memory-pattern preallocation for variable-duration clips. This avoids retaining peak-sized arena buffers; it is memory hardening, not evidence that memory exhaustion caused the reported crash. Avoiding unused command models and repeated inference also reduces unnecessary work. The native model and identity preprocessing remain unchanged.

Official API reference: https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html
System recognition behavior: https://developer.android.com/reference/android/speech/SpeechRecognizer

No device crash stack or exit report was supplied. The existing authenticated Logs → Crash & wake report contains the installed version, Java crash location when available, Android exit reasons (including native crash and low-memory kill), and wake-stage diagnostics. That report is necessary to identify a remaining phone-specific crash rather than guess from symptoms.

## Validation

Offline regressions include the demonstrated failing-before/passing-after duration test. Android tests cover model-load request cancellation/provider switching and exact gain parity across buffered and microphone-sized frames. CI results are recorded in PR20. These are functional tests, not real Indian-accent recordings or handset first-call accuracy measurements. No retraining required.
