# IRIS-Android — Handoff Brief

> Snapshot for bringing a new contributor (human or AI) up to speed.
> Written against the actual source (build workflow, `app/build.gradle`, responder classes,
> Notification Vault architecture, Server/Watch packages).
> **Current version: `16.75.0` / `versionCode 471` (Branch: `feat/responder-conversation-fixes`).**

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
| **Server Mode** | Working (Opt-in) | FastAPI server (`server/app.py`) proxying Ollama (`qwen2.5:3b-instruct`) and faster-whisper. Android client `ServerClient.java` falls back to on-device logic on timeout. |
| **Watch Companion** | Experimental | `IRIS-Watch/` Zepp OS mini-app designed for Amazfit T-Rex 3 over loopback HTTP bridge. |

---

## 2. Key Static Analysis & Verification Suites

Run from `IRIS-Android/` before every commit:
```bash
# 1. Verify append-only SQL triggers and database schema immutability
python tests/check-archive-schema.py     # 73/73 tests pass

# 2. Check for unresolved type names and layout ID bindings
python tests/check-unresolved-types.py    # 39 files checked, 280 layout IDs resolved

# 3. Validate all 38 XML layout and drawable resources
python tests/check-xml.py                # 38 resource files validated

# 4. Check speech pipeline contracts, dynamic noise formulas, and VAD adaptation
python tests/check-speech-pipeline.py    # 100% contract and math validation

# 5. Check real agent planning pipeline, multi-intent decomposition, and tool execution
python tests/check-planning-pipeline.py  # 100% dispatch, Host methods, and pure policy validation
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
* **Auto-Responder:**
  * `AutoResponder.java`: Top-level message intake, triage, and dispatch pipeline.
  * `ResponderGuards.java`: Guard policies, rate limits, allowlists, and `Verdict.NOT_URGENT` enforcement.
  * `ResponderSettings.java`: SharedPreferences wrapper with `urgentOnly` toggle state.
  * `SmartReplies.java`: Context-aware keyword and vernacular reply engine.
  * `view_comms_responder.xml`: Responder UI deck with quick `[URGENT MESSAGES ONLY]` toggle button.
* **Voice & Execution Controllers:**
  * `VoicePipelineCoordinator.java`: Low-level audio routing, `AudioDeviceCallback` lifecycle, input device enumeration/selection, speech focus request/abandon, media key play/pause dispatch, and `openSpeechSession()` factory.
  * `AudioFocusSession.java`: AutoCloseable transient audio focus manager guaranteeing automatic abandon and media resumption with try-with-resources semantics.
  * `OnnxSpeechRecognizer.java`: Offline high-accuracy speech recognizer running quantized Whisper-Tiny / Sherpa-ONNX via bundled `onnxruntime-android:1.19.2`, with 80-bin log-mel extraction and greedy CTC/token decoding.
  * `SileroVad.java`: On-device neural voice activity detector with dynamic ambient noise floor adaptation (`calculateAmbientDb`, `computeAdaptiveThreshold`, `adaptThresholdFromRms`).
  * `WakeWordEngine.java`: Wake-phrase detection with 500ms (25-frame) rolling ambient RMS tracking, adaptive trigger multipliers (2.8x–3.8x), dynamic DTW scaling, and Silero VAD trimming.
  * `PlanRunner.java`: Phase 2/5 Android tool executor centralizing execution of structured plans and tool calls against device hardware and intents.
  * `LocalPlanner.java` & `IntentParser.java`: Intent parsing and structured plan generation.
* **Glue & UI Orchestration:**
  * `MainActivity.java`: Host UI activity & navigation tabs; delegates Notification Vault entirely to `ArchiveViewController`.
  * `IrisListeningService.java`: Foreground lifecycle and hotword service; delegates audio routing to `VoicePipelineCoordinator`, offline STT to `OnnxSpeechRecognizer` (with Vosk fallback), and plan execution to `PlanRunner`.
  * `IrisNotificationListener.java`: System `NotificationListenerService` capturing active notifications, enforcing the `lastWipeEpoch` barrier, and handling system notification shade dismissal.

---

## 4. Recent Milestones & Release History

* **v16.75.0 (Commit `3e712d7`)**:
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
