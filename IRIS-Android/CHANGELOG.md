# Changelog

## 0.2.2

- Fixed dead-end state in voice confirmation when retries exceeded — now cancels and rearms.
- Fixed AudioRecord race condition in WakeWordEngine.stop() — thread join before release.
- Added 20-second command timeout and 15-second confirmation timeout to prevent hung states.
- Added 30-second disambiguation timeout when user ignores the selection notification.
- Fixed startForeground called unconditionally to prevent ForegroundServiceDidNotStartInTimeException on Android 12+.
- Fixed TTS and speech recognition audio conflict — recognition now waits for TTS completion.
- Fixed Spinner onItemSelected firing on initial setup causing unnecessary service restarts.
- Fixed LogStore half-capacity trim splitting log lines at arbitrary character offsets.
- Fixed handler postDelayed lifecycle leaks in MainActivity — callbacks cleared in onDestroy.
- Fixed NPE in IrisTileService.onClick() when getQsTile() returns null.
- Fixed deprecated startActivityAndCollapse on API 34+ — uses PendingIntent overload.
- Added import validation for wake profiles — checks threshold bounds, template dimensions, phrase length.
- Added Notification.VISIBILITY_PRIVATE on call confirmation to hide phone number on lock screen.
- Sanitized phone number in performCall URI to use cleaned digits.
- Added partial wake lock to prevent Doze mode from throttling the listening service.
- Added WAKE_LOCK permission to AndroidManifest.
- Uses VibratorManager on API 31+ replacing deprecated Vibrator API.
- Uses 3-argument startForeground with FOREGROUND_SERVICE_TYPE_MICROPHONE on API 34+.
- Set audio thread priority to THREAD_PRIORITY_URGENT_AUDIO in WakeWordEngine.
- Added AudioRecord STATE_INITIALIZED check before startRecording.
- Cached AppSettings microphone preference in WakeWordEngine constructor.
- Optimized ProfileStore Levenshtein distance to two-row rolling array — O(n) memory.
- Paused IrisOrbView pulse animator when inactive to save CPU.
- Added phase-aware accessibility content descriptions to IrisOrbView.
- Updated GitHub Actions workflow — version-parameterized, lint step, debug/release support.

## 0.2.1

- Fixed GitHub Actions builds when the repository contains the source ZIP instead of extracted Gradle files.
- Added automatic source extraction and Android project-directory detection.
- Updated the APK and workflow artifact names to v0.2.1.
- Updated the checkout and Java setup actions for current GitHub runners.

## 0.2.0

- Replaced continuous full recognizer use in the default mode with a trainable acoustic wake detector.
- Added fully customizable three-sample wake-phrase training and transfer.
- Added Wake, Tap, and Continuous Experimental modes.
- Added on-device speech preference, offline-model request, multilingual options, and fallback status.
- Added voice confirmation, contact disambiguation, phonetic matching, corrections, and safe no-call tests.
- Added encrypted profiles/logs, biometric export protection, retention choices, and locked-call protection.
- Added automatic and manual microphone routing, BLE headset handling, and a Quick Settings tile.
- Added personality, voice, haptic, text-size, activity, and training-management controls.
- Added animated assistant phases, live transcript/level feedback, contact imagery, and frequent-call insights.

## 0.1.0

- Initial calling-only Android MVP.
