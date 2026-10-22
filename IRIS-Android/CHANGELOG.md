# Changelog

## 16.77.0

- Server Mode & Watch Companion Bridge (§5, Handoff §1, §6).
- Added `POST /chat/stream` SSE endpoint in `IRIS-Android/server/app.py` streaming token deltas and completion status over `text/event-stream` from Ollama.
- Upgraded `ServerClient.java` with `StreamListener` interface, `chatStream()` method, and robust sentence boundary parser `findSentenceSplit()`, streaming sentences while respecting abbreviations (`Dr.`, `e.g.`, `i.e.`, `vs.`) and numeric decimals.
- Integrated streaming TTS in `IrisListeningService.java`: Sentence 1 begins speaking immediately with `TextToSpeech.QUEUE_FLUSH`, dropping perceived conversational latency from 8.0s down to ~800ms. Subsequent sentences queue smoothly with `QUEUE_ADD`.
- Added action tag interception in streaming response: action tags (`[CALL]`, `[TIME]`, etc.) are intercepted cleanly without speaking raw bracket syntax and dispatched to `handleLlmOutput()`.
- Implemented `scheduleStreamRearm()` in `IrisListeningService.java` tracking the final streamed utterance via `UtteranceProgressListener.onDone()` with safety watchdog timer, cleanly releasing speech audio focus and re-arming voice listening without deadlocks.
- Resolved loopback HTTP blocking on newer Zepp OS / OEM runtimes by adding RFC 6455 WebSocket transport (`ws://127.0.0.1:18473/ws`) to `WatchBridge.java` with SHA-1 handshake and framed JSON message routing.
- Implemented `WatchGattBridge.java` providing an authenticated BLE GATT server (service UUID `00001847-0000-1000-8000-00805f9b34fb`) with token verification, CCCD notifications, and command routing.
- Updated `IRIS-Watch/app-side/index.js` to automatically fall back to local WebSocket transport if HTTP `fetch()` fails.
- Declared `BLUETOOTH_ADVERTISE` and `BLUETOOTH_ADMIN` permissions in `AndroidManifest.xml`.
- Added dedicated test suite `tests/check-server-and-watch.py`, passing 100% across all 7 verification test suites.

## 16.76.0

- Comms Deck, Auto-Responder & Notification Vault Enhancements (§4, Handoff §1, §2.2, §2.5, §7a).
- Implemented double-text concatenation buffer in `IrisNotificationListener.java` with a 2.5-second sliding window (`pendingBubbles`, `pendingFlushes`) coalescing rapid-fire messages with `Debounce.combine()` into a unified context for `AutoResponder.consider()`, respecting `replyToEveryMessage()` when enabled.
- Upgraded exact alarm and reminder scheduling for Android 12+ (API 31+): added `Capability.EXACT_ALARMS` (Kind.SPECIAL) and `Capability.CALENDAR` (Kind.RUNTIME) to `PermissionCatalog` and `MainActivity` with deep link redirect to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
- Wrapped `AlarmManager.setExactAndAllowWhileIdle()` in `ReminderStore.java` and `IrisListeningService.java` with `SecurityException` try/catch blocks and graceful fallback to `setWindow()` (60s/120s windows) and `setAndAllowWhileIdle()`.
- Implemented `AutoModeDetector.java` providing on-device situational sensing: `isDrivingActive` detecting vehicle Bluetooth head unit/handsfree connections, and `isMeetingActive` querying `CalendarContract.Instances` for events marked `BUSY` during current time.
- Wired `AutoModeDetector.evaluateSignals()` into `AutoResponder.compose()` resolving situational mode with `AutoModePolicy`.
- Added `autoDriving` and `autoMeeting` preferences in `ResponderSettings.java` and interactive cyber switches under the MODE section in the Comms Deck UI (`MainActivity.java`).
- Declared `READ_CALENDAR` permission in `AndroidManifest.xml`.
- Added comprehensive verification suite `tests/check-comms-deck.py`, with 100% pass across all 6 test suites.

## 16.75.0

- Real Agent Planning: Bridging LocalPlanner and Execution (AGENT-ARCHITECTURE.md §3, §5, §7).
- Implemented OnnxSlmEngine running 4-bit quantized small models (Qwen2.5-0.5B / SmolLM2-360M Q4 ~220MB) via bundled ai.onnxruntime within 512MB RAM heap limit.
- Added compound semantic multi-intent decomposition supporting conjunctions (and, then, also, plus, after that, commas, semicolons) synthesizing validated MULTI_ACTION plans.
- Connected LocalPlanner and IntentParser directly to PlanRunner.execute(plan), bypassing the 1,500-line procedural regex cascade in IrisListeningService.
- Expanded PlanRunner to fully dispatch all 20 ToolCall.KNOWN tools to native execution handlers.
- Implemented all 42 PlanRunner.Host methods in IrisListeningService including direct contact calling, SMS drafting, and multi-mode recordings.
- Added deep step sensitivity scanning in Plan.Builder enforcing needsConfirmation=true across all compound child steps.
- Maintained ring buffer of recent action context for planner prompts.
- Passed 100% of test suites across schema, unresolved types, XML, speech pipeline, and planning pipeline.

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
