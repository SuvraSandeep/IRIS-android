# IRIS-Android — Handoff Brief

> Snapshot for bringing a new contributor (human or AI) up to speed.
> Written against the actual source (build workflow, `app/build.gradle`, responder classes,
> Notification Vault architecture, Server/Watch packages).
> **Current version: `17.2.0` / `versionCode 502` (Branch: `feat/responder-conversation-fixes`).**

---

## 0. Orientation
- **App:** Offline-first, single-user voice assistant + WhatsApp/SMS auto-responder + immutable forensic notification vault. Package `com.iris.assistant`. Owner = **Suvra**.
- **Repo root:** `IRIS-android/`; Android project under `IRIS-Android/` (where `settings.gradle`, `app/`, `scripts/`, `tests/` live). Watch companion under `IRIS-Watch/`. Personal server under `IRIS-Android/server/`.
- **Design spine:** Decision logic lives in **pure policy classes with zero Android imports**
  (`MessageCategory`, `ReplyTone`, `SmartReplies`, `ResponderGuards`, `Shorthand`, `GroupMention`,
  `Debounce`, `IntroTrim`, `OptOutDetector`, `OwnerAlias`, `TimeGreeting`, `AutoModePolicy`, `ReminderParser`,
  `Emoji`, `Vernacular`, `ProfileShare`, `ArchiveSchema`, `DeletedMessageRules`, `SwipeDecision`,
  `Plan`, `IntentParser`, `IrisIntent`, `ToolCall`, etc.). The Android framework layer is kept lean and decoupled.
- **Standing Rules (Mandatory):**
  1. **Strict Versioning Policy:**
     - **Major change / overhaul**: Increment `+1.0.0` (e.g. `16.x.x` -> `17.0.0`).
     - **Minor change or fix**: Increment `+0.1.0` (e.g. `17.0.0` -> `17.1.0`).
     - **Build failed and re-built / hotfix**: Increment `+0.0.1` (e.g. `17.1.0` -> `17.1.1`).
     - `versionCode` in `app/build.gradle` increments with every single release (`+1`).
  2. Every feature or fix bumps `app/build.gradle` (`versionName` / `versionCode`), updates watch companion versions (`IRIS-Watch/app.json` and `IRIS-Watch/package.json`), prepends a new entry to the `FEATURES` array in `IRIS-FEATURES.html`, updates `BUILD_VERSION`, and **updates `HANDOFF.md` after each commit**.
  3. Pure policy classes must remain free of Android imports (`android.*`) so they are unit-testable offline.
  4. Append-only database triggers in `ArchiveSchema.java` must never be weakened or deleted.

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
| **Silent Deletion Vaulting** | Working | Deletions are quietly archived in the SQLite Vault for owner forensics (`replyToDeleted = false` by default). Senders are never publicly messaged or called out for deleting typos or retracted texts. |
| **Multilingual Recipient Opt-Out** | Working | Pure policy `OptOutDetector.java` recognizes silence requests across English (`stop`, `shut up`, `mute`), Hindi (`chup`, `reply mat karo`), Odia (`chup ruha`, `reply karani`), and Bengali (`chup thak`, `ar reply diyo na`, `bondho koro`). Mutes conversation for 24h, sends 1 polite confirmation, and suppresses replies with `RECIPIENT_MUTED`. Includes 'Unmute All' dialog. |
| **Strict Category Guards** | Working | Decouples casual keyword matches from `forceReply` when `strictGuards` is enabled (default true), ensuring casual greetings and banter never bypass owner-configured category silences, banter caps, or same-text guards. |
| **Signature De-Duplication** | Working | Enhanced `IntroTrim.java` strips repetitive `"I'm IRIS, Suvra's assistant"` signatures from leading, trailing, and middle clauses on follow-up replies within the same conversation session. |
| **Customizable Typing Delay** | Working | Upgraded sliding buffer window in `IrisNotificationListener.java` to support customizable anticipation delays (`1s`, `2.5s`, `5s`, `8s`, `12s`) via a dedicated dialog in `ResponderViewController.java` (default 5s Natural). |
| **Urgent-Only Mode** | Working | Dedicated quick-toggle button (`responderUrgentOnlyToggle`) and settings switch. When active, IRIS only responds to urgent/distressed messages, family rings, and flag commands; casual messages are silenced with `Verdict.NOT_URGENT`. |
| **Double-Text Buffer** | Working | `IrisNotificationListener.java`. Sliding window (`pendingBubbles`, `pendingFlushes`) buffers rapid-fire message bubbles from the same contact/conversation and coalesces them with `Debounce.combine()` into a single unified context for `AutoResponder.consider()`, respecting `replyToEveryMessage()` when enabled. |
| **Situational Auto-Modes** | Working | `AutoModeDetector.java` + `AutoModePolicy.java`. Detects vehicle Bluetooth connections (`isDrivingActive`) and busy calendar events (`isMeetingActive`), dynamically shifting IRIS into Driving or Meeting mode when auto-switches are enabled. |
| **Exact Alarms & Reminders** | Working | Android 12+ (API 31+) compliant exact alarm scheduling with deep links to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, `SecurityException` try/catch wrapping, and graceful `setWindow()` (60s/120s) fallbacks in `ReminderStore` and `IrisListeningService`. |
| **Decision Engine & Guards** | Working | `ResponderGuards.java`. Evaluates cooldowns, daily caps, banter snooze, quiet hours, auto-sleep, mention rules, recipient muting, and burst deduplication. |
| **Smart Replies Brain** | Working | `SmartReplies.java` (~100+ scenario rules), `PhraseMatcher` (fuzzy match), `Vernacular` (Hinglish/Odia), `Emoji` sentiment analysis, and `Shorthand` expansion. |
| **Decision Audit Log** | Working | Full audit trail displaying incoming text, app, verdict rationale, outgoing simulated/sent replies, one-tap teach-a-reply dialog, and decision details copy. |
| **Operational States** | Working | Three states: `OFF`, `DRY RUN` (evaluates & logs without sending), and `LIVE` (sends responses via `NotificationReply`). |
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
| **Amazfit T-Rex 3 Remote & Bridge** | Working | `IRIS-Watch/` Zepp OS 4.0 mini-app with hardened loopback HTTP (`WatchBridge.java` with full CORS preflight and headers, payload consumption, 60s clock skew tolerance, and RFC 6455 local WebSocket fallback). Contoured 480x480 AMOLED HUD with glowing IRIS logo (`assets/trex3/icon.png` & `iris_logo.png`), human-friendly action buttons (Battery, Ring Phone, Silence, Talk), companion settings with live bridge ping, and dedicated glowing accent card in Android Settings. |

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
  * `ResponderViewController.java`: Dedicated UI controller managing auto-responder rules, dry-run/live toggles, tone settings, situational modes, test-send dispatch, and live decision log auditing with filter chips.
  * `ContactsViewController.java`: Dedicated UI controller managing phonebook contact browsing, manual contact rule entry, relationship tagging, and custom tone selection.
  * `FlaggedQueueController.java`: Dedicated UI controller managing pinned notes, urgent flags, scheduled reminders queue, filter chips (All/Pinned/Flagged/Reminders), and manual reminder entry.
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
  * `CallManager.java`: Dedicated telephony controller encapsulating fuzzy contact matching, soundex/token similarity, phone number extraction, call timing/night warnings, spoken verbal confirmations, disambiguation broadcasts, and direct dialer intents with lockscreen credential fallback.
  * `HeadsetSensorCoordinator.java`: Hardware sensor coordinator managing shake-to-wake sensor listener (2.7g threshold, 3s cooldown), `MediaSession` media button listener with pass-through callback, and Bluetooth audio device connection/summary querying.
  * `VoicePipelineCoordinator.java`: Low-level audio routing, `AudioDeviceCallback` lifecycle, input device enumeration/selection, speech focus request/abandon, media key play/pause dispatch, and `openSpeechSession()` factory.
  * `AudioFocusSession.java`: AutoCloseable transient audio focus manager guaranteeing automatic abandon and media resumption with try-with-resources semantics.
  * `OnnxSpeechRecognizer.java`: Offline high-accuracy speech recognizer running quantized Whisper-Tiny / Sherpa-ONNX via bundled `onnxruntime-android:1.19.2`, with 80-bin log-mel extraction and greedy CTC/token decoding.
  * `SileroVad.java`: On-device neural voice activity detector with dynamic ambient noise floor adaptation (`calculateAmbientDb`, `computeAdaptiveThreshold`, `adaptThresholdFromRms`).
  * `WakeWordEngine.java`: Wake-phrase detection with 500ms (25-frame) rolling ambient RMS tracking, adaptive trigger multipliers (2.8x–3.8x), dynamic DTW scaling, and Silero VAD trimming.
  * `PlanRunner.java`: Phase 2/5 Android tool executor centralizing execution of structured plans and tool calls against device hardware and intents.
  * `LocalPlanner.java` & `IntentParser.java`: Intent parsing and structured plan generation.
  * `OnnxSlmEngine.java`: On-device 4-bit small language model engine running Qwen2.5/SmolLM2 with semantic multi-intent conjunction decomposition.
* **Glue & UI Orchestration:**
  * `MainActivity.java`: Host UI activity & navigation tabs; delegates Comms Deck sections entirely to domain controllers (`ArchiveViewController`, `ResponderViewController`, `ContactsViewController`, `FlaggedQueueController`); provides Permission Dashboard and situational mode switches.
  * `IrisListeningService.java`: Foreground lifecycle and hotword service; coordinates streaming TTS via `speakStreamSentence()` and `scheduleStreamRearm()`; delegates audio routing to `VoicePipelineCoordinator`, headset sensors to `HeadsetSensorCoordinator`, telephony to `CallManager`, offline STT to `OnnxSpeechRecognizer` (with Vosk fallback), and plan execution to `PlanRunner`.
  * `IrisNotificationListener.java`: System `NotificationListenerService` capturing active notifications, running the 2.5s sliding-window double-text buffer (`pendingBubbles`), enforcing the `lastWipeEpoch` barrier, and handling system notification shade dismissal.

---

## 4. Recent Milestones & Release History

* **v17.2.0 (Commit `b05171f`)**:
  * **Watch Transport Probe Crash Elimination**: Removed illegal background `startForegroundService()` calls in `WatchTransportProbe.java` that triggered `ForegroundServiceStartNotAllowedException` on Android 14+ (targetSdk 35). Added safe service readiness polling on port 18473 and UI try/catch guards in `MainActivity.java`.
  * **Settings Tab Detachment Isolation**: Fixed `NullPointerException` and detached view updates in `updateAssistantState()` when starting IRIS from Settings tab (tab 4). Added strict `selectedTab == 0` guards and null checks across all UI status text views.
  * **Bulletproof 64-Hex Pairing Key Extraction**: Added `extractHexKey()` across Zepp companion `setting/index.js` and `app-side/index.js` to automatically extract the true 64-hex key even if the user copies whole dialog sentences, trailing clipboard newlines (`\r\n`), spaces, quotes, or `Bearer ` prefixes.
  * **Key Auto-Generation on Access**: Updated `WatchBridge.token()` to automatically generate and save a secure 64-hex key if none exists yet, eliminating empty keys.
  * **Strict Semantic Versioning**: Minor fix increment (+0.1.0) to version `17.2.0` (`versionCode 502` in Android, version `17.2.0` / code `19` in Zepp OS).

* **v17.1.0 (Commit `d848d34`)**:
  * **Watch Bridge Dialog Items Restoration**: Resolved Android `AlertController` issue where `showWatchSetup()` in `MainActivity.java` called `.setMessage()` alongside `.setItems()`, concealing the action list items. Removed `.setMessage()` to restore full visibility of list options ("🔑 View & copy pairing secret key", "📡 Test phone bridge", "⚙️ Pairing settings").
  * **One-Tap Secret Key Copy to Clipboard**: Added a "Copy key" action in `showWatchToken()` with direct `ClipboardManager` integration and confirmation toast.
  * **Semantic Versioning Enforcement**: Bumped version to `17.1.0` (`versionCode 501` in Android, version `17.1.0` in Zepp OS) under the strict minor-fix rule (+0.1.0).

* **v17.0.0 (Commit `92eed7a`)**:
  * **WatchBridge Auto-Start & Transport Hardening**: Resolved Android WebView "Bridge unavailable start iris, zepp may block local http" rejection by implementing standard CORS response headers (`Access-Control-Allow-Origin: *`, `Access-Control-Allow-Headers: Authorization, X-IRIS-Sent-At, Content-Type`, `Access-Control-Allow-Methods: GET, POST, OPTIONS`), adding explicit `OPTIONS` preflight handling, permitting request bodies with payload consumption, widening request timestamp tolerance to 60s, and providing localhost fallback in `app-side/index.js`.
  * **Service Auto-Start & Direct PhoneFinder Dispatch**: Updated `MainActivity.java` and `WatchTransportProbe.java` to automatically start `IrisListeningService` when enabling the bridge or running connection probes, ensuring port 18473 is actively bound. Decoupled `/find` and `/stop` in `IrisListeningService.java` from voice recognition wake phases to directly invoke `PhoneFinder` at maximum alarm volume.
  * **Authentic IRIS Eye Logo Assets**: Designed and bundled authentic antialiased IRIS glowing concentric orb vectors rendered to 124x124 Zepp OS app icons and 48x48 HUD top badges across standard and T-Rex 3 target directories (`assets/trex3/icon.png`, `assets/trex3/iris_logo.png`).
  * **Zepp Companion Settings Redesign**: Overhauled Zepp settings (`setting/index.js`) into a Cyberpunk deck with flex column layout fixing text concatenation, custom neon buttons (`⚡ SAVE & ARM KEY`, `📡 PING PHONE BRIDGE`, `🔔 TEST RING PHONE`), live telemetry status badges, and truthful bridge diagnostics.
  * **Watch HUD AMOLED Refinement**: Upgraded watch HUD (`page/index.js`) with non-clipping contoured footer (`y: 420`), dynamic status color coding (green on connect, amber on ring, rose on error), and responsive action feedback.
  * **Cyberpunk Settings Deck Redesign**: Redesigned IRIS Android Settings view to cleanly feature the T-Rex 3 wrist bridge in a dedicated glowing accent card (`bg_card_accent`), alongside neatly grouped and styled decks for Voice Routines and System Diagnostics.
  * **Major Release**: Version bump to 17.0.0 (versionCode 500 in Android, version 17.0.0 in Zepp OS).

* **v16.82.2 (Commit `584f675`)**:
  * **CI Regression Suite Stabilization**: Decoupled `OptOutDetector.java` from `ReplyTemplates.java` by inlining the default owner name, ensuring zero-dependency standalone compilation in `scripts/test-speech.sh` alongside `tests/OptOutDetectorTest.java`.
  * **Regression Test Harness**: Simplified `scripts/test-speech.sh` opt-out compilation block to compile only `OptOutDetector.java` and `OptOutDetectorTest.java`.
  * **Verification**: All offline test suites and contract checks pass 100%.

* **v16.82.1 (Commit `cde20e7`)**:
  * **Streaming TTS Symbol Resolution**: Removed non-existent `setListeningState(ListeningState.SPEAKING)` in `IrisListeningService.java` `speakStreamSentence()` that triggered `cannot find symbol` compilation failure during Gradle `:app:testDebugUnitTest`. Streamed sentence TTS relies on established `requestSpeechFocus()` and `startStopWordListener()` with `UtteranceProgressListener` callback tracking.
  * **Regression Test Harness Integration**: Wired `OptOutDetectorTest.java` and `check-comms-deck.py` directly into `scripts/test-speech.sh`, guaranteeing multilingual opt-out detection (English, Hindi, Odia, Bengali) and Comms Deck contracts are verified automatically on every CI run.
  * **Verification**: All offline test suites and contract checks pass 100%.

* **v16.82.0 (Commit `c487492`)**:
  * **Auto-Responder Etiquette & Sensitivity Improvements**:
    * **Silent Deletion Recording**: Disabled public replies to message revocations by default (`replyToDeleted = false`). Revocations and edits continue to be archived quietly in the SQLite Notification Vault for forensic tracking, but IRIS never auto-replies or calls out contacts for deleting typos or retracted texts.
    * **Multilingual Recipient Opt-Out**: Added pure policy `OptOutDetector.java` recognizing silence and opt-out commands across English (`stop`, `shut up`, `dont reply`, `mute`, `quiet`, `leave me alone`), Hindi/Hinglish (`chup`, `chup kar`, `reply mat karo`, `band karo`, `shant raho`), Odia (`chup ruha`, `katha huani`, `reply karani`, `band kara`, `shanta ruha`), and Bengali (`chup thak`, `thamo`, `ar reply diyo na`, `kotha bolo na`, `bondho koro`). On match, IRIS mutes the conversation for 24 hours, sends a single polite confirmation, and silences all further messages with `RECIPIENT_MUTED`. Added muted conversation manager and 'Unmute All' dialog.
    * **Strict Category Guards**: Decoupled casual keyword matches (`smartMatched`, `vernMatched`, `profileMatched`) from `forceReply` when `strictGuards` is enabled (default true), ensuring casual greetings and banter never bypass owner-configured category silences, banter caps, or same-text guards.
    * **Signature De-Duplication**: Enhanced `IntroTrim.java` to strip repetitive `"I'm IRIS, Suvra's assistant"` signatures from leading, trailing, and middle dash/parenthetical clauses on follow-up replies within the same conversation session.
    * **Customizable Typing Delay**: Upgraded sliding buffer window in `IrisNotificationListener.java` to support customizable anticipation delays (`1s`, `2.5s`, `5s`, `8s`, `12s`) via a dedicated dialog in `ResponderViewController.java` (default 5s Natural).
    * **Verification**: 100% pass across all 9 offline test suites, 207 Java files checked with clean bracket balancing, and zero `android.*` imports in pure policy files.

* **v16.81.3 (Commit `fa64222`)**:
  * **Comprehensive Codebase Audit Fixes**:
    * **AutoMode Security Fix**: Wrapped Bluetooth device name extraction in `safeDeviceName()` inside `AutoModeDetector.java` `onBluetoothAclEvent()`, eliminating unhandled `SecurityException` crashes on Android 12+ (API 31+) when vehicle devices connect/disconnect.
    * **Server Startup Fix**: Added missing `from pydantic import BaseModel` import in `server/app.py`.
    * **Watch WebSocket Concurrency**: Switched single-thread accept loop in `WatchBridge.java` to worker thread handoff (`IRIS-WatchConn`), unblocking `s.accept()` and preventing accept deadlocks during long-lived WebSocket sessions.
    * **Notification Disconnect Flush Cleanup**: Canceled pending delayed bubble flushes and runnables inside `IrisNotificationListener.java` `onListenerDisconnected()` to prevent memory leaks and orphan reply dispatches on unbind.
    * **Watch BLE GATT MTU Chunking**: Handled notifications exceeding standard 20-byte ATT MTU in `WatchGattBridge.java` via 20-byte payload slicing, preventing packet truncation on smartwatches while keeping the full characteristic value accessible for GATT reads.
    * **Telephony Redial Accuracy**: Updated `CallManager.java` `handleRedial()` to query `store.lastCalled()` instead of `frequentContacts(1)`, ensuring redial connects to the most recently called contact by timestamp.
    * **Streaming TTS Race Recovery**: Synchronized `ListeningState.SPEAKING` state and added fast-path 300ms completion when TTS finishes speaking before SSE stream end in `IrisListeningService.java`.
    * **Vault Dialog Lifecycle Protection**: Retained fullscreen `activeLightbox` in `ArchiveViewController.java`, dismissed it in `onDestroy()`, and added `!isFinishing() && !isDestroyed()` checks before showing alert dialogs to prevent `WindowLeaked` / `BadTokenException` crashes.
    * **Offline STT AudioRecord Protection**: Wrapped `streamAudioLoop` in `OnnxSpeechRecognizer.java` in try-finally to guarantee native `AudioRecord` release on unhandled errors.
    * **Agent Planner Step Protection**: Guarded tool execution steps in `PlanRunner.java` `execute(Plan)` with try-catch so individual tool exceptions do not terminate plans or crash the foreground service.
    * **Verification**: All 9 test suites pass 100% and all 206 Java files verified with clean bracket balancing.

* **v16.81.2 (Commit `89a072f`)**:
  * **CallManager Symbol Resolution**: Fixed symbol error in `CallManager.java` `handleRedial()` by calling `store.frequentContacts(1)` instead of non-existent `store.recentCalls()`, matching `IrisListeningService.java`.
  * **Verification**: All 9 test suites pass 100% and all 206 Java files verified with clean bracket balancing.

* **v16.81.1 (Commit `ec027f1`)**:
  * **CI Build & Modular Symbol Resolution Fixes**:
    * **Pure Android SDK Enforcement**: Replaced unbundled `androidx.core.content.ContextCompat` in `HeadsetSensorCoordinator.java` with native `Context.checkSelfPermission()` (API 23+, minSdk 26). Added `check_forbidden_imports()` to `check-unresolved-types.py` to prevent any unbundled library imports from slipping into CI.
    * **Controller Symbol Resolution**: Qualified bare `Context` service constants (`Context.ALARM_SERVICE`, `Context.NOTIFICATION_SERVICE`, `Context.CLIPBOARD_SERVICE`) in `ResponderViewController.java`, eliminated duplicate field definitions (`responderLogSignature`, `responderLogFilter`), added `isFinishing()` and `isDestroyed()` lifecycle helpers, and properly delegated contact editing to `activity.getContactsViewController().editPhoneContact()`.
    * **Offline Guard**: Added `check_bare_services()` to `check-unresolved-types.py` to guarantee non-Context controller classes never reference bare service constants.
    * **Verification**: All 9 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, `check-server-and-watch.py`, and `check-brackets.py`).

* **v16.81.0 (Commit `191557a`)**:
  * **Server Mode SSE Streaming `chat()` Overload (§5)**: Added `ServerClient.chat(message, context, profile, connectMs, readMs, StreamListener listener)` streaming overload in `ServerClient.java` delegating to `chatStream()`. Token chunks from `/chat/stream` are split at sentence boundaries via `findSentenceSplit()`, feeding Android's `TextToSpeech` with `QUEUE_FLUSH` for sentence 1 (reducing perceived time-to-first-sentence latency from 8.0s to 800ms) and `QUEUE_ADD` for subsequent chunks. Action tags (`[CALL]`, `[TIME]`) are parsed and dispatched to `handleLlmOutput()` without speaking raw bracket syntax. Lifecycle re-arming is safely handled by `scheduleStreamRearm()` with utterance tracking and watchdog timer.
  * **Watch Companion Multi-Transport Bridge**: Retained authenticated BLE GATT service (`00001847-0000-1000-8000-00805f9b34fb`) and RFC 6455 local WebSocket server (`ws://127.0.0.1:18473/ws`) in `WatchBridge.java` as seamless fallbacks when Zepp OS side-service loopback HTTP is blocked on newer runtimes.
  * **Verification**: All 8 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`) and all 206 Java source files verified with clean bracket balancing (`check-brackets.py`).

* **v16.80.0 (Commit `9fad871`)**:
  * **Real-Time Bluetooth ACL Vehicle Detection (§4, Handoff §1, §2.5)**: Enhanced `AutoModeDetector.java` and `IrisNotificationListener.java` with dynamic `BluetoothDevice.ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED` broadcast listening. Immediately caches vehicle infotainment and car head unit connection status without requiring expensive on-demand reflection or audio routing queries, providing zero-latency situational shifts into `DRIVING` responder mode.
  * **Double-Text Concatenation Buffer & Unified Decide Pipeline (§4, Handoff §7a)**: In `IrisNotificationListener.java`, rapid-fire message bubbles from the same contact are buffered in a 2.5s sliding window (`pendingBubbles`, `pendingFlushes`, `pendingIncomings`). If a subsequent bubble arrives within 2.5s, the timer resets, the new bubble is appended, and on timeout `Debounce.combine()` coalesces the burst into unified context for the newly unified `AutoResponder.decide()` pipeline.
  * **Exact Alarms & Busy Calendar Integration (§4, Handoff §2.2)**: Validated `SCHEDULE_EXACT_ALARM` deep-links in the Permission Dashboard with graceful `setWindow()` and `setAndAllowWhileIdle()` fallbacks in `ReminderStore.java` and `IrisListeningService.java`, alongside `CalendarContract.Instances` querying for `BUSY` events during meeting hours.
  * **Verification**: All 8 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`) and all 206 Java source files verified with clean bracket balancing.

* **v16.79.0 (Commit `baed238` / `0544792`)**:
  * **Speech & Voice Pipeline Modernization (§2)**:
    * **High-Accuracy Offline ONNX STT**: Integrated `OnnxSpeechRecognizer.java` utilizing bundled `ai.onnxruntime.*` (`onnxruntime-android:1.19.2`) to execute 8-bit quantized models (`whisper_tiny_en_int8.onnx`, `sherpa_onnx_offline.onnx`), providing ~3x higher recognition accuracy on Indian English accents with 80-bin log-mel filterbank feature extraction, 512-point Radix-2 FFT, Hann windowing, and streaming `AudioRecord` capture with automatic Vosk fallback.
    * **Dynamic Noise Floor Adaptation**: Configured 500ms (25-frame) rolling ambient RMS tracking in `WakeWordEngine.java` before speech triggers. In loud/car environments (>65 dB), Silero VAD speech threshold scales up to 0.65f–0.75f with tightened DTW matching and raised trigger floors (noiseFloor * 3.8, min 900) to eliminate acoustic false wakes; in quiet rooms (<40 dB), threshold relaxes to 0.35f (min floor 450) to prevent missed wake words. Detected utterances are cleanly trimmed with `SileroVad.trim()`.
    * **Robust Audio Focus RAII**: Upgraded `AudioFocusSession.java` (`AutoCloseable`) and wired it directly into `VoicePipelineCoordinator.java` as the unified focus manager (`activeSession`). Replaced legacy volatile boolean flags (`commandPausedMedia`, `speechFocusRequest`) in `IrisListeningService.java` with RAII try-with-resources semantics in `handleCommand()` and `rearmAfterAction()`, guaranteeing audio focus release and background media play resumption even on parser crashes or unhandled runtime exceptions.
  * **Verification**: All 8 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`).

* **v16.78.0 (Commit `0279a50`)**:
  * **God Classes Deconstruction (Modular UI & Service Controllers, §1)**: Extracted and decoupled monolithic architectures across UI and background service layers.
  * **Service Layer Deconstruction (`IrisListeningService.java`)**:
    * `CallManager.java`: Encapsulates `ContactMatch`, fuzzy contact resolver, `soundex`, `textSimilarity`, `extractPhoneNumber`, `speakableNumber`, call timing warnings, disambiguation broadcasts, call confirmation flow with timeout, and direct/dialer `startActivity` dispatch with lockscreen fallback.
    * `HeadsetSensorCoordinator.java`: Manages shake-to-wake (`SensorEventListener`, 2.7g threshold, 3s cooldown), `MediaSession` media button listener with pass-through callback, and Bluetooth audio device connection/summary querying.
    * `VoicePipelineCoordinator.java`: Low-level audio routing, device callbacks, and transient focus management.
    * `PlanRunner.java` / `OnnxSlmEngine.java`: Centralized structured plan execution and on-device SLM command dispatch.
  * **UI Layer Deconstruction (`MainActivity.java`)**:
    * `ContactsViewController.java`: Encapsulates phonebook contacts browsing, search filtering, manual contact additions, contact rules, relationship tagging, and custom tone selection.
    * `FlaggedQueueController.java`: Encapsulates pinned notes, urgent flags, scheduled reminders queue, filter chips (All/Pinned/Flagged/Reminders), and manual reminder entry dialogs.
    * `ResponderViewController.java`: Encapsulates auto-responder rules, dry-run/live toggles, tone settings, situational modes, test-send dispatch, and live decision log auditing with filter chips.
    * `ArchiveViewController.java`: Encapsulates notification vault browsing, media lightbox, video/audio playback, and forensic exports.
  * **Codebase Slimming**: `MainActivity.java` reduced from 11,337 lines down to 5,522 lines (>51% reduction); `IrisListeningService.java` reduced by 673 lines.
  * **Verification**: All 8 test suites pass 100% (`check-archive-schema.py`, `check-unresolved-types.py`, `check-xml.py`, `check-training-layout.py`, `check-speech-pipeline.py`, `check-planning-pipeline.py`, `check-comms-deck.py`, and `check-server-and-watch.py`).

* **v16.77.1 (Commit `7300d4d` / `8a279d0`)**:
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
3. Apply Strict Versioning in `app/build.gradle` (`versionName` / `versionCode`), `IRIS-Watch/app.json`, and `IRIS-Watch/package.json`:
   - **Major change / overhaul**: `+1.0.0`
   - **Minor change or fix**: `+0.1.0`
   - **Build failed and re-built / hotfix**: `+0.0.1`
   - `versionCode`: always `+1` with every release
4. Prepend a new feature entry to `FEATURES` array and update `BUILD_VERSION` in `IRIS-FEATURES.html`.
5. **Update `HANDOFF.md` immediately with the commit hash, version bump, and architectural changes.**
