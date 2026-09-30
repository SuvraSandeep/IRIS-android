# IRIS 11.2.1 — repeat wake and recovery

The previous offline command window stopped scanning for the wake sound after the first accepted wake. Saying the phrase again during that window could therefore look like a dead wake engine. Server TTS also lacked bounded completion and generation checks, and session capture/analysis had no independent health watchdog. These are code-level failure paths, not a confirmed diagnosis from this phone's logs.

## Changes

- Scan the enrolled sound during offline command capture as well as idle wake listening. Each candidate still passes recorded-phrase, owner, microphone-route and live profile-revision checks. A rejected candidate returns to the existing command; an accepted repeated wake invalidates the old command callback and starts a fresh interaction.
- Return to wake mode through one cancellable 150 ms retry, with a longer backoff on errors. Clear unfinished clarification/composition state on a fresh verified wake.
- Detect stopped/stalled capture and stalled analysis from a separate main-handler heartbeat; report recovery and make the failed session replaceable. This cannot forcibly terminate a hung native model call or override Android microphone restrictions.
- Get the captured route from the actual AudioRecord rather than shared UI status that another recording can reset.
- Bound server TTS completion, guard late downloads/player callbacks, prepare asynchronously, and invalidate stale reply callbacks on rearm. Handle null continuation safely.
- Pause the streaming session during manual-trigger greetings and set the actual phase consistently.

## Validation

Offline scripts/test-speech.sh passes. New Android tests exercise the real session transitions through 50 verify/pause/rearm cycles, owner rejection during a command, subsequent owner acceptance, stale generation guards and stopped-capture recovery. They use mocked speaker embeddings and no physical microphone; they do not measure acoustic accuracy. CI runs the Android tests and APK build.

No owner threshold, model, phrase evidence, training validation or authenticated feedback rule is weakened. No promise of 100% wake accuracy or every app bug being fixed. Wake during IRIS's own speech and external Google/server microphone sessions remains outside the new parallel offline wake scan.
