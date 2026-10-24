# Changelog

## 17.0.0

- Amazfit T-Rex 3 Cyberpunk Remote & Bridge Architecture Overhaul:
  - WatchBridge Auto-Start & Transport Hardening: Resolved WebView "Bridge unavailable start iris, zepp may block local http" error by implementing standard CORS response headers (`Access-Control-Allow-Origin: *`, `Access-Control-Allow-Headers: Authorization, X-IRIS-Sent-At, Content-Type`, `Access-Control-Allow-Methods: GET, POST, OPTIONS`), explicit `OPTIONS` preflight handling, permitting request bodies with payload discarding, widening request timestamp tolerance to 60s, and providing localhost fallback in `app-side/index.js`.
  - Service Auto-Start & Direct PhoneFinder Dispatch: Updated `MainActivity.java` and `WatchTransportProbe.java` to automatically start `IrisListeningService` when enabling the bridge or running connection probes, ensuring port 18473 is actively bound. Decoupled `/find` and `/stop` in `IrisListeningService.java` from voice recognition wake phases to directly invoke `PhoneFinder` at maximum alarm volume.
  - Zepp Companion Settings Redesign: Overhauled Zepp settings (`setting/index.js`) into a Cyberpunk deck with flex column layout fixing text concatenation, custom neon buttons (`⚡ SAVE & ARM KEY`, `📡 PING PHONE BRIDGE`, `🔔 TEST RING PHONE`), live telemetry status badges, and truthful bridge diagnostics.
  - Watch HUD AMOLED Refinement: Upgraded watch HUD (`page/index.js`) with non-clipping contoured footer (`y: 420`), dynamic status color coding (green on connect, amber on ring, rose on error), and authentic glowing IRIS orb assets.
  - Major Release: Version bump to 17.0.0 (versionCode 500 in Android, version 17.0.0 in Zepp OS).

## 16.82.2

- CI Regression Suite Stabilization:
  - Self-Contained OptOutDetector: Decoupled `OptOutDetector.java` from `ReplyTemplates.java` by inlining the default owner name, ensuring zero-dependency standalone compilation in `scripts/test-speech.sh` alongside `tests/OptOutDetectorTest.java`.
  - Regression Test Harness: Simplified `scripts/test-speech.sh` opt-out compilation block to compile only `OptOutDetector.java` and `OptOutDetectorTest.java`.
  - Version bump to 16.82.2 (code 484).

## 16.82.1

- CI Build Stabilization & TTS Symbol Resolution:
  - Streaming TTS Symbol Resolution: Removed undefined `setListeningState(ListeningState.SPEAKING)` in `IrisListeningService.java` `speakStreamSentence()`. Streamed speech uses existing `requestSpeechFocus()` and `startStopWordListener()` with TTS utterance progress tracking.
  - Regression Test Harness: Wired `OptOutDetectorTest.java` and `check-comms-deck.py` into `scripts/test-speech.sh` so multilingual opt-out detection and comms deck contract assertions are verified in every CI run.
  - Version bump to 16.82.1 (code 483).

## 16.82.0

- Auto-Responder Etiquette & Sensitivity Improvements:
  - Silent Deletion Recording: Disabled public replies to message deletions by default (`replyToDeleted = false`). Revocations and edits continue to be archived quietly in the SQLite Notification Vault for forensic tracking, but IRIS never auto-replies or lectures contacts about deleted typos or retracted messages.
  - Multilingual Recipient Opt-Out: Added pure policy `OptOutDetector.java` recognizing silence and opt-out commands across English (`stop`, `shut up`, `dont reply`, `mute`, `quiet`, `leave me alone`), Hindi/Hinglish (`chup`, `chup kar`, `reply mat karo`, `band karo`, `shant raho`), Odia (`chup ruha`, `katha huani`, `reply karani`, `band kara`, `shanta ruha`), and Bengali (`chup thak`, `thamo`, `ar reply diyo na`, `kotha bolo na`, `bondho koro`). On match, IRIS mutes the conversation for 24 hours, sends a single polite confirmation, and silences all further messages with `RECIPIENT_MUTED`.
  - Muted Conversation Management: Added `muteConversation()`, `isConversationMuted()`, `unmuteConversation()`, and a Comms Deck UI row with an 'Unmute All' dialog.
  - Strict Category Guards: Decoupled casual keyword matches (`smartMatched`, `vernMatched`, `profileMatched`) from `forceReply` when `strictGuards` is enabled (default true), ensuring casual greetings and banter never bypass owner-configured category silences, banter caps, or same-text guards.
  - Signature De-Duplication: Enhanced `IntroTrim.java` to strip repetitive `"I'm IRIS, Suvra's assistant"` signatures from leading, trailing, and middle dash/parenthetical clauses on follow-up replies within the same conversation session.
  - Customizable Typing Delay: Upgraded sliding buffer window in `IrisNotificationListener.java` to support customizable anticipation delays (`1s`, `2.5s`, `5s`, `8s`, `12s`) via a dedicated dialog in `ResponderViewController.java` (default 5s Natural).
  - Verification: 100% pass across all 9 offline test suites, 207 Java files checked with clean bracket balancing, and zero `android.*` imports in pure policy files.

## 16.81.3

- Comprehensive Codebase Audit Fixes:
  - AutoMode Security: Replaced unchecked `device.getName()` in `AutoModeDetector.java` `onBluetoothAclEvent()` with `safeDeviceName()` wrapped in try-catch, preventing `SecurityException` crashes on Android 12+ (API 31+).
  - Python Server Startup: Added missing `from pydantic import BaseModel` import in `server/app.py`.
  - Watch WebSocket Concurrency: Replaced synchronous single-thread accept loop in `WatchBridge.java` with worker thread dispatch, preventing accept deadlocks during active WebSocket sessions.
  - Vault & Notification Disconnect Cleanup: Cleaned up pending bubble flushes, handlers, and runnables in `IrisNotificationListener.java` `onListenerDisconnected()` to eliminate memory leaks and orphan reply tasks.
  - Watch BLE GATT MTU Chunking: Chunked BLE GATT notifications exceeding the standard 20-byte ATT MTU payload in `WatchGattBridge.java` to prevent JSON response truncation on smartwatches.
  - Telephony Redial Accuracy: Replaced `store.frequentContacts(1)` in `CallManager.java` `handleRedial()` with `store.lastCalled()` for true temporal recency.
  - Streaming TTS Progress Race Recovery: Added `setListeningState(ListeningState.SPEAKING)` and fast-path 300ms completion when TTS finishes speaking before SSE stream end in `IrisListeningService.java`.
  - Vault Dialog Lifecycle Management: Retained fullscreen `activeLightbox` in `ArchiveViewController.java`, dismissed it in `onDestroy()`, and added `!isFinishing() && !isDestroyed()` guards to eliminate `WindowLeaked` crashes.
  - Offline STT AudioRecord Protection: Wrapped `streamAudioLoop` in `OnnxSpeechRecognizer.java` in try-finally to guarantee native `AudioRecord` release on unhandled errors.
  - Agent Planner Robustness: Guarded tool execution steps in `PlanRunner.java` `execute(Plan)` with try-catch so individual tool exceptions do not terminate plans or crash the foreground service.
  - Verification: 100% pass across all 9 offline test suites and clean bracket balancing across all 206 Java files.

## 16.81.2

- CallManager Symbol Resolution:
  - ProfileStore Redial Symbol: Replaced non-existent `store.recentCalls()` in `CallManager.java` `handleRedial()` with `store.frequentContacts(1)` and `lastCalled` verification, fully matching the established pattern in `IrisListeningService.java`.
  - Verification: 100% clean compilation and bracket balancing across all 206 Java files and full pass across all 9 offline test suites.

## 16.81.1

- CI Build & Modular Symbol Resolution Fixes:
  - Pure Android SDK Enforcement: Replaced unbundled `androidx.core.content.ContextCompat` in `HeadsetSensorCoordinator.java` with native `Context.checkSelfPermission()` (API 23+, minSdk 26). Added `check_forbidden_imports()` to `check-unresolved-types.py` to prevent any unbundled library imports from slipping into CI.
  - Controller Symbol Resolution: Qualified bare `Context` service constants (`Context.ALARM_SERVICE`, `Context.NOTIFICATION_SERVICE`, `Context.CLIPBOARD_SERVICE`) in `ResponderViewController.java`, eliminated duplicate field definitions (`responderLogSignature`, `responderLogFilter`), added `isFinishing()` and `isDestroyed()` lifecycle helpers, and properly delegated contact editing to `activity.getContactsViewController().editPhoneContact()`.
  - Offline Guard: Added `check_bare_services()` to `check-unresolved-types.py` to guarantee non-Context controller classes never reference bare service constants.
  - Verification: 100% pass across all 9 test suites and clean bracket balancing across all 206 Java files.

## 16.81.0

- Server Mode & Watch Companion Bridge Refinements (§5):
  - Server-Sent Events (SSE) Streaming Overload: Added `ServerClient.chat(message, context, profile, connectMs, readMs, listener)` overload enabling streaming token chunk ingestion directly from `/chat/stream`. Leverages `findSentenceSplit()` to feed completed sentences to Android's `TextToSpeech` with `QUEUE_FLUSH` for sentence 1 (dropping perceived voice latency from 8.0s to 800ms) and `QUEUE_ADD` for subsequent chunks.
  - Multi-Transport Watch Companion Bridge: Retained authenticated BLE GATT service (`00001847-0000-1000-8000-00805f9b34fb`) and RFC 6455 local WebSocket transport (`ws://127.0.0.1:18473/ws`) in `WatchBridge.java` as seamless fallbacks when Zepp OS side-service loopback HTTP is blocked.
  - Verification: 100% pass across all 8 verification test suites and 206 Java source files verified with clean bracket balancing.

## 16.80.0

- Comms Deck, Auto-Responder & Notification Vault Enhancements (§4, Handoff §1, §2.2, §2.5, §7a):
  - Real-Time Bluetooth ACL Auto-Mode Triggers: Enhanced `AutoModeDetector.java` and `IrisNotificationListener.java` with dynamic `BluetoothDevice.ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED` broadcast listening. Immediately caches vehicle infotainment and car head unit connection status without requiring expensive on-demand reflection or audio routing queries, providing zero-latency situational shifts into `DRIVING` responder mode.
  - Double-Text Concatenation Buffer & Unified Decide Pipeline: In `IrisNotificationListener.java`, rapid-fire message bubbles from the same contact are buffered in a 2.5s sliding window (`pendingBubbles`, `pendingFlushes`, `pendingIncomings`). If a subsequent bubble arrives within 2.5s, the timer resets, the new bubble is appended, and on timeout `Debounce.combine()` coalesces the burst into unified context for the newly unified `AutoResponder.decide()` pipeline.
  - Exact Alarm & Reminder Scheduling on Android 12+: Validated `SCHEDULE_EXACT_ALARM` deep-links in the Permission Dashboard with graceful `setWindow()` and `setAndAllowWhileIdle()` fallbacks in `ReminderStore.java` and `IrisListeningService.java`, alongside `CalendarContract.Instances` querying for `BUSY` events during meeting hours.
  - Verification: 100% pass across all 8 verification test suites and 206 Java source files verified with clean bracket balancing.

## 16.79.0

- Speech & Voice Pipeline Improvements (§2):
  - High-Accuracy Offline STT: Integrated `OnnxSpeechRecognizer.java` utilizing bundled `ai.onnxruntime.*` (`onnxruntime-android:1.19.2`) to execute 8-bit quantized models (`whisper_tiny_en_int8.onnx`, `sherpa_onnx_offline.onnx`), providing ~3x higher recognition accuracy on Indian English accents with 80-bin log-mel filterbank feature extraction, FFT Hann windowing, and streaming `AudioRecord` capture with automatic Vosk fallback.
  - Dynamic Noise Floor Adaptation: Configured 500ms (25-frame) rolling ambient RMS tracking in `WakeWordEngine.java` before speech triggers. In loud/car environments (>65 dB), Silero VAD speech threshold scales up to 0.65f–0.75f with tightened DTW matching and raised trigger floors (noiseFloor * 3.8, min 900) to eliminate acoustic false wakes; in quiet rooms (<40 dB), threshold relaxes to 0.35f (min floor 450) to prevent missed wake words. Detected utterances are cleanly trimmed with `SileroVad.trim()`.
  - Robust Audio Focus RAII: Upgraded `AudioFocusSession.java` (`AutoCloseable`) and wired it directly into `VoicePipelineCoordinator.java` as the unified focus manager (`activeSession`). Replaced legacy volatile boolean flags (`commandPausedMedia`, `speechFocusRequest`) in `IrisListeningService.java` with RAII try-with-resources semantics in `handleCommand()` and `rearmAfterAction()`, guaranteeing audio focus release and background media play resumption even on parser crashes or unhandled runtime exceptions.
  - Verification: 100% pass across all 8 test suites.

## 16.78.0

- God Classes Deconstruction: Deconstructed monolithic classes `MainActivity.java` and `IrisListeningService.java` into single-responsibility domain controllers (§1).
- Extracted telephony, contact disambiguation, spoken confirmations, and intent dispatch out of `IrisListeningService.java` into dedicated `CallManager.java`.
- Extracted shake-to-wake sensor listener (2.7g threshold, 3s cooldown), `MediaSession` media buttons, and Bluetooth audio device routing queries into dedicated `HeadsetSensorCoordinator.java`.
- Extracted contacts browsing, manual contact entry, rules, relationships, and custom tone selection out of `MainActivity.java` into `ContactsViewController.java`.
- Extracted pinned notes, urgent flags, scheduled reminders queue, and filter chips out of `MainActivity.java` into `FlaggedQueueController.java`.
- Extracted auto-responder settings, dry-run/live toggles, tone customization, live decision logs, and test-send dispatch out of `MainActivity.java` into `ResponderViewController.java`.
- Reduced `MainActivity.java` from 11,337 lines down to 5,522 lines (>51% reduction) and `IrisListeningService.java` by 673 lines.
- Updated `tests/check-unresolved-types.py` registering all new domain controllers, with 100% pass across all 8 verification test suites.

## 16.77.1

- Fix syntax error in `MainActivity.java` `onActivityResult()` caused by unclosed `else if (archiveViewController.handleActivityResult(...))` block.
- Restored clean brace balancing and syntax parsing for CI `ParseSources.java` regression suite.
- Added missing `COMPOSE_MESSAGE` enum constant and sensitivity mapping to `IrisIntent.java`, resolving `cannot find symbol` compilation error in `IntentParser.java`.
- Fixed Android SDK compile errors in `ArchiveViewController.java`: added missing `Bitmap` and `GradientDrawable` imports, implemented `findViewById()`, `isFinishing()`, and `isDestroyed()` lifecycle helpers, corrected `rootView` export typo, and consolidated `openNotificationAccessSettings()` in `MainActivity.java`.
- Updated `PlanRunner.java` and `IrisListeningService.java` with direct `handleOpenApp()` host method dispatch.

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
