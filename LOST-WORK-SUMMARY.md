# Lost Work Reconstruction (v5.3.0 → v6.6.3)

The chat transcript in `now.json` only covers **Aug 27 → Aug 28 18:52** (up to **v5.2.0**),
extracted into `RECOVERED-CHAT.md`.

The sessions from **v5.3.0 → v6.6.3** (Aug 29–30) were NOT saved to any export, so their
chat text is gone. But the CODE they produced is intact. Here is what those lost sessions
built, reconstructed from the source files and version zips:

## New capabilities added in the lost sessions

### 1. Conversational AI (the "chatbot that can make calls")
- **LlmAgent.java** — On-device Gemma LLM via Google MediaPipe LLM Inference.
  Uses reflection so the app compiles/runs even without the model. Falls back to
  rule-based chat when the model isn't present.
  - Model expected as `gemma.task` / `gemma3-1b-it-int4.task` in the app model dir.
  - NOTE (from build.gradle): MediaPipe `tasks-genai` dependency was REMOVED because
    Google forbids bundling the Gemma model in an APK — it needs `adb push` and a
    flagship phone. LlmAgent stays reflection-based for a future runtime download.
- **ConversationManager.java** — Short-term memory: keeps the last 8 user/assistant
  exchanges so IRIS stays in context during a conversation.

### 2. Notification reading
- **IrisNotificationListener.java** — A NotificationListenerService that captures posted
  notifications (needs "Notification access" granted in system settings). Filters out
  ongoing/system noise.
- **NotificationStore.java** — Encrypted rolling store (max 100) of captured notifications.
  Enables voice queries like "read my latest notification", "how many WhatsApp messages",
  "any message from mom".

### 3. Weather
- **WeatherService.java** — Free, keyless weather + forecast via Open-Meteo
  (open-meteo.com). Background fetch, main-thread result. Powers "what's the weather".

## Version zips present (each is a buildable snapshot)
- v5.3.0, v5.4.0, v5.5.0
- v6.0.0, v6.0.1, v6.1.0, v6.1.1, v6.2.0, v6.3.0, v6.4.0, v6.5.0, v6.6.0, v6.6.1, v6.6.2, v6.6.3

Current source in `IRIS-Android/` is **v6.6.3 / versionCode 210**.

## How to inspect any lost version in detail
Each `IRIS-Android-vX.Y.Z-source.zip` is a full snapshot. To see exactly what changed
between two versions, extract both and diff the `app/src/main/java/com/iris/assistant/`
folders. The newest (v6.6.3) is already extracted in `IRIS-Android/`.

## Full class inventory (v6.6.3)
Core: IrisListeningService, MainActivity
Voice: VoskEngine, TimedRecorder, WakeWordEngine, SpeakerVerifier, ModelManager
Intelligence: LlmAgent, ConversationManager, MemoryStore, MemoryParser, BehaviorAnalyzer
Data: ProfileStore, SecureStore, LogStore, AppSettings
Features: NotificationStore, IrisNotificationListener, WeatherService
UI: IrisOrbView, IrisTileService

## Recovery artifacts in this folder
- `RECOVERED-CHAT.md` — full transcript of the Aug 27–28 session (from now.json)
- `LOST-WORK-SUMMARY.md` — this file (reconstruction of Aug 29–30 work)
- `now.json` — raw session export (kiro-session-export-v1)
