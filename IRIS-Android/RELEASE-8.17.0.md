# IRIS 8.17.0 — Protected owner wake and quiet enrollment

Based on main 510868d803315107c264378d7b07fdbbf7a831d6 (8.16.2). Preserves the latest battery-rate telemetry, orb and microphone controls. Replaces optional phrase-only wake with the owner-only policy explicitly requested for this release.

## Fixed
- Retraining no longer removes the working voiceprint before a replacement validates.
- Six five-second captures: three normal, three quiet/whisper. Normal and quiet speaker
  centroids validate separately; a failed quiet set never weakens the normal owner threshold.
- Quiet capture uses bounded gain and relative signal quality, replacing a fixed loudness floor.
  The same gain processor feeds enrollment, wake testing and live wake recognition.
- Short complete phrases with sufficient identity evidence can pass. Repeating the exact phrase
  twice is supported to collect more voice evidence; partial phrases and extra commands cannot wake.
- Microphone read failures, stalls, cancellation, stale callbacks and audio teardown are handled.
- Wake diagnostics explain phrase mismatch, insufficient evidence, model failure and media pause.
- The background assistant uses the small Indian-English grammar-compatible model independently
  of the optional large-model training setting, avoiding an incompatible wake grammar/model pair.
- Owner wake responds with a short 'Yes?' before listening for the command.
- SecureStore replaces encrypted records atomically; failed writes retain the existing file.
- Training progress has a bounded, versioned format; incompatible old partial training restarts.

## Settings protection
Changing strictness, clearing identity, adding/removing alternate phrases and saving owner
training require explicit approval followed by device authentication. Without a configured device
PIN/pattern/password, these operations are blocked. Normal voice wake itself requires no unlock
once IRIS is running and enrolled. General command training and profile imports preserve wake
identity. Background workers cannot inherit a settings-write approval. AGENTS.md records the
owner's requirement to ask before future wake-policy changes; it cannot prevent a repository owner
or arbitrary code writer from replacing the implementation.

## Try on your phone
1. Grant microphone/notification permissions and start IRIS while unlocked. A device PIN,
   pattern or password is required to authorize protected settings changes.
2. Training > Set Up Wake Phrase. Choose a distinct phrase, ideally 3–5 familiar words.
3. For samples 1–3 say the full phrase twice naturally; for 4–6 repeat it softly or whisper close
   to the same phone microphone. Wait for the countdown and leave a short pause at the ends.
4. Review the result. Save with device authentication. If quiet samples fail, retry them or
   explicitly save normal voice only. The old enrollment stays intact if you cancel or validation fails.
5. Use Test wake phrase. Try normal voice, soft voice and whisper separately. Read the diagnostic.
6. Start IRIS, lock the phone, then say the full phrase, wait for 'Yes?', and speak your command.
   If a very short phrase lacks voice evidence, repeat the full phrase twice with a short pause.
7. Test with another speaker and background noise. Do not weaken strictness merely to force a pass.

Wake remains paused during phone media playback to avoid repeating the earlier video-trigger bug.
A recording of the owner's voice from another device cannot be guaranteed distinguishable from live
speech. Whisper recognition varies by microphone, distance, speech model and noise; near-silent
or unverifiable input stays asleep. Android microphone privacy controls, force-stop and OEM power
restrictions can prevent background listening. This release does not change call/SMS/email execution
or promise all third-party actions can run over a secure lock screen.

## Validation
Offline regression suites include quiet signal handling, silence/noise, clipping, phrase boundaries,
missing/mismatched speaker embeddings, threshold bounds and approval-scope isolation. APK build and
lint run in GitHub Actions. Physical-device acoustic/locked-screen validation is still required.

Commit: fix: protect owner wake and improve quiet enrollment (8.17.0)
