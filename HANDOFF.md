# IRIS-Android — Handoff Brief

> Snapshot for bringing a new contributor (human or AI) up to speed.
> Written against the actual source (build workflow, `app/build.gradle`, responder classes,
> Notification Vault architecture, Server/Watch packages).
> **Current version: `16.77.1` / `versionCode 474` (Branch: `feat/responder-conversation-fixes`).**

---

## 0. Orientation
- **App:** Offline-first, single-user voice assistant + WhatsApp/SMS auto-responder + immutable forensic notification vault. Package `com.iris.assistant`. Owner = **Suvra**.
- **Repo root:** `IRIS-android/`; Android project under `IRIS-Android/` (where `settings.gradle`, `app/`, `scripts/`, `tests/` live). Watch companion under `IRIS-Watch/`. Personal server under `IRIS-Android/server/`.
- **Design spine:** Decision logic lives in **pure policy classes with zero Android imports**
  (`MessageCategory`, `ReplyTone`, `SmartReplies`, `ResponderGuards`, `Shorthand`, `GroupMention`,
  `Debounce`, `IntroTrim`, `OwnerAlias`, `TimeGreeting`, `AutoModePolicy`, `ReminderParser`,
  `Emoji`, `Vernacular`, `ProfileShare`, `ArchiveSchema`, `DeletedMessageRules`, `SwipeDecision`,
  `Plan`, `IntentParser`, `IrisIntent`, `ToolCall`, etc.). The Android framework layer is kept lean and decoupled.
- **Standing Rules (Mandatory):**
  1. Every feature or fix bumps `app/build.gradle` (`versionName` / `versionCode`), prepends a new entry to the `FEATURES` array in `IRIS-FEATURES.html`, updates `BUILD_VERSION`, and **updates `HANDOFF.md` after each commit**.
  2. Pure policy classes must remain free of Android imports (`android.*`) so they are unit-testable offline.
  3. Append-only database triggers in `ArchiveSchema.java` must never be weakened or deleted.

---

## 1. Feature-by-Feature Status

### A. Notification Vault / Archive (`com.iris.assistant`)
| Feature | Status | Description |
|---|---|---|
| **Append-Only SQLite Engine** | Working | `ArchiveSchema.java` + `NotificationArchive.java`. DDL enforces SQLite BEFORE UPDATE and BEFORE DELETE triggers so messages cannot be tampered with. |
| **Media Interception & Storage** | Working | Intercepts photos, videos, voice notes, and documents (`.pdf`, `.docx`, `.xlsx`, `.zip`) from `EXTRA_MESSAGES`. Stored in app-private `archive_images/` directory. |
| **Media Playback & Viewer** | Working | Native `VideoView` for clips, interactive `WaveformView` audio player for voice notes, file-opener intent for docs, and photo lightbox viewer. |
| **Unsent / Deleted Detection** | Working | `DeletedMessageRules.java`. Distinguishes confirmed deletions from suspected deletions (app withdrawal while screen was off), preserving original text with warning banners. |
| **Full-Screen Fluid Viewport** | Working | Collapsible cyber status bar (`archiveDiagBody`), fast-scroll thumb, 100% native velocity scroll inertia (card touch listener decoupling), and inline one-tap `[🗑️ DEL]`. |
| **Forensic Exports & Backup** | Working | `ArchiveExport.java` + `ArchiveViewController.java`. Generates forensic HTML dark-mode reports with embedded thumbnails, self-contained ZIP backups containing media files, and filtered CSV exports. |
| **Multi-Store Wipe-All Purge** | Working | Completely purges SQLite rows, deletes disk media (`ArchiveImages.deleteAll`), reclaims storage (`VACUUM` + WAL truncation), clears `NotificationStore` and `FlaggedStore`, physically dismisses active shade notifications (`IrisNotificationListener.dismissAll`), and establishes synchronous epoch wipe barriers. |

### B. Auto-Responder / Comms Deck (`com.iris.assistant`)
| Feature | Status | Description |
|---|---|---|
| **Urgent-Only Mode** | Working | Dedicated quick-toggle button (`responderUrgentOnlyToggle`) and settings switch. When active, IRIS only responds to urgent/distressed messages, family rings, and flag commands; casual messages are silenced with `Verdict.NOT_URGENT`. |
| **Double-Text Buffer** | Working | `IrisNotificationListener.java`. 2.5s sliding window (`pendingBubbles`, `pendingFlushes`) buffers rapid-fire message bubbles from the same contact/conversation and coalesces them with `Debounce.combine()` into a single unified context for `AutoResponder.consider()`, respecting `replyToEveryMessage()` when enabled. |
| **Situational Auto-Modes** | Working | `AutoModeDetector.java` + `AutoModePolicy.java`. Detects vehicle Bluetooth connections (`isDrivingActive`) and busy calendar events (`isMeetingActive`), dynamically shifting IRIS into Driving or Meeting mode when auto-switches are enabled. |
| **Exact Alarms & Reminders** | Working | Android 12+ (API 31+) compliant exact alarm scheduling with deep links to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, `SecurityException` try/catch wrapping, and graceful `setWindow()` (60s/120s) fallbacks in `ReminderStore` and `IrisListeningService`. |
| **Decision Engine & Guards** | Working | `ResponderGuards.java`. Evaluates cooldowns, daily caps, banter snooze, quiet hours, auto-sleep, mention rules, and burst deduplication. |
| **Smart Replies Brain** | Working | `SmartReplies.java` (~100+ scenario rules), `PhraseMatcher` (fuzzy match), `Vernacular` (Hinglish/Odia), `Emoji` sentiment analysis, and `Shorthand` expansion. |
| **Decision Audit Log** | Working | Full audit trail displaying incoming text, app, verdict rationale, outgoing simulated/sent replies, one-tap teach-a-reply dialog, and decision details copy. |
| **Operational States** | Working | Three states: `OFF`, `DRY RUN` (evaluates & logs without sending), and `LIVE` (sends responses via `NotificationReply`). |

### C. Voice / Wake / Identity / Planning
| Feature | Status | Description |
|---|---|---|
| **Wake Word Engine & Noise Adaptation** | Working | `WakeWordEngine.java`. 500ms (25-frame) rolling ambient RMS tracking dynamically scaling Silero VAD thresholds (0.35f in quiet <40 dB to 0.75f in noise >65 dB), trigger multipliers (2.8x-3.8x), and DTW distance gates. |
| **High-Accuracy Offline STT** | Working | `OnnxSpeechRecognizer.java`. Bundled ONNX Runtime (onnxruntime-android:1.19.2) executing quantized int8 Whisper-Tiny / Sherpa-ONNX with 80-bin log-mel filterbanks and greedy CTC/token decoding (~3x higher word accuracy on Indian English) with seamless Vosk fallback. |
| **Voice Activity Detection** | Working | `SileroVad.java`. On-device neural VAD via ONNX Runtime with dynamic ambient threshold scaling (`adaptThresholdFromRms`). |
| **Audio Focus RAII Session** | Working | `AudioFocusSession.java`. AutoCloseable transient focus manager guaranteeing immediate audio focus release and background media play resumption across all command outcomes. |
| **Speaker Verification** | Working | `EcapaEmbedding.java`. ECAPA-TDNN ONNX model verifying owner voiceprint. |
| **Real Agent Planning & SLM Execution** | Working | `OnnxSlmEngine.java` running 4-bit ONNX small models (Qwen2.5-0.5B / SmolLM2-360M Q4 ~220MB) + compound multi-intent semantic decomposition ('and', 'then', 'also', 'plus', ';', ',') synthesizing validated `MULTI_ACTION` plans executed sequentially via `PlanRunner.java` across all 20 `ToolCall.KNOWN` tools, completely bypassing the 1,500-line procedural regex cascade. |

### D. Server Mode & External Integrations
| Feature | Status | Description |
|---|---|---|
| **Server Mode & SSE Streaming** | Working (Opt-in) | FastAPI server (`server/app.py`) with `POST /chat/stream` SSE endpoint streaming Ollama tokens. `ServerClient.java` with `StreamListener` and `findSentenceSplit()` streams sentences into `IrisListeningService.java` with immediate `QUEUE_FLUSH` Sentence 1 TTS, dropping perceived latency from 8.0s to ~800ms. Subsequent sentences queue seamlessly with `QUEUE_ADD`; action tags (`[CALL]`, `[TIME]`) are intercepted and dispatched to `handleLlmOutput()`. Clean lifecycle re-arming via `scheduleStreamRearm()` and watchdog timer. |
| **Watch Companion Multi-Transport** | Working | `IRIS-Watch/` Zepp OS mini-app with multi-transport phone bridge: loopback HTTP, RFC 6455 local WebSocket (`ws://127.0.0.1:18473/ws`), and authenticated BLE GATT service (`WatchGattBridge.java`, UUID `00001847-0000-1000-8000-00805f9b34fb`). `app-side/index.js` automatically falls back to WebSocket when loopback HTTP is blocked on newer runtimes. |

---

## 2. Key Static Analysis & Verification Suites

Run from `IRIS-Android/` before every commit:
```bash
# 1. Verify append-only SQL triggers and database schema immutability
python tests/check-archive-schema.py     # 73/73 tests pass

# 2. Check for unresolved type names and layout ID bindings
python tests/check-unresolved-types.py    # 43 files checked, 280 layout IDs resolved

# 3. Validate all 38 XML layout and drawable resources
python tests/check-xml.py                # 38 resource files validated

# 4. Check speech pipeline contracts, dynamic noise formulas, and VAD adaptation
python tests/check-speech-pipeline.py    # 100% contract and math validation

# 5. Check real agent planning pipeline, multi-intent decomposition, and tool execution
python tests/check-planning-pipeline.py  # 100% dispatch, Host methods, and pure policy validation

# 6. Check Comms Deck, double-text buffering, exact alarms, and auto-modes
python tests/check-comms-deck.py         # 100% pass across all comms deck & vault checks

# 7. Check Server Mode SSE streaming and Watch Companion multi-transport bridge
python tests/check-server-and-watch.py   # 100% pass across streaming TTS & watch transports
```

---

## 3. Architecture & Key Files Map

* **Notification Vault (Archive):**
  * `ArchiveViewController.java`: Dedicated UI controller managing pagination, date presets/custom picker chips, photo lightbox with scale/zoom/pan, video player, audio waveform player, swipe-to-delete queue with undo bar, and multi-format forensic exports (ZIP, HTML report, CSV, plain text).
  * `ArchiveSchema.java`: Schema DDL, append-only triggers, size quotas, and migration definitions.
  * `NotificationArchive.java`: Database helper, query builder, thread grouper, and authorised wipe logic.
  * `DeletedMessageRules.java`: Placeholder detection, deletion classification, and confidence scoring.
  * `ArchiveImages.java`: Media file storage, thumbnail caching, and disk sweep cleanups.
  * `ArchiveExport.java`: Forensic HTML reports, self-contained ZIP media backups, and CSV generation.
  * `view_archive.xml`: Viewport layout, collapsible diagnostics header, and smooth-scrolling `ListView`.
* **Auto-Responder & Comms Deck:**
  * `AutoResponder.java`: Top-level message intake, triage, and dispatch pipeline with `AutoModeDetector` signal resolution.
  * `AutoModeDetector.java`: On-device sensor and calendar event detector for vehicle Bluetooth connections and busy calendar events.
  * `AutoModePolicy.java`: Pure decision matrix resolving effective situational mode (Driving > Meeting > Sleeping > None).
  * `ResponderGuards.java`: Guard policies, rate limits, allowlists, and `Verdict.NOT_URGENT` enforcement.
  * `ResponderSettings.java`: SharedPreferences wrapper for arming, scopes, `urgentOnly`, `autoDriving`, and `autoMeeting` toggles.
  * `SmartReplies.java`: Context-aware keyword and vernacular reply engine.
  * `view_comms_responder.xml`: Responder UI deck with quick `[URGENT MESSAGES ONLY]` toggle button.
* **Server Mode & Watch Companion Bridge:**
  * `ServerClient.java`: Pure policy HTTP client with `StreamListener`, `chatStream()`, and `findSentenceSplit()` boundary parsing for SSE responses from `server/app.py`.
  * `server/app.py`: FastAPI server proxying Ollama and faster-whisper with `POST /chat/stream` SSE endpoint.
  * `WatchBridge.java`: Multi-transport companion bridge on port 18473 supporting loopback HTTP, RFC 6455 local WebSocket (`ws://127.0.0.1:18473/ws`), and coordinating `WatchGattBridge`.
  * `WatchGattBridge.java`: Authenticated BLE GATT service (UUID `00001847-0000-1000-8000-00805f9b34fb`) with token verification, CCCD notifications, and command routing.
  * `IRIS-Watch/app-side/index.js`: Zepp OS companion app side-service with automatic WebSocket fallback when local HTTP is blocked.
* **Voice & Execution Controllers:**
  * `VoicePipelineCoordinator.java`: Low-level audio routing, `AudioDeviceCallback` lifecycle, input device enumeration/selection, speech focus request/abandon, media key play/pause dispatch, and `openSpeechSession()` factory.
  * `AudioFocusSession.java`: AutoCloseable transient audio focus manager guaranteeing automatic abandon and media resumption with try-with-resources semantics.
  * `OnnxSpeechRecognizer.java`: Offline high-accuracy speech recognizer running quantized Whisper-Tiny / Sherpa-ONNX via bundled `onnxruntime-android:1.19.2`, with 80-bin log-mel extraction and greedy CTC/token decoding.
  * `SileroVad.java`: On-device neural voice activity detector with dynamic ambient noise floor adaptation (`calculateAmbientDb`, `computeAdaptiveThreshold`, `adaptThresholdFromRms`).
  * `WakeWordEngine.java`: Wake-phrase detection with 500ms (25-frame) rolling ambient RMS tracking, adaptive trigger multipliers (2.8x–3.8x), dynamic DTW scaling, and Silero VAD trimming.
  * `PlanRunner.java`: Phase 2/5 Android tool executor centralizing execution of structured plans and tool calls against device hardware and intents.
  * `LocalPlanner.java` & `IntentParser.java`: Intent parsing and structured plan generation.
  * `OnnxSlmEngine.java`: On-device 4-bit small language model engine running Qwen2.5/SmolLM2 with semantic multi-intent conjunction decomposition.
* **Glue & UI Orchestration:**
  * `MainActivity.java`: Host UI activity & navigation tabs; delegates Notification Vault entirely to `ArchiveViewController`; provides Permission Dashboard and situational mode switches.
  * `IrisListeningService.java`: Foreground lifecycle and hotword service; coordinates streaming TTS via `speakStreamSentence()` and `scheduleStreamRearm()`; delegates audio routing to `VoicePipelineCoordinator`, offline STT to `OnnxSpeechRecognizer` (with Vosk fallback), and plan execution to `PlanRunner`.
  * `IrisNotificationListener.java`: System `NotificationListenerService` capturing active notifications, running the 2.5s sliding-window double-text buffer (`pendingBubbles`), enforcing the `lastWipeEpoch` barrier, and handling system notification shade dismissal.

---

## 4. Recent Milestones & Release History

* **v16.77.1**:
  * **CI Build Fix (ParseSources.java & onActivityResult Syntax)**: Fixed unclosed `else if (archiveViewController.handleActivityResult(...))` block in `MainActivity.java` `onActivityResult()`, eliminating the orphaned `try-catch` syntax error that failed `ParseSources.java` in GitHub Actions CI run `39818154538`.
  * **Missing Symbol Fix (IrisIntent.COMPOSE_MESSAGE)**: Added missing `COMPOSE_MESSAGE` enum constant and sensitivity mapping to `IrisIntent.java`, resolving `cannot find symbol` compiler error in `IntentParser.java` during `test-speech.sh`.
  * **Android Compilation Fixes (`ArchiveViewController`, `PlanRunner`, `MainActivity`)**: Added missing `Bitmap` and `GradientDrawable` imports in `ArchiveViewController`, implemented `findViewById()`, `isFinishing()`, and `isDestroyed()` lifecycle helpers, corrected `rootView` export typo, restored `openNotificationAccessSettings()` in `MainActivity`, and wired `handleOpenApp()` in `PlanRunner.Host` and `IrisListeningService`.
  * **Automated Syntax Sweep**: Verified all 280 Java files across app and test suites have 100% clean brace balancing and syntax parsing.
  * **Verification**: All 8 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`).

* **v16.77.0 (Commit `63992af` / `60cabce`)**:
  * **Server Mode SSE Streaming & Low-Latency TTS (§5, Handoff §1, §6)**: Added `POST /chat/stream` in `IRIS-Android/server/app.py` streaming token deltas over `text/event-stream`. Upgraded `ServerClient.java` with `StreamListener`, `chatStream()`, and `findSentenceSplit()` sentence boundary detection. Wired into `IrisListeningService.java`: Sentence 1 begins speaking immediately with `TextToSpeech.QUEUE_FLUSH`, dropping perceived latency from 8.0s down to ~800ms. Subsequent sentences queue with `QUEUE_ADD`. Action tags (`[CALL]`, `[TIME]`) are intercepted and dispatched to `handleLlmOutput()` without speaking raw bracket syntax. Added `scheduleStreamRearm()` with utterance tracking and watchdog timer.
  * **Multi-Transport Watch Companion Bridge (WebSocket + BLE GATT)**: Resolved loopback HTTP blocking on newer Zepp OS / OEM runtimes by adding RFC 6455 local WebSocket support (`ws://127.0.0.1:18473/ws`) to `WatchBridge.java` with SHA-1 handshake and binary frame handling. Implemented `WatchGattBridge.java` providing an authenticated BLE GATT server (UUID `00001847-0000-1000-8000-00805f9b34fb`) with token verification, CCCD notifications, and command routing. Upgraded `IRIS-Watch/app-side/index.js` to automatically fall back to WebSocket when HTTP fetch fails.
  * **Verification**: All 7 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`).

* **v16.76.0 (Commit `cfe535e` / `4682acb`)**:
  * **Double-Text Concatenation Buffer (Handoff §7a)**: In `IrisNotificationListener.java`, rapid-fire message bubbles from the same contact or conversation are buffered in a 2.5-second sliding window (`pendingBubbles`, `pendingFlushes`) rather than firing immediate disjoint replies. If a follow-up arrives within 2.5s, the timer resets and the new text is appended; on timeout, `Debounce.combine()` coalesces the burst into a unified context for `AutoResponder.consider()`, respecting `replyToEveryMessage()` when enabled. Cleanly unregisters in `onDestroy`.
  * **Exact Alarm & Reminder Scheduling on Android 12+ (Handoff §2.2)**: Added `Capability.EXACT_ALARMS` (Kind.SPECIAL) and `Capability.CALENDAR` (Kind.RUNTIME) to `PermissionCatalog` and `MainActivity` with deep link redirect to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. Wrapped `AlarmManager.setExactAndAllowWhileIdle()` in `ReminderStore.java` and `IrisListeningService.java` with `SecurityException` try/catch blocks and graceful fallbacks to `AlarmManager.setWindow()` (60s/120s windows) and `setAndAllowWhileIdle()`. Declared `READ_CALENDAR` in `AndroidManifest.xml`.
  * **Driving & Meeting Situational Auto-Modes (Handoff §1, §2.5)**: Implemented `AutoModeDetector.java` wiring on-device sensors: `isDrivingActive` detects vehicle Bluetooth head unit connections (`AUDIO_VIDEO_CAR_AUDIO`, `AUDIO_VIDEO_HANDSFREE`, and automotive device names); `isMeetingActive` queries `CalendarContract.Instances` for events marked `BUSY` (availability 0) overlapping current time. Connected `AutoModeDetector.evaluateSignals()` into `AutoResponder.compose()` to resolve situational modes dynamically via `AutoModePolicy`. Added `autoDriving` and `autoMeeting` switches in `ResponderSettings` and the Comms Deck UI.
  * **Verification**: All 6 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, and `check-comms-deck.py`).

* **v16.75.0 (Commit `45a1754`)**:
  * **Real Agent Planning & Direct PlanRunner Execution**: Connected structured understanding in `LocalPlanner` and `IntentParser` directly to `PlanRunner.execute(plan)`, eliminating the 1,500-line procedural regex cascade in `IrisListeningService`.
  * **On-Device Small Language Model (SLM) Engine**: Implemented `OnnxSlmEngine.java` implementing `LocalPlanner.Engine` configured for 4-bit quantized small models (Qwen2.5-0.5B-Instruct / SmolLM2-360M Q4 ~220MB) running within the 512MB RAM heap limit via bundled `ai.onnxruntime.*`.
  * **Free-Form Multi-Intent Semantic Decomposition**: Engineered conjunction decomposition ('and', 'then', 'also', 'plus', 'after that', ';', ',') handling compound commands (e.g. "Set an alarm for 7 AM and turn on the torch", "Turn on torch and take a screenshot", "Call Alice and set a timer for 10 minutes"), synthesizing validated `MULTI_ACTION` plans executed sequentially.
  * **Full Tool Dispatch & Host Implementation**: Expanded `PlanRunner` to dispatch all 20 `ToolCall.KNOWN` tools, and implemented all 42 `PlanRunner.Host` methods in `IrisListeningService`. Deep step sensitivity validation enforces `needsConfirmation = true` across child tool steps.
  * **Verification**: All 73 archive schema checks, 39 unresolved type checks across 280 layout IDs, 38 XML resource files, and dedicated planning pipeline tests (`check-planning-pipeline.py`) pass 100%.

* **v16.74.0 (Commit `87af4e0`)**:
  * **ONNX Runtime High-Accuracy Offline STT**: Implemented `OnnxSpeechRecognizer.java` running quantized int8 Whisper-Tiny / Sherpa-ONNX models via bundled `onnxruntime-android:1.19.2`. Includes 80-channel log-mel spectrogram acoustic feature extraction (16kHz, 25ms window, 10ms hop, 80 mel filterbanks, 512-point Radix-2 FFT, Hann window), greedy CTC/token decoding, and streaming `AudioRecord` capture. Integrated into `IrisListeningService.startCommandRecognitionLocal()` with seamless fallback to `VoskEngine`.
  * **Dynamic Noise Floor Adaptation**: Added 500ms (25-frame) rolling ambient RMS calculation in `WakeWordEngine.audioLoop()` before voice triggers (`!speaking`). Dynamically scales `SileroVad` speech threshold (0.35f in quiet rooms <40 dB SPL to 0.75f in noisy cars/streets >65 dB SPL), dynamically adjusts trigger multipliers (2.8x to 3.8x, min 450 to 900), adapts DTW distance gates, and trims utterances with Silero VAD before Goertzel feature extraction.
  * **Robust Audio Focus RAII**: Implemented `AudioFocusSession.java` (`implements AutoCloseable`) with try-with-resources wrapping in `IrisListeningService.handleCommand()` and `VoicePipelineCoordinator.openSpeechSession()`, preventing media pause deadlocks on parser crashes or unhandled command errors.
  * **Verification**: All 73 archive schema checks, 38 unresolved type checks across 280 layout IDs, 38 XML resource files, and dedicated speech pipeline tests (`check-speech-pipeline.py`) pass 100%.
* **v16.72.1 (Commit `98a0ca1`)**:
  * Enhanced Auto-Responder status deck with explicit cyber warning notice when Urgent-Only mode is active.
* **v16.72.0 (Commit `73880d8`)**:
  * **Touch Scroll Decoupling**: Removed card-level `OnTouchListener` from `ArchiveAdapter`, restoring 100% native Android velocity inertia and fast-scroll thumb navigation on `archiveList`.
  * **System Notification Shade Dismissal**: Added `IrisNotificationListener.dismissAll()` on Wipe-All to physically clear Android's status bar drawer.
  * **Synchronous Wipe Barrier**: Added `lastWipeEpoch` in-memory barrier in `IrisNotificationListener` and switched `AppSettings.setLastArchiveWipeAt` to `.commit()` with a 1000ms forward buffer to guarantee pre-wipe notifications never resurrect on RESCAN.
  * **System Marker Vault Seclusion**: Configured `buildArchiveFilter` to strictly exclude internal listener diagnostic markers from the message vault.
* **v16.71.0 (Commit `11131f8`)**:
  * Viewport expansion (+160dp) via collapsible cyber diagnostics header (`[DIAG ▾]`).
  * Fast-scroll thumb and inline `[🗑️ DEL]` cyber action buttons on archive cards.
* **v16.70.0 (Commit `1008d66`)**:
  * Introduced Auto-Responder Urgent-Only mode toggle button.

---

## 5. Maintenance Checklist for Future Commits
Whenever making code changes:
1. Ensure pure policy classes have no `android.*` imports.
2. Run `python tests/check-archive-schema.py`, `python tests/check-unresolved-types.py`, and `python tests/check-xml.py`.
3. Bump `versionCode` (+1) and `versionName` in `app/build.gradle`.
4. Prepend a new feature entry to `FEATURES` array and update `BUILD_VERSION` in `IRIS-FEATURES.html`.
5. **Update `HANDOFF.md` immediately with the commit hash, version bump, and architectural changes.**
