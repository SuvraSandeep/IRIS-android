# IRIS Bug Roadmap — 10 Functional Issues

Investigated against the actual source (`IRIS-Android/app/src/main/java/com/iris/assistant/`) on 2026-09-14. Every root cause below was traced to a specific line, not guessed. Ordered by severity/blast radius, not by the order reported.

---

## 1. AI brain crashes even with plenty of free RAM — DECISION: REMOVE

**Symptom:** 16GB device, 8GB free RAM — app still crashed with AI brain enabled.

**Root cause:** `LlmAgent.loadModel()` (`LlmAgent.java:44-48`) only guards against **not enough RAM** (`availMb < 1100`). With 8GB free, that guard passes and the model loads. The actual instability is in the **native inference call itself** — `mediapipe:tasks-genai:0.10.24` is accessed entirely through reflection (`Class.forName(...)`, no compile-time binding), which means:
- Any native crash inside MediaPipe's C++ layer takes the whole process down; Java `try/catch` cannot catch a native SIGSEGV.
- The existing crash-guard (`iris_llm_guard` SharedPreferences flag, `IrisListeningService.java` around line 474) only **auto-disables AI *after* a crash already happened once** — it does nothing to prevent the first crash, which is what the user hit.
- The library is explicitly experimental (`tasks-genai`); RAM headroom doesn't fix instability in early-access native inference code.

**Decision: remove the AI brain feature entirely.** It is opt-in, off by default, and this is the second RAM/crash issue traced to it (the first, RAM footprint of the high-accuracy Vosk model, was already flagged in a prior session). No sandboxing/fix-in-place option is being pursued — removal is the confirmed direction.

**Removal checklist:**
1. Delete `LlmAgent.java` entirely.
2. In `LocalPlanner.java`: remove the LLM-backed `Engine` implementation wiring, but **keep the `Engine` interface itself** — it's Android-free and already designed for a future safer/local engine to drop in without redesigning the planner.
3. In `IrisListeningService.java`: remove `llmAgent` field, `llmReady` flag, the `iris_llm_guard` crash-guard block in `onCreate()`, the `settings.aiEnabled()` load-on-create thread, and the `tryLocalPlanner`/`offlineChat` LLM branches (fall through directly to `ruleBasedChat`).
4. In `AppSettings.java`: remove `aiEnabled()`/`setAiEnabled()`.
5. In `MainActivity.java`: remove the "AI BRAIN" Settings section (switch + any related UI), `ModelManager.autoDownloadGemmaIfNeeded` call in `onCreate`.
6. Delete or gut `ModelManager.java` if it has no other purpose beyond Gemma model management; check for other callers first.
7. Remove the `mediapipe:tasks-genai:0.10.24` dependency from `app/build.gradle`.
8. Update `IRIS-FEATURES.html` (`FEATURES` array + `BUILD_VERSION`) to drop the AI-brain feature entry per the standing project rule.
9. Run the full test suite after removal — `LocalPlannerTest.java` and `PlanTest.java` likely reference LLM-adjacent behavior and may need updates to reflect the engine-less planner.

---


## 2. App doesn't stay running — gets killed

**Symptom:** IRIS stops running in the background after a while.

**Root cause:** `AndroidManifest.xml` declares no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission at all. `IrisListeningService` is a foreground service with `foregroundServiceType="microphone"` (correct), but without battery-optimization exemption:
- Samsung (One UI), Xiaomi (MIUI), OnePlus (OxygenOS), and most other OEM skins apply **additional** background-kill logic on top of stock Android Doze/App Standby, and a foreground service alone does not exempt an app from these OEM-specific killers.
- The user has no way to grant this exemption because IRIS never asks for it.

**Fix:**
1. Add `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />` to the manifest.
2. On first run (or from Settings), check `PowerManager.isIgnoringBatteryOptimizations(packageName)`; if false, fire `Intent.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to prompt the user directly (one dialog, no Settings navigation needed for this specific permission — it's a direct-grant intent, unlike Battery Saver).
3. Add a Settings row + explanation: "Keep IRIS running in the background" with a "Fix now" button that re-fires the intent if it was denied.
4. Document OEM-specific caveats (some manufacturers additionally require a manual "auto-start"/"protected apps" toggle IRIS cannot request via any API) with a link/instructions per common OEM in the Settings help text.

This alone will not guarantee 100% uptime on every OEM (that's a genuine, documented Android fragmentation problem — see `dontkillmyapp.com` for OEM-specific behavior), but it removes the single largest controllable cause.

---

## 3. Wake phrase not heard while media is playing — SUPERSEDED, see #11

**Symptom:** Saying "Hello IRIS" does nothing if music/video is playing.

**Root cause:** `IrisListeningService.startWakeDetection()` deliberately refuses to even start wake listening while `audioManager.isMusicActive()` is true, to protect Bluetooth music quality (wake listening uses the phone mic, and forcing it active can still interact badly with some OEM audio stacks).

**Decision:** phone-mic wake during media playback is **not** the direction we're taking — the phone is frequently not within mic range while listening over Bluetooth earphones (see #11 for the full reasoning). This gate is being left in place. Voice wake during media remains off by default and unfixed; **physical trigger buttons (#11) are the actual fix for "IRIS doesn't respond while listening to music."**

---


## 4. "Take a selfie" uses the back camera

**Symptom:** Asking for a selfie takes a back-camera photo.

**Root cause:** Exact regex bug in `PHOTO_PATTERN` (`IrisListeningService.java:306-308`):
```java
"^(?:take|click|capture|snap)\\s+(?:a\\s+|my\\s+|the\\s+)?"
+ "(?:(front|selfie|back|rear)\\s+)?(?:camera\\s+)?(?:photo|picture|pic|selfie)\\b.*$"
```
The lens-word group `(front|selfie|back|rear)` only captures when a lens word appears **before** the trailing noun. But "selfie" is *also* one of the valid trailing nouns (`photo|picture|pic|selfie`). So for the phrase **"take a selfie"** — the single most natural way to ask — the regex engine matches "selfie" against the trailing noun alternative and leaves capture group 1 empty. `handleTakePhoto(photoM.group(1))` then receives `camWord = null`, and:
```java
final boolean front = camWord != null && (... .startsWith("front") || ... .startsWith("selfie"));
```
`null != null` → `false` → defaults to the back camera.

**Fix:** After matching, if group 1 is null, check whether the *matched noun itself* was "selfie" rather than relying only on the capture group. Concretely, change the check at the call site (`IrisListeningService.java:1520`):
```java
Matcher photoM = PHOTO_PATTERN.matcher(normalized);
if (photoM.matches()) {
    String camWord = photoM.group(1);
    if (camWord == null && normalized.contains("selfie")) camWord = "selfie";
    handleTakePhoto(camWord);
    return;
}
```
This is the minimal, correct fix — it does not touch the regex (avoiding new false positives) and makes the existing "selfie anywhere in the phrase → front camera" intent (already used correctly elsewhere at line 1366 for the "open camera and take a photo" path) consistent here too.

---

## 5. Screenshot request appears to "start screen capturing" / needs a hardware button combo

**Symptom:** Asking for a screenshot behaves like starting a screen recording; screenshots otherwise require pressing volume-down + power simultaneously.

**Root cause (two layers):**
1. **The "simultaneous button press" the user describes is Android's own native screenshot gesture**, not something IRIS is triggering — this happens when IRIS's accessibility-service fast path isn't enabled, so the user is manually taking the screenshot themselves and IRIS's voice command isn't doing anything at all.
2. **The system dialog reading like "start recording"** is real and not fixable at the app level: `handleScreenshot()` falls back to `launchScreenCapture("shot", 0)` → `ScreenCaptureActivity` → `MediaProjectionManager.createScreenCaptureIntent()`. Android's own system permission dialog for **any** MediaProjection use — screenshot or video — says "Start recording or casting?" This is because a single-frame MediaProjection screenshot is technically implemented as starting (and immediately stopping) the same recording API; Android does not offer a lighter-weight screenshot-only consent flow. This is a platform limitation, not an IRIS bug.

The actual fix, then, is to make sure the **fast, dialog-free path is what's actually active**, since that path (`IrisAccessibilityService.takeScreenshotNow()` using `GLOBAL_ACTION_TAKE_SCREENSHOT`) requires **no MediaProjection consent at all** and produces an instant screenshot with no recording-style dialog.

**Fix:**
1. `handleScreenshot()` already checks `IrisAccessibilityService.available()` first — the real issue is that **the accessibility service is very likely not enabled** on the user's device (it's an opt-in, separate Android permission the user must manually grant in Settings → Accessibility). Add a clear, one-time onboarding prompt: when the user first asks for a screenshot and the accessibility path isn't available, tell them explicitly ("Enable IRIS's screenshot shortcut in Accessibility settings — I'll open that now") and deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS`, instead of silently falling through to the MediaProjection dialog every time.
2. Add a persistent status indicator (e.g. in the Command Deck / Settings) showing whether the fast screenshot path is currently enabled, so this is diagnosable without reading logs.
3. Document clearly in the app (Guide/feature card) that without the accessibility permission, every screenshot will show Android's own recording-style consent dialog — this is expected platform behavior, not a bug, once the accessibility path is confirmed off.

---

## 6. Media resumes automatically after taking a photo, even though it was off before the command

**Symptom:** Media was already paused/off; after "take a photo," it starts playing again.

**Root cause:** `IrisListeningService.requestSpeechFocus()` / `abandonSpeechFocus()` (around line 5701-5755) form a **per-utterance** pause/resume transaction with no memory across utterances in the same command:
```java
private void requestSpeechFocus() {
    pausedMediaForSpeech = am.isMusicActive();   // sampled fresh, every single time TTS speaks
    if (pausedMediaForSpeech) { /* send PAUSE key */ }
    ...
}
private void abandonSpeechFocus() {
    if (pausedMediaForSpeech) { pausedMediaForSpeech = false; /* send PLAY key */ }
}
```
A photo capture involves **two separate TTS utterances**: the "Taking a photo…" intro, and later the "Saved a photo to…" result (fired from `ACTION_CAPTURE_DONE` after the camera activity finishes). Each call to `speakThenRun` independently calls `requestSpeechFocus()`, which re-samples `am.isMusicActive()` **at that moment**. `isMusicActive()` is a known-flaky Android API — it can report `true` due to leftover audio-focus/session state (camera shutter sound, a lingering media session from an app that isn't actually playing anything, etc.), even when nothing is audibly playing. If the *second* utterance's fresh sample returns a false `true`, IRIS "pauses" nothing (since nothing is playing) but still sets `pausedMediaForSpeech = true`, and then unconditionally fires `KEYCODE_MEDIA_PLAY` on `abandonSpeechFocus()` — which can wake up whatever media session was last active, even one the user turned off long before this command.

**Fix:** Track "did IRIS itself pause something during this command" as **one flag scoped to the whole command**, not per-utterance:
1. Add a single instance field, e.g. `private boolean commandPausedMedia;`, reset to `false` at the start of every `handleCommandInner()`.
2. In `requestSpeechFocus()`, only set `pausedMediaForSpeech = true` (and send PAUSE) if media is actually active **and** this is the *first* speech focus request in the command (i.e., only pause once per command, not once per utterance).
3. In `abandonSpeechFocus()`, only send PLAY if `commandPausedMedia` was set **by this exact command's first pause**, and clear it once the whole command's flow completes (`rearmAfterAction()`), not after every individual TTS line.
4. This guarantees: if media was off before the command started, IRIS never sends a PLAY key during that command, no matter how many utterances it speaks or how `isMusicActive()` fluctuates mid-flow.

---

## 7. Cannot take pictures/videos on the lock screen

**Symptom:** Locked-screen photo/video capture still doesn't work.

**Root cause:** `LockedCaptureActivity` itself is correctly built (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`, Camera2 opened only after `onResume()` per Android's while-in-use camera access rules). The class-level comment is honest about this: *"background/locked camera behaviour varies by OEM and Android version — this is the best-effort path and may require the CAMERA permission to be pre-granted and battery optimisation disabled for IRIS on some devices."*

Given issue #2 above (no battery-optimization exemption requested anywhere in the app), this is very likely the same root cause manifesting twice: if IRIS's process/service is being killed or throttled by the OEM's background-app policy, the lock-screen capture activity can be blocked from launching at all, or killed mid-capture, regardless of how correctly it's coded.

**Fix:**
1. Fix #2 first (battery-optimization exemption) — this is a prerequisite, not optional, for reliable lock-screen capture on most OEM devices.
2. Verify camera + microphone + notification permissions are actually granted (the activity already checks `CAMERA` at `onCreate` and reports clearly if missing — confirm this message is actually reaching the user and not being swallowed by a killed process before it can speak).
3. Add a specific diagnostic: log (and optionally surface) *which* stage failed — permission missing, camera open failed, activity never resumed (killed before `onResume`), or session configuration failed — since `LockedCaptureActivity.done()` already produces distinct messages for each of these; confirming which one fires in practice on the user's device is the fastest way to pinpoint whether this is an OEM-kill issue (fix #2) or a genuine device/API compatibility gap needing a device-specific workaround.
4. If, after fix #2, the activity still can't launch on lock screen on a specific OEM, that is a documented per-OEM Android platform restriction (some manufacturers disallow `showWhenLocked` activities from non-system apps entirely) — the honest outcome may be "unsupported on this OEM," which should be stated rather than silently retried forever.

---

## 8. "Tell me something about myself" not understood

**Symptom:** This exact phrase gets no sensible response.

**Root cause:** `RECALL_PATTERN` (`IrisListeningService.java:384`):
```java
".*\\b(?:what\\s+do\\s+you\\s+(?:know|remember)(?:\\s+about\\s+(.+))?"
+ "|know\\s+about\\s+me"
+ "|tell\\s+me\\s+about\\s+(?:me|myself)"
+ "|what.?s?\\s+in\\s+my\\s+memory"
+ "|my\\s+(?:memories|info|memory|details|profile))\\b.*"
```
This *does* include `tell\s+me\s+about\s+(?:me|myself)` — so "tell me about myself" should match. But the user's exact phrasing was **"tell me something about myself"** — note the extra word "**something**" between "me" and "about." The regex requires "tell me about myself" verbatim (no word in between); "tell me **something** about myself" does not match any alternative in the pattern, so it falls through to general chat handling instead of the memory/recall flow.

**Fix:** Add the natural variant with an optional filler word:
```java
"|tell\\s+me\\s+(?:something\\s+)?about\\s+(?:me|myself)"
```
This lets "tell me about myself" and "tell me something about myself" both match, without loosening the pattern enough to accidentally catch unrelated phrases (the fixed words "about myself/me" still anchor it). Apply the same "something" tolerance check to the other recall variants if a broader review turns up similar natural-language gaps (e.g. "what do you know about me" vs "what do you actually know about me").

---

## 9. "Read Once" doesn't work

**Symptom:** Tapping Read Once on the Command Deck does nothing.

**Root cause:** The button wiring itself (`MainActivity.java` around line 489) and the underlying `SystemTelemetryController.refreshNow()` are structurally correct — `refreshNow()` calls `network.sampleTraffic()` then `rebuild(true)`, and the click handler calls `renderDeck(telemetry.latest())` right after. However, `rebuild()` wraps its entire body in a blanket `catch (Throwable ignored) { }` (`SystemTelemetryController.java:100-116`), and the individual collectors (`NetworkTelemetryCollector`, `BluetoothTelemetryCollector`, `ResourceTelemetryCollector`, `PhoneDetailsCollector`) all run **synchronously on the calling thread** — which for a button tap is the **main/UI thread**. If any single collector throws (a permission check failing unexpectedly, a Bluetooth API call misbehaving on a specific OEM, a null system-service lookup), the *entire* `rebuild()` call silently aborts with zero diagnostic trail, and `latest` is never updated — from the user's perspective, tapping Read Once visibly does nothing, with no error, no log entry, nothing to go on.

**Fix:**
1. Replace the single blanket `try/catch` around the whole `rebuild()` body with **per-collector** try/catch, so one failing collector can't blank out the others:
   ```java
   try { network.contribute(b, settings.telemetryPublicIp()); } catch (Throwable t) { LogStore.append(ctx, "TELEMETRY", "network failed: " + t); }
   ```
   ...and similarly for `bluetooth.contribute`, `resources.contribute`, `PhoneDetailsCollector.contribute`. This ensures a partial, honest snapshot always renders instead of nothing.
2. Add a `LogStore.append` call inside each new catch block so failures are visible in the existing Logs screen instead of vanishing silently — this is the single highest-value change, since it turns an unreproducible "just doesn't work" report into an actual stack trace next time it happens.
3. Once actual failures are visible in logs, this becomes a normal, fixable bug instead of a mystery — re-diagnose with real data rather than more speculation.

---

## 10. Still using phone mic despite headphones connected

**Symptom:** Even with headphones (wired or Bluetooth) connected, IRIS reports/uses the phone mic.

**Root cause:** By design, during **wake listening** specifically, `chooseDevice()` is called with `allowBluetoothInAuto = btMicAllowed && !musicPlaying`, and `btMicAllowed` is `false` the entire time IRIS is only awaiting the wake phrase — it only becomes `true` once a command window opens. This is intentional (keeps Bluetooth music at full A2DP quality while merely awake) and was already reviewed and partially addressed in a prior session (wired headphones should already be preferred unconditionally per the `chooseDevice` wired-first logic — `if (wiredDev != null) return wiredDev;` runs regardless of `allowBluetoothInAuto`).

Given the decision in #3 (phone-mic wake during media is not being pursued; physical triggers are the chosen fix instead), this narrows to:
- **Wired headphones**: should already route correctly today (`wiredDev` takes priority unconditionally in `chooseDevice`) — if the user is still seeing "phone microphone" with wired headphones connected, that's a **second, distinct bug** in device-type detection (worth a targeted log check: what `AudioDeviceInfo.getType()` actually reports for their specific headphone model — some no-name wired headsets report as `TYPE_USB_DEVICE` or an unrecognized type not yet in the `wired` check list).
- **Bluetooth headphones**: remain intentionally excluded from wake listening's mic selection — this is confirmed as-designed (music-quality tradeoff), not a bug, and is superseded by the trigger-button approach in #11 for the "phone not nearby" scenario.

**Fix (wired-only scope now):**
1. Add a diagnostic log line inside `configureAudioRoute()` printing the exact `AudioDeviceInfo.getType()` int for every detected input device whenever wake listening starts — this makes it possible to confirm, from a single log capture, exactly which device type the user's wired headphones report as, rather than guessing.
2. If a specific wired headphone reports as an unrecognized `AudioDeviceInfo` type, extend the `wired` check in `chooseDevice()` (already extended once for `TYPE_USB_DEVICE`/`TYPE_USB_ACCESSORY`) to cover it.

---

## 11. Physical trigger buttons don't work, and need to be customizable

**Symptom:** Shake-to-wake and the headset double-press trigger don't work. Requested: pick which button/gesture wakes IRIS — media play/pause, next, previous, or double/triple-press of volume up/down.

**Root cause — two separate, real bugs found, plus one hard platform limitation:**

**Bug A — toggling either trigger in Settings does nothing until the app is fully restarted.** `setupTriggers()` (`IrisListeningService.java:517`) is called exactly once, from `onCreate()`. The Settings switches for both triggers already know this and say so:
```java
toast("Shake trigger " + (checked ? "on" : "off") + " — restart IRIS to apply.");
```
So if the user toggled these on without force-restarting the app, they were never active in the first place — this alone accounts for "doesn't work."

**Bug B — the headset trigger's `MediaSession` loses to whatever app is actually playing music.** Android delivers Bluetooth media-button events (play/pause/next/prev) to the system's currently **preferred** `MediaSession` — normally whichever app most recently started active playback. IRIS calls `mediaSession.setActive(true)` once at startup, but never re-asserts priority, so **while music is genuinely playing** (the exact scenario the trigger is meant for), the music app's session — not IRIS's — receives the button press first. IRIS's callback can only fire in the case it's least needed: when nothing else is playing. This is a design gap, not a typo — a `MediaSession`-only approach cannot reliably win against an actively-playing app without extra work (see fix below).

**Hard platform limitation — Bluetooth volume buttons cannot be intercepted.** Requested in the customization list: double/triple-press of volume up/down. On Bluetooth earphones, volume button presses are handled by the AVRCP protocol layer and delivered as `ACTION_VOLUME_CHANGED` system broadcasts — **not** as `KEYCODE_MEDIA_*` events through `MediaSession`, and **not interceptable/abortable** by any regular Android app; the system applies the volume change first and only then lets apps observe it, with no way to gate, consume, or repurpose it. Volume-button custom actions are only reliably possible using the **phone's own physical volume buttons** while the phone is in-hand (a `KeyEvent` a foreground/overlay component can observe) — never as a genuine Bluetooth-earphone gesture. This must be stated plainly rather than attempted and silently failing.

**Fix — robust, customizable trigger system:**

1. **Fix the restart requirement.** Extract trigger setup/teardown so it can run on demand: add `refreshTriggers()` that calls `teardownTriggers()` then `setupTriggers()`, and call it immediately from each Settings switch's `onCheckedChangeListener` via a broadcast/service call (`IrisListeningService` already receives commands via `onStartCommand`, so add a lightweight `ACTION_REFRESH_TRIGGERS`). No more "restart IRIS to apply."

2. **Fix `MediaSession` priority properly**, so play/pause/next/prev genuinely work as triggers *while music is playing* (the actual use case):
   - Re-call `mediaSession.setActive(true)` and refresh the `PlaybackState` immediately before/around each trigger registration, and periodically re-assert it (e.g. on every wake-phase entry) rather than once at startup — reduces (but per Android's documented behavior, cannot 100% guarantee) session-priority loss to the active player.
   - Explicitly document that this remains best-effort: Android's exact session-priority algorithm is undocumented/OEM-varying, so 100% reliability while another app plays cannot be promised — set the user's expectation honestly in the feature description rather than implying a guarantee.

3. **Make the trigger button customizable**, per the request. Add a Settings choice (radio group or spinner): "Wake IRIS with: Play/Pause · Next · Previous · Double-press Play/Pause · Triple-press Play/Pause" (drop volume up/down from this list — per the hard limitation above, it cannot be implemented for Bluetooth earphones; if the user specifically wants a **phone-hardware** volume-button trigger while holding the phone, that is a separate, feasible feature — see note below).
   - Store the choice as `AppSettings.triggerKeyCode()` / `triggerPressCount()` (e.g. `KEYCODE_MEDIA_PLAY_PAUSE` + count 1, or `KEYCODE_MEDIA_NEXT` + count 3, etc.).
   - Generalize the existing double-press-detection logic in `onMediaButtonEvent` (currently hardcoded to `KEYCODE_HEADSETHOOK` + double-press only) into a small reusable press-counter keyed by the configured `KeyEvent` code and required press count, with the same "pass the event through if the count isn't met in time" behavior already correctly implemented for the existing double-press case (so normal play/pause/skip still works when the user *isn't* trying to trigger IRIS).
4. **Note on phone-hardware volume buttons (optional, separate feature):** unlike Bluetooth volume, the phone's own physical volume buttons **can** be observed by an app while it's in the foreground, or system-wide via an Accessibility Service (`AccessibilityService.onKeyEvent`, already have `IrisAccessibilityService` in the codebase for the screenshot fast-path — it could be extended to also watch for a configured double/triple volume-button press). This only works when the user is physically holding and pressing the phone's own buttons — it does **not** extend to a Bluetooth earphone's separate volume control, and should be labeled clearly as "phone volume buttons" in the UI to avoid the same confusion driving this bug report.
5. **Test both fixes independently**: confirm a Settings toggle takes effect immediately without restart (Bug A), and confirm the chosen media-button trigger fires reliably both with and without other apps actively playing audio (Bug B) — these are separate failure modes and both need their own pass/fail check.

---


## Priority order for implementation

1. **#1 AI brain** — remove (safety: crash risk, zero functional loss since it's opt-in and off by default).
2. **#2 Battery optimization exemption** — foundational fix; #7 (lock-screen capture) likely depends on it.
3. **#6 Media auto-resume** — clear, scoped, low-risk fix (one flag, two methods).
4. **#4 Selfie → back camera** — one-line fix at the call site, zero regex risk.
5. **#8 "tell me something about myself"** — one regex addition, zero risk.
6. **#11 Trigger buttons (shake/headset), made customizable** — this is now the primary fix for "IRIS doesn't respond during media playback," replacing the abandoned #3 approach. Fix the restart-required bug first (quick, mechanical), then the MediaSession priority issue, then add the button/press-count customization UI.
7. **#9 Read Once silent failures** — add per-collector error isolation + logging; unblocks real diagnosis of any remaining issue.
8. **#10 Headphone mic detection (wired only)** — needs a diagnostic log pass to confirm whether there's a real device-type-detection bug beyond the confirmed, unchanged Bluetooth-during-wake tradeoff.
9. **#5 Screenshot / MediaProjection dialog** — mostly a platform limitation; the fix is onboarding/clarity (point the user at the accessibility permission), not new capture logic.
10. **#7 Lock-screen capture** — re-test after #2 lands; may resolve as a side effect, or reveal a genuine OEM-specific gap that needs to be documented as unsupported rather than chased indefinitely.

**Decided against:** #3 (phone-mic wake during media playback) — phone is frequently not in mic range during Bluetooth-earphone listening, so this doesn't solve the real scenario. #11's trigger-button approach replaces it.
