# IRIS 13.0.0 — media routing and recoverable voice processing

Version code: 351. Includes the 11.2.1 and 12.0.0 changes awaiting merge.

## Changes

- Failed Bluetooth communication routing now releases IRIS's audio mode, even when route cleanup itself throws. Passive listening switches to a confirmed phone or wired input during music playback instead of holding Bluetooth call mode. It does not force the speaker over a selected headset or take over an existing call.
- A standalone verified wake uses local TTS for “Hello boss” (or the configured address), with a fallback cue when speech is unavailable. Inline commands preserve their command audio.
- Live owner embedding and offline command decoding run in a private, non-exported `:voice_models` service. A native operation deadline can terminate this worker. Disconnects, errors and stale replies cannot accept a wake; session recovery recreates the worker.
- Optional endurance diagnostics record stage timings and up to 500 cycles in memory. User-labelled first-call attempts are separate from automatic accepted cycles. Export contains metadata, not audio or transcripts.
- Feedback previews show minimum held-out speaker scores before and after refinement. Authentication, four held-out checks, revision guards and rollback remain required. Labels alone do not establish an accuracy improvement.
- Watch pairing adds an authenticated phone loopback probe. Expired watch actions and late UI replies are rejected. Node tests exercise the relay and request timeout handling.

## Validation and limits

See [device test cases](TEST-13.0.0.md). Local speech regression and watch syntax/transport tests pass. Android compilation and JVM tests run in the pull request's GitHub Actions workflow; use that run as the build record.

No physical phone or T-Rex 3 was available. Real acoustic recall, audible response latency, battery use, output routing and watch compatibility are not yet measured. Native models in a separate process add memory and startup costs. During Bluetooth music, the phone microphone may be used; train the phone voice profile for that input. Android/OEM restrictions can still affect background operation and DND permissions.

The watch project remains experimental. Zepp packaging was blocked by automatic approval review because the build attempted external CDN access that might export project data; it was not retried without explicit authorization. Local transport tests and the phone probe do not demonstrate an installed watch app.

Commit message: `Release IRIS 13.0.0: preserve media routing and recover voice workers`
