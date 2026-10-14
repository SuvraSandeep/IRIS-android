# IRIS-Android — Handoff Brief

> Snapshot for bringing a new contributor (human or AI) up to speed.
> Written against the actual source (build workflow, `app/build.gradle`, responder classes,
> Server/Watch packages). **Current version: `16.27.0` / `versionCode 406`.**

## 0. Orientation
- **App:** offline-first, single-user voice assistant + WhatsApp/SMS auto-responder. Package `com.iris.assistant`. Owner = **Suvra**.
- **Repo root:** `IRIS-android/`; Android project under `IRIS-Android/` (that's where `settings.gradle`, `app/`, `scripts/`, `tests/` live). Watch companion under `IRIS-Watch/`. Personal server under `IRIS-Android/server/`.
- **Design spine:** all decision logic lives in **pure policy classes with no Android imports**
  (`MessageCategory`, `ReplyTone`, `SmartReplies`, `ResponderGuards`, `Shorthand`, `GroupMention`,
  `Debounce`, `IntroTrim`, `OwnerAlias`, `TimeGreeting`, `AutoModePolicy`, `ReminderParser`,
  `Emoji`, `Vernacular`, `ProfileShare`, etc.). The Android layer is deliberately thin. This is why
  there's a large offline test suite but a thin, device-only glue layer.

## ⚠️ The single most important fact
Releases **16.6.0 → 16.27.0 (~21 releases) are all offline-suite-green but have NOT been compiled
against the Android SDK.** The offline harness catches Java syntax + unresolved type names only —
**not** Android method-signature/import/`R` errors (16.0.0 historically failed CI exactly this way).
**The immediate next task is a successful CI/APK build of the current source and installing it on
the device.** Treat all "Working" statuses below as "working in offline logic; device-unverified
for the latest ~21 releases" unless noted.

---

## 1. Feature-by-feature status

### Voice / wake / identity (mature, older, has run on device historically)
| Feature | Status | Notes |
|---|---|---|
| Wake word (Vosk grammar) | Working* | *older, device-proven in earlier builds |
| Speech-to-text (Vosk full) | Working* | en-IN + en-US small models |
| Silero VAD (voice activity) | Working* | ONNX Runtime, on-device; model self-downloads at runtime |
| Speaker verification (ECAPA-TDNN ONNX + Vosk spk) | Working* | only-owner wake; see `SPEAKER-VERIFICATION.md`, `WAKE-TRAINING-REDESIGN.md` |
| Owner enrollment / training | Working* | `OwnerEnrollmentController`, training-plan classes + Robolectric unit tests |
| TTS (Android TextToSpeech) | Working* | |
| Command routing (call/time/torch/etc.) | Working* | **rule-based** `LocalPlanner`/`IntentParser`/`Plan` — not an LLM |

### Comms Deck / Auto-responder (active work area — heavily built, offline-tested, device-unverified for recent releases)
| Feature | Status |
|---|---|
| Category classification (`MessageCategory`) | Working (offline) |
| Tone resolution + per-app tone + sentiment softening | Working (offline) |
| Reply templates / variants (`ReplyTemplates`) | Working (offline) |
| Guards: scope, cooldown, caps, loop, SMS-OTP safety, burst, **debounce** | Working (offline) |
| SmartReplies keyword brain (~90+ scenario rules) | Working (offline) |
| Robust/loose matching (`PhraseMatcher`), SMS shorthand (`Shorthand`) | Working (offline) |
| Group mention by @handle/number (`GroupMention`) | Working (offline) |
| Money-amount detection, weather, availability, location, "detective-trap" | Working (offline) |
| Hinglish/Odia (`Vernacular`), Emoji understanding (`Emoji`) | Working (offline) |
| Reminders (`ReminderParser` + follow-up edit/cancel) | **Partial** — parsing works offline; on-device scheduling needs exact-alarm permission |
| Learned replies (teach-a-reply) | Working (offline) |
| Profile sharing (`ProfileShare`/`ShareProfile`, PII-safe) | Working (offline) |
| Notification triage ("what did I miss") | Working (offline) |
| Multi-intent stitching (`matchMulti`) | Working (offline) |
| Intro-strip after msg #1 (`IntroTrim`) | **Partial** — only strips lead/trailing intros, not mid-sentence |
| Double-text debounce (`Debounce`) | **Partial** — suppresses the rapid 2nd reply; does not literally concatenate the two messages' content |
| Self-activating modes (`AutoModePolicy`) | **Partial** — decision core + auto-sleep done; driving/meeting signal collectors NOT wired (permission-gated) |

### Other subsystems
| Feature | Status |
|---|---|
| **Server Mode** (opt-in cloud LLM/Whisper) | **Partial** — Android client (`ServerClient`) + runnable server package exist; opt-in; not device-verified end-to-end |
| **Watch Bridge** (`IRIS-Watch`, Amazfit T-Rex 3) | **Partial / experimental** — Zepp OS mini-app source; never tested on real hardware; loopback-HTTP transport unproven; packaging blocked |
| **On-device generative LLM** (Gemma/MediaPipe) | **Planned / not in build** (see §7) |
| Memory store / personal facts (`iris-me.json`) | Working* |

---

## 2. Known bugs & issues (as of 16.27.0)
1. **Not APK-compiled** since ~16.6.0 — highest risk; may contain Android-only compile errors invisible to the offline suite. Workaround: keep the Android layer thin; run CI before trusting a release.
2. **Reminders fail silently** on Android 12+ without the "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`) permission. Parsing is correct; scheduling is the gap.
3. **IntroTrim partial** — intros embedded mid-sentence aren't removed (by design, to avoid mangling).
4. **Debounce partial** — the 2nd rapid message's content isn't merged, only one reply is sent.
5. **Driving/meeting auto-modes** — not active (signals unwired, permission-gated).
6. **Instagram deep-link reply fallback (plan 9.1/9.6)** — not implemented; replies only work when the notification exposes a RemoteInput action.
7. **Watch Bridge** — unverified on hardware; Zepp cleartext/loopback may be blocked; Zeus packaging was blocked by an upload-approval review.
8. **Server Mode** — not device-verified end-to-end; cold-start/latency behavior untested on real free-tier hosts.
9. **On-device small LLM historically crashed** (CPU/RAM) → removed/disabled; see §7.
10. **`now.json` (407 MB)** was purged from git history (git-ignored). Recoverable from backup branch `backup/pre-nowjson-fix`.
11. **Two build workflows exist** — the active one is `.github/workflows/build-apk.yml` at the repo root; a nested `IRIS-Android/.github/workflows/build-iris-apk.yml` also exists and differs (e.g. bundles Silero VAD). Nested workflows under a subdir do NOT run on GitHub — don't be misled by the nested copy.

**Standing safety workarounds in place:** offline-first auto-fallback everywhere; models self-download at runtime if CI bundling fails; keep-unread default ON; SMS one-time-codes/shortcodes never auto-replied; owner wake/identity checks are a hard contract (`AGENTS.md` / `PROJECT-RULES.md`) and must not be weakened.

---

## 3. Build & run from source

### Canonical path — GitHub Actions (what's actually used)
Workflow: `.github/workflows/build-apk.yml` (Ubuntu, **JDK 17 Temurin**, Gradle via wrapper,
`workflow_dispatch` with debug/release; also runs on push/PR).
Pipeline: check watch JS (`npm --prefix IRIS-Watch run check && test`) → locate project
(`IRIS-Android`) → read version → bundle models (see §5) → `bash scripts/test-speech.sh` →
`./gradlew :app:testDebugUnitTest` (Robolectric) → `./gradlew :app:assembleDebug|assembleRelease`
→ lint → upload APK artifact `IRIS-v<version>-<type>-apk`.
To build: trigger the workflow (push to main, or **Run workflow** → debug/release) and download the artifact.

### Local path (needs Android SDK)
```
# from IRIS-Android/
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # Robolectric unit tests
bash scripts/test-speech.sh           # pure offline regression suites
```
Requires JDK 17, Android SDK (compile/target per `app/build.gradle`), and model assets in
`IRIS-Android/app/src/main/assets/` (see §5).

### Offline logic checks (no SDK needed)
```
# from IRIS-Android/
python tests/run-offline-suites.py                                     # 114 suite commands
java -Dfile.encoding=UTF-8 tests/ParseSources.java app/src/main/java    # parses 181 files
python tests/check-unresolved-types.py                                 # unresolved types + layout ids
```

---

## 4. What's next / half-finished
- **Last work:** completed `RESPONDER-INTELLIGENCE-PLAN.md` and a forensic-audit spec (shorthand, scenario rules, multi-intent stitching, intro-strip, debounce) through 16.27.0.
- **Immediate next task:** green CI/APK build of current source + install on device; fix any Android-only compile errors it surfaces. Nothing else should be trusted until this passes.
- **Then, roughly:** (1) exact-alarm permission so reminders fire; (2) wire driving/meeting auto-mode signals (permissions + device test); (3) Instagram deep-link reply fallback; (4) device-verify Server Mode and the Watch Bridge; (5) improve IntroTrim (mid-sentence) and true debounce concatenation.
- **No half-written code left mid-edit** — every release is a complete, suite-green unit.

---

## 5. Model files & where they go (`IRIS-Android/app/src/main/assets/`)
| Asset | Source | In CI | Runtime fallback |
|---|---|---|---|
| `model-en-in.zip` (Vosk small en-IN) | alphacephei | best-effort download | self-downloads if absent |
| `model-en-us.zip` (Vosk small en-US) | alphacephei | **required** (`unzip -t`) | — |
| `spk-model.zip` (Vosk speaker) | alphacephei | best-effort | self-downloads |
| `ecapa_tdnn_voxceleb.onnx` (+ `.json`) | `tools/export_ecapa_to_onnx.py` (torch+speechbrain, cached) | yes | — |
| `silero_vad.onnx` | snakers4/silero-vad raw GitHub | not bundled by the active root workflow | `SileroVad.java` self-downloads on first use (bundled-first, else download) |
| `iris-me.json` | repo root (full owner profile) | copied if present | app works without it |
| `iris-share.json` | repo root (shareable facts only) | copied if present | nothing shared if absent |

Zips are bundled whole (not unpacked) and streamed via `AssetManager.open()` + `ZipInputStream`
on-device — unpacking loose files broke `AssetManager.list()`. `aaptOptions { noCompress 'onnx','tflite' }`.

---

## 6. Dependencies & gotchas
- **Gradle deps** (`app/build.gradle`): `com.alphacephei:vosk-android:0.3.75`,
  `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`; test: `junit:4.13.2`,
  `robolectric:4.14.1`, `org.json`. **No MediaPipe/Gemma dependency.**
- **No API keys** needed for the app. Server Mode uses a self-generated bearer token + your own
  Ollama/Whisper server (no third-party cloud API key).
- **Dev-environment:** owner develops on **Windows + PowerShell**; the primary agent has **no
  Android SDK** (offline Java checks only). PowerShell shows a harmless "running scripts is
  disabled" profile error on every command — ignore it.
- **Release rules (enforced):** every change adds a new entry at the **top** of the `FEATURES`
  array in `IRIS-FEATURES.html` and bumps `BUILD_VERSION`; `app/build.gradle` `versionName`
  +0.1.0 (feature) / +0.0.1 (fix) and `versionCode` +1. `IRIS-FEATURES.html` is **JS, not JSON** —
  validate with `new Function("return [" + body + "]")()`, never `JSON.parse`.
- **Invariants:** pure policy classes have no Android imports; `MessageCategory` must not depend on
  any other policy class (compiled alone in some suites); every shipped responder reply variant
  should name both **IRIS** and **Suvra** (all-variants sweep in `ReplyTemplatesTest`); the
  real-Odia "must-not-match" corpus must stay silent.
- **Suite registration:** each new pure class gets its own `javac`+`java` block in
  `scripts/test-speech.sh`; watch transitive deps (`ReplyTone`->`MessageCategory`;
  triage/sentiment->`Emoji`+`ReplyTone`; `ResponderGuards`->`Debounce`).
- **Encoding:** `javac` needs `-encoding UTF-8` and rejects `-Dfile.encoding`; `java` needs quoted
  `'-Dfile.encoding=UTF-8'`.
- **Owner contract:** `AGENTS.md` + `PROJECT-RULES.md` forbid weakening owner wake-identity, model
  selection, media rejection, or authenticated-write controls without explicit owner request.

---

## 7. On-device LLM — the honest answer
**Not running on-device in the current build.** There is **no MediaPipe/Gemma/Qwen dependency** in
`build.gradle` and **no Gemma/LLM source class** in the app. `CONVERSATIONAL-AGENT.md` (a v6.0.0
design doc) describes a "Gemma-3 / FunctionGemma via MediaPipe" agent, and `SERVER-MODE.md` records
that the on-device small LLM was **"unstable / crashes"** on phone CPU/RAM.

So today:
- **On-device "intelligence" is rule-based**, not generative: `LocalPlanner`/`IntentParser`/`Plan`
  for commands, and the `SmartReplies`/`MessageCategory` stack for the responder.
- **Generative LLM is server-only and opt-in** (Server Mode): `server/app.py` (FastAPI) proxies
  **Ollama** — default `qwen2.5:3b-instruct` (alternatives `llama3.2:3b`, `qwen2.5:7b-instruct`) —
  plus **faster-whisper** for STT. The phone (`ServerClient.java`) calls `/health`, `/chat`,
  `/transcribe` and **falls back to on-device on any error/timeout**.
- Net: **rule-based on-device + optional server LLM**. On-device Gemma/MediaPipe is
  **planned/documented but uncoded (previously attempted, removed as unstable)**.

**Server Mode** = implemented client + runnable server package (`server/`: `app.py`, `install.sh`,
`Caddyfile`, `colab/`), opt-in via Settings, **not device-verified end-to-end**.
**Watch Bridge** (`IRIS-Watch`) = experimental Zepp OS mini-app for the Amazfit T-Rex 3 (phone
status, ring/stop finder, request-talk) over BLE -> Zepp Side Service -> authenticated
`127.0.0.1:18473`; **never tested on real hardware**; transport may be blocked by Zepp's build.
**Comms Deck** = extensively implemented and offline-tested, **device-unverified for the latest
~21 releases**.

---

## 8. Key reference docs in-repo
`RESPONDER-INTELLIGENCE-PLAN.md` (responder roadmap + completion status) · `SERVER-MODE.md` /
`SERVER-MODE-IMPLEMENTATION.md` · `CONVERSATIONAL-AGENT.md` / `AGENT-ARCHITECTURE.md` ·
`SPEAKER-VERIFICATION.md` / `WAKE-TRAINING-REDESIGN.md` · `MEMORY-AND-SECURITY.md` ·
`INTELLIGENCE-ROADMAP.md` · `IRIS-Watch/README.md` · `server/README.md` · `AGENTS.md` /
`PROJECT-RULES.md` (owner security contract).
