# Voice stability patch — 11.0.1 (341)

Main reviewed: db6fdf8bd6bb47614abfd9603947accd12bb569b (merged 11.0.0).

## Code defects addressed

- ECAPA readiness used the same monitor as model creation and inference. UI readiness polling could block behind native work. Readiness and closing state are now volatile; the check does not acquire the inference monitor. Session use/release remains serialized.
- Vosk recorded transcription and speaker extraction did not consistently hold the lifetime lock used by asynchronous model cleanup. Cancellation could release the model during a native call. Both entry points now hold that lock through recognizer release.
- Wake drain exceptions inside the scheduled executor could be retained in an unread Future. Recoverable runtime failures now invalidate the session, record metadata and notify the service to restart capture. Fatal errors retain normal Android crash handling.
- Late microphone-silencing events cannot revive a closed session. Pending drains also exclude silenced/failed input.

## Evidence for the next device attempt

Logs → Crash & wake report runs off the UI thread and offers a manual Copy report action after authentication. It includes version/device, a bounded recent metadata history, PCM arrival/energy, closest streaming-candidate distance and per-model/combined owner scores. Java crashes save stack locations and exception types, excluding exception messages, in a bounded app-private no-backup file. Android 11+ exit history distinguishes Java/native crashes, ANRs, low-memory termination and stops where Android provides a record. There is no upload or audio/transcript collection. Native tombstones and ANR traces are not collected by this feature.

The report is diagnostic, not proof of the reported crash cause. No device crash trace was supplied for this change. Saved voice evidence, matching policy and thresholds remain unchanged; retraining is not required for this patch. Recognition accuracy and hardware crash resolution remain unverified until a device trial/report.

## Validation

Regression tests cover readiness while the native monitor is deliberately held, one-shot recovery notification for worker exceptions, fatal-error propagation, bounded metadata and omission of exception messages. Run scripts/test-speech.sh, Android unit tests and APK build before delivery.
