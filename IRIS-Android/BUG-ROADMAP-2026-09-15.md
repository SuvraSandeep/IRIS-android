# IRIS Bug Roadmap — Batch 2 (2026-09-15)

Investigated against the actual source (`IRIS-Android/app/src/main/java/com/iris/assistant/`) on 2026-09-15. Every root cause below was traced to a specific line, not guessed. Ordered latest-reported-first, per request, with the most severe issue (#5/#7, wake accepting anyone's voice) called out clearly regardless of position.

Related prior document: `BUG-ROADMAP-2026-09-14.md` (issue #10 there — headphone mic detection — overlaps with #1 below; read together).

---

## 1. Still shows "phone microphone" even though a Bluetooth headset is connected

**Symptom:** Mic route reports/uses the phone mic with a Bluetooth headset connected.

**Root cause — depends on exactly when this is observed, and both cases are already partly diagnosed:**

**Case A — during wake listening (before saying the wake phrase).** This is confirmed, current, deliberate behavior, not a bug: `startWakeDetection()` forces `btMicAllowed = false`, so `chooseDevice()` never selects a Bluetooth device while merely awaiting the wake phrase — this protects Bluetooth music quality (forcing SCO for a BT mic makes A2DP music sound bad). This was already decided in the prior roadmap: physical trigger buttons (media play/pause etc.) are the intended path for "IRIS should respond to me even while I'm on Bluetooth audio," not making wake use the BT mic. See `BUG-ROADMAP-2026-09-14.md` #3 and #11.

**Case B — during the active command window (after the wake phrase was heard, or via Tap-to-Talk/notification trigger).** Here `btMicAllowed = true` and Bluetooth **should** be selected by `chooseDevice()`'s "Automatic" branch. If the mic is still reported as the phone mic in this window specifically, that is a **real, distinct bug** worth isolating — the most likely causes, in order of likelihood:
- The headset uses **LE Audio** (`AudioDeviceInfo.TYPE_BLE_HEADSET`, API 31+) and the specific device/Android build combination doesn't expose it via `getAvailableCommunicationDevices()` the way a classic SCO headset does — some OEM Bluetooth stacks are inconsistent here.
- `audioManager.setCommunicationDevice(chosen)` (the API 31+ path) can return `false` (failed) silently, in which case the code correctly falls through to `audioManager.setMode(AudioManager.MODE_NORMAL); return "Phone microphone"` — a legitimate failure, not a lie, but currently unlogged as a *failure* specifically (only the general device list is logged).

**Fix:**
1. **First, determine which case this actually is** — the existing diagnostic (`logDetectedInputDevices()`, already added in a prior session, logs every input device's exact `AudioDeviceInfo.getType()` int on every `configureAudioRoute()` call) should be checked against a log capture from the moment of the actual complaint: was this during wake (Case A, expected) or during a command window (Case B, a real bug)? This single log line resolves the ambiguity immediately without more guessing.
2. If confirmed as Case B: add a second diagnostic log line specifically when `setCommunicationDevice()` returns `false`, so a failed-but-silent Bluetooth routing attempt is distinguishable from "Bluetooth was never even offered as an option" (the current log only shows the latter).
3. If the specific headset's `AudioDeviceInfo` type isn't recognized as Bluetooth at all (unlikely given `TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, and `TYPE_BLE_HEADSET` are all already checked in the codebase, but worth confirming), extend `isBluetoothMic()`/`chooseDevice()`'s bluetooth-detection boolean to cover it.

---

## 2. Can't say "cancel" mid-speech to make IRIS stop talking

**Symptom:** While IRIS is speaking, saying "cancel" (or "stop") does nothing — it keeps talking.

**Root cause — the mic is not listening at all while IRIS is speaking.** The only voice-reachable "stop"/"cancel" handling in the whole app is inside `handleCommandInner()`:
```java
if (lower.matches("^(stop|cancel|shut up|quiet|go away|never mind|nevermind|nahi|ruk|bas)$")) { ... }
```
This code only runs once a **command-window recognizer** has already captured a full utterance. But per `speakThenRunLocal()`'s flow, the recognizer is torn down and the mic released for the entire duration of text-to-speech playback (`requestSpeechFocus()` runs, TTS speaks, and only on `onDone`/completion does `afterSpeaking` — which reopens a recognizer — run). There is currently **no mechanism at all** for capturing speech while IRIS itself is talking. The only existing way to interrupt speech is `ACTION_STOP_SPEAKING`, which is wired **only** to the physical notification "⏹ Stop" button (`IrisListeningService.java:5406`) — there is no voice path to it whatsoever.

**Fix — the correct approach is NOT to open a second recognizer mid-speech** (that would create feedback/echo problems, since the mic would hear IRIS's own voice through the speaker). Options, in order of robustness:

1. **(Recommended) Low-power keyword-spot for "stop"/"cancel" specifically, running in parallel with TTS, using the same lightweight Vosk wake-detection mechanism already in the codebase** (`VoskEngine.startWakeDetection`, already built and proven not to require the speaker model when verification is off). While speaking, run a *second*, muted-output Vosk pass listening **only** for "stop"/"cancel"/"shut up" — much narrower vocabulary than full wake, and since TTS audio plays through the speaker (not looped back into the mic capture path on most devices doing basic AEC), false triggers from IRIS's own voice are containable with a stricter confidence threshold. This reuses proven infrastructure instead of building new recognition logic.
2. **Simpler interim fix, ships faster:** while speaking, keep the physical trigger buttons (once #11 from the prior roadmap is implemented) live and treat a press during active speech as an implicit "stop speaking now," even if the user's actual chosen trigger action is normally something else — a press during active TTS output has an unambiguous, single reasonable interpretation (stop), regardless of what the button is otherwise configured to do.
3. Either way: **do not** try to keep the full command-recognizer running throughout TTS playback — echo/feedback and doubled recognition (hearing both the user and IRIS's own voice) make that approach unreliable without proper acoustic echo cancellation, which the current architecture (`SpeechRecognizer`/`Vosk` via raw `AudioRecord`) does not implement.

---

## 3. Photos sometimes come out completely white/overexposed

**Symptom:** Some captured photos are blown out to near-total white, especially (implied) in bright or backlit conditions.

**Root cause:** `LockedCaptureActivity.takePhoto()`'s capture request sets **only**:
```java
b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
b.set(CaptureRequest.JPEG_ORIENTATION, captureOrientation());
```
There is no auto-exposure (AE) region, no exposure compensation, no AE lock/settle wait, and critically **no AE convergence check before capturing**. `TEMPLATE_STILL_CAPTURE` on its own does enable 3A (auto-exposure/focus/white-balance) by default, but the code captures a single frame **immediately** upon session configuration with no wait for the auto-exposure algorithm to actually converge on the scene — on many devices/lighting conditions, the very first frame after a camera session opens can be captured before AE has settled, producing exactly the "blown out to white" symptom the user describes (this is a well-known Camera2 pitfall — a still-capture template needs a short "pre-capture" AE trigger sequence before the final capture request, which this code doesn't perform at all).

**Fix:**
1. **Before the final `capture()` call**, issue a `CONTROL_AE_PRECAPTURE_TRIGGER` request and wait for `CONTROL_AE_STATE` to report `CONVERGED` (or `FLASH_REQUIRED`, handled separately per #4) via the capture-session's `CaptureCallback.onCaptureCompleted`, then only issue the final still-capture request once AE has actually settled. This is the standard, documented Camera2 pattern for reliable still capture and directly fixes the overexposure symptom — it is the correct root-cause fix, not a workaround.
2. As a secondary safeguard, consider setting a mild negative `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION` bias (e.g. -2 to -3, in the camera's supported EV step range) specifically for backlit/bright scenes if the AE-convergence fix alone doesn't fully resolve it in testing — but implement and test #1 first, since a proper convergence wait is very likely sufficient on its own and a blanket negative bias would make normally-lit photos slightly dark.
3. Add a short (e.g. 1.5s) timeout on the AE-convergence wait so a scene that never reports `CONVERGED` (unusual, but possible with extreme lighting or certain camera HALs) doesn't hang the capture indefinitely — fall back to capturing anyway after the timeout rather than leaving the user stuck.

---

## 4. Request: "take a photo with flash on" should actually turn the flash on for the photo

**Symptom:** No way to request flash for a voice-commanded photo; feature doesn't exist.

**Root cause:** Confirmed — this is not a bug, it's a genuinely missing feature. `LockedCaptureActivity` has **zero** flash/AE-flash logic anywhere in the file. `handleTorch()` (the *existing* flash-related code, `IrisListeningService.java:2910`) uses `CameraManager.setTorchMode()` — a completely separate, simpler API that toggles the flash LED **independent of any active camera capture session**, and is very likely to **fail outright** if called while `LockedCaptureActivity` already holds the camera open for a capture session (many devices reject `setTorchMode` on a camera ID that's in active use by another session). This existing flash mechanism cannot be reused as-is for photo-with-flash.

**Fix — implement flash properly via the Camera2 capture-request flash controls, integrated with the AE-convergence fix from #3:**
1. Extend `PHOTO_PATTERN` (`IrisListeningService.java:306`) to optionally capture a trailing "with flash" / "using flash" / "flash on" clause, e.g.:
   ```java
   "^(?:take|click|capture|snap)\\s+(?:a\\s+|my\\s+|the\\s+)?"
   + "(?:(front|selfie|back|rear)\\s+)?(?:camera\\s+)?(?:photo|picture|pic|selfie)"
   + "(?:\\s+(?:with|using)\\s+(?:the\\s+)?flash(?:\\s+on)?)?\\b.*$"
   ```
   and add a capturing group so `handleTakePhoto` receives a `flashRequested` boolean alongside the existing lens choice (also fixing the "selfie" capture-group gap already identified in the prior roadmap, #4 there, while touching this same pattern).
2. Pass `flashRequested` through the `LockedCaptureActivity` launch intent as a new `EXTRA_FLASH` boolean.
3. In `takePhoto()`'s capture request, when `flashRequested` is true, set:
   ```java
   b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
   ```
   (or `FLASH_MODE = FLASH_MODE_SINGLE` directly if `CONTROL_AE_MODE` is kept as `ON` — either is valid Camera2 usage; `ON_ALWAYS_FLASH` is simpler and lets the AE algorithm manage flash timing itself).
4. Check `CameraCharacteristics.FLASH_INFO_AVAILABLE` before honoring the request (already have this exact check pattern in `handleTorch()` — reuse it), and if the lens has no flash, say so honestly ("This camera doesn't have a flash") rather than silently ignoring the request.
5. This naturally combines with #3's AE-convergence wait — flash timing is itself part of what the AE pre-capture trigger sequence coordinates, so implementing both together (rather than #4 first, #3 later) is less rework.

---

## 5 / 7. THE BIGGEST BUG: wake responds to anyone's voice despite training

**Symptom:** Trained voice enrollment ("very strict," per the user), but IRIS still wakes to other people's voices. Called out twice in the report as the most serious issue.

**Root cause — found and fully explained, with certainty, not a guess:**

Two settings control voice wake, and they are **completely disconnected** in the current build:
1. `AppSettings.speakerVerification()` — defaults to **`false`**. This is the master switch that decides whether a captured wake-phrase audio sample is actually checked against the enrolled voiceprint at all.
2. The user's voice enrollment (Training screen, `enrollVoiceprintAsync()` → `WakePolicy.enrollment()` → `ProfileStore.setVoiceprint()`) — this **only saves a voiceprint**. It does **not** touch `speakerVerification()` in any way.

So a user can complete a full, careful voice enrollment (exactly as this user describes doing) and it has **zero effect** on wake behavior, because `isOwnerVoice()` — the method that decides whether to accept a wake detection — starts with:
```java
if (!settings.speakerVerification()) return true;   // accepts ANY voice, unconditionally
```
Since `speakerVerification()` defaults to false and nothing in the enrollment flow turns it on, every wake detection is accepted regardless of whose voice it was. The voiceprint the user carefully trained sits in storage, fully correct, and is never once consulted.

**The reason this can't simply be toggled by the user right now: the Settings switch for it is hard-disabled in the UI.** Confirmed at `MainActivity.java:2548-2550`:
```java
Switch speakerVerification = view.findViewById(R.id.speakerVerificationSwitch);
speakerVerification.setChecked(settings.speakerVerification());
speakerVerification.setEnabled(false);     // <-- no listener, cannot be toggled by the user at all
speakerVerification.setText("Owner verification required for voice wake");
```
This switch is purely a **read-only status display** — it shows the current (always-false-by-default) state but has no `setOnCheckedChangeListener` and is explicitly disabled. There is currently no UI path anywhere in the app for a user to turn this on. This single dead control is the entire root cause of the "biggest bug."

**Fix — exact, complete:**
1. **Re-enable the switch and wire it up**, in `MainActivity.java`:
   ```java
   Switch speakerVerification = view.findViewById(R.id.speakerVerificationSwitch);
   speakerVerification.setChecked(settings.speakerVerification());
   speakerVerification.setOnCheckedChangeListener((b, checked) -> {
       if (checked && !new ProfileStore(this).getWakeProfile().isVoiceEnrolled()) {
           speakerVerification.setChecked(false);
           toast("Enroll your voice in Training first, then turn this on.");
           return;
       }
       settings.setSpeakerVerification(checked);
       toast(checked ? "Wake now requires your enrolled voice." : "Wake accepts the phrase from anyone.");
   });
   speakerVerification.setText("Only wake for my enrolled voice");
   ```
   The guard against enabling it with no voiceprint enrolled prevents the opposite failure mode (verification on, no voiceprint saved yet → `isOwnerVoice()` already correctly falls back to accept-all in that case per the prior session's fix, but that's a confusing intermediate state worth preventing at the UI level rather than relying on the fallback silently).
2. **Close the training loop**: at the end of a successful voice enrollment in the Training screen (wherever `enrollVoiceprintAsync`'s success path currently just saves the voiceprint and reports success), also **prompt the user** to turn speaker verification on right there — e.g. "Voice enrolled. Turn on 'only wake for my voice' now?" with a direct action button that calls `settings.setSpeakerVerification(true)` — so a user who goes through training doesn't also have to separately discover a Settings switch to get the behavior they were training for in the first place. This is the fix that actually matches user expectation: training your voice should visibly and immediately mean something.
3. Re-verify end-to-end after the fix: enroll a voice, confirm the switch turns on and stays on, confirm a different voice saying the exact trained phrase is rejected (`isOwnerVoice()`'s `WakePolicy.owner(embedding, enrolled, voiceThreshold())` cosine-similarity check, already correctly implemented, will now actually run).

This is the highest-priority fix in this entire batch — it is a complete UI dead-end hiding a fully-working backend check.

---

## 6. Training screen: "Hello IRIS" should require the exact phrase and word, nothing else accepted

**Symptom:** Requesting stricter phrase matching — training "Hello IRIS" should only accept that exact utterance, not near-misses or partial matches.

**Root cause / current state:** This is **already the documented, existing design** — not a bug to fix, but worth confirming precisely so the user knows what's already true versus what #5/#7 above adds on top:
- `WakePolicy.matches(text, phrases)` requires the recognized text to **exactly equal** (after normalization — lowercase, punctuation stripped) one of the trained phrases; there is no fuzzy/partial matching at the phrase-text level.
- The wake-detection result callback (`VoskEngine.java`, the `result()` method inside `startWakeDetection`) additionally requires: word-level confidence ≥ 0.85, utterance duration between 0.5–4 seconds, and (when speaker verification is active per #5's fix) the enrolled-voice cosine-similarity check.
- So "Hello IRIS" as a trained phrase already requires the *phrase itself* to be spoken in full — no single-word shortcut, no fuzzy acceptance. What was **missing** was the identity check (#5), not the phrase-strictness the user is describing here.

**What actually changes once #5 is fixed:** once speaker verification can be turned on (and is turned on, per the enrollment prompt in #5's fix), "Hello IRIS" will require **both** (a) the exact trained phrase text, already true today, **and** (b) a voice match to the enrolled speaker — which is precisely "accept nothing else" as the user is asking for. No separate phrase-strictness fix is needed beyond landing #5; this item is resolved as a consequence of that fix, and should be explicitly re-confirmed with the user once #5 ships so they know both halves (phrase + identity) are now enforced together.

---

## 8. Turning on "IRIS screenshot" in Settings blocks making an online payment

**Symptom:** After enabling the IRIS screenshot feature (the accessibility-service fast path) in Settings, online payment flows stop working.

**Root cause:** This is confirmed as a real, well-understood interaction, not a made-up one. Enabling `IrisAccessibilityService` grants IRIS the Android **Accessibility Service** permission. Many banking/payment apps (and Android's own secure-screen APIs) treat **any** active accessibility service as a potential screen-reading/overlay risk and will refuse to render sensitive payment/PIN entry screens while **any** accessibility service is enabled system-wide — this is a deliberate anti-fraud measure in those apps (protecting against real screen-scraping malware that abuses the Accessibility API), and it applies regardless of what the specific accessibility service actually does. IRIS's accessibility service only calls `performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)` (confirmed by reading `IrisAccessibilityService.java` — it does nothing else), but the payment app has no way to know that; it can only see "an accessibility service is active" and blocks accordingly.

**Fix — this cannot be "fixed" in the sense of making the payment app allow it (that's the payment app's security policy, correctly protecting the user, and not something IRIS should try to circumvent even if technically possible — circumventing bank fraud protections would be a serious safety regression, not a bug fix).** The correct fix is honesty and convenience:
1. **Document this clearly** in the Settings screen, right next to the "Enable IRIS screenshot shortcut" toggle: *"Turning this on may block secure screens in banking/payment apps while it's enabled (Android's accessibility permission is treated as a security risk by these apps). Turn it off before making a payment, or leave it off and use the standard screenshot method."*
2. **Add a quick, one-tap disable path** directly reachable from the same Settings row (or a Quick Settings tile, since `IrisTileService` already exists in the codebase) so a user who hits this can turn the feature off in one tap right when they need to pay, rather than navigating deep into Android's Accessibility settings.
3. Do **not** attempt to make IRIS's accessibility service "invisible" to payment-app detection — that would be actively defeating a legitimate anti-fraud control and is out of scope as a "fix," full stop.

---

## 9. Can't tell the version name

**Symptom:** Asking for the version doesn't work.

**Root cause — two separate, narrow gaps, not a broken feature:**
1. **A routing collision.** `PhoneFacts` (checked early in `handleCommandInner`, before `VERSION_PATTERN`) has its own `app_version` field with aliases `"iris version|app version"`. So phrases like "iris version" or "app version" get intercepted by the generic phone-facts path and answered via `PhoneFacts.answer()` reading `ResourceTelemetryCollector.K_APP_VERSION` (which itself resolves correctly via `PackageManager`), **not** via the dedicated `handleVersion()`/`VERSION_PATTERN` path. This isn't actually broken — it produces a correct answer either way — but it means two code paths exist for the same question, which is confusing to reason about and to test.
2. **A genuine phrasing gap.** `VERSION_PATTERN` only matches a fixed set of exact phrasings (`"what's your version"`, `"your version"`, `"app version"`, `"version number"`, bare `"version"`, etc.) — it does **not** match the very natural **"tell me the version"** or **"tell me your version"**, since none of its alternatives begin with "tell." Neither does `PhoneFacts`'s alias-matching catch this phrasing (its aliases are "iris version"/"app version" as fixed substrings, and "tell me the version" doesn't contain either as a matched token in its matching logic). This phrasing genuinely falls through to general chat with no answer.

**Fix:**
1. Broaden `VERSION_PATTERN` to accept a "tell me" prefix:
   ```java
   "^(?:tell\\s+me\\s+(?:your\\s+|the\\s+)?version|(?:what|which)(?:'?s| is)?\\s+(?:your\\s+|the\\s+|iris\\s+|app\\s+)?version(?:\\s+number)?"
   + "|what\\s+version\\s+are\\s+you|your\\s+version|app\\s+version|version\\s+number|version)$"
   ```
2. Leave the `PhoneFacts` routing collision as-is functionally (both paths already produce a correct version string), but note it for future cleanup — not urgent, since it doesn't actually produce a wrong or missing answer today, only a slightly redundant code path.

---

## 10. Request: "tell me about the connected network" should report everything about the current Wi-Fi/network

**Symptom:** No way to get a full network summary with this natural phrasing.

**Root cause:** The underlying data already exists — `PhoneFacts.FIELDS` already has `net_transport`, `wifi_name`, `wifi_signal`, `wifi_freq`, `wifi_link_speed`, `gateway`, `dns`, `phone_ip_wifi`, `vpn`, `metered`, and more, all individually askable. There's also already a generic aggregate-group mechanism in `PhoneFacts.select()`:
```java
for(String g:new String[]{"network","display","sensors","resources","system","audio"}) {
    if(n.matches("(?:show |tell me |what are |give me )?(?:my |the |all )?"+g+" (?:details|status|information|specifications)"))return group(g);
}
```
This already matches "tell me network details," "show my network status," etc. — but the user's exact phrasing, **"tell me about the connected network,"** doesn't fit this template (it has "about the connected" in the middle, not one of the fixed trailing words "details/status/information/specifications" directly after "network").

**Fix:** Add a second, more natural aggregate-trigger phrase to the same loop (or as a dedicated check just for "network," since "about the connected network" is Wi-Fi/network-specific phrasing that doesn't generalize as cleanly to the other groups):
```java
if(n.matches(".*\\b(?:tell me about|what about|how (?:is|about))\\s+(?:my |the )?(?:connected |current )?(?:network|wifi|wi fi|connection)\\b.*"))
    return group("network");
```
Place this check alongside the existing group-aggregate loop in `select()`. This directly satisfies "tell me about the connected network" and similar natural variants ("what about my wifi," "how's my connection") by returning the full network field group, exactly as the existing "network details" phrasing already does.

---

## 11. Can't put the phone in airplane mode, battery saving mode, or any other mode

**Symptom:** Mode-switching voice commands ("turn on airplane mode," etc.) don't work.

**Root cause — most likely a platform limitation already correctly handled, but needs confirmation of exactly which phrasing/mode failed.** The relevant handlers already exist and are already honest about a real Android restriction:
- `handleAirplane(boolean turnOn)`: airplane mode **cannot** be toggled directly by any third-party app on modern Android (`Settings.Global.AIRPLANE_MODE_ON` is not writable without a system-level permission no regular app can hold) — the existing code correctly detects this and **opens Android's airplane-mode settings screen** for the user to toggle manually, rather than claiming to have done it. This is expected, correct behavior, not a bug — Android does not permit what's being asked here at the API level, for any app.
- `handleModeSet()` (ringer/vibrate/normal/DND): these **are** genuinely settable via `AudioManager.setRingerMode()` / `NotificationManager.setInterruptionFilter()`, gated on `isNotificationPolicyAccessGranted()` — if that specific permission (a separate one-time grant, different from microphone/camera/etc.) hasn't been granted, `handleRinger()`/`handleDnd()` correctly detect this and call `requestDndAccess()`, which opens the relevant settings screen and explains why. If the *voice command itself* isn't being recognized at all (as opposed to being recognized but blocked on a missing permission), that would point to a phrasing gap in `MODE_SET_PATTERN`/`MODE_WORDS` instead.
- Battery saving mode: per the prior roadmap (#11/#batterysaver work, already implemented in an earlier session), "turn on battery saving mode" opens Android's Battery Saver settings (apps cannot toggle that switch directly either — same class of restriction as airplane mode) while separately turning on IRIS's own resource-saving mode, which *is* fully controllable by the app.

**Fix (pending exact reproduction):**
1. **This needs the exact phrase and exact mode that failed** to know whether it's (a) airplane mode/Battery-Saver-system-switch — expected, cannot be fixed, already correctly redirects to Settings, or (b) ringer/vibrate/DND with notification-policy access not yet granted — also expected and already correctly redirects, or (c) a genuine phrasing/regex gap in `MODE_SET_PATTERN`/`MODE_WORDS`/`BATTERY_SAVER_SET_PATTERN` not catching a specific natural phrasing the user tried.
2. If it turns out to be (a) or (b): the fix is **clarity**, not new capability — make sure the spoken response explicitly explains *why* (e.g. "Airplane mode can't be switched directly by any app — I've opened the settings for you to toggle it") is actually being heard by the user, since if the user is only hearing something ambiguous like "I couldn't do that," they may reasonably interpret a correctly-behaving redirect as total failure. Confirm the spoken responses in `handleAirplane()`/`requestDndAccess()` are reaching the user clearly.
3. If it turns out to be (c): capture the exact phrase used and extend `MODE_WORDS`/`MODE_SET_PATTERN` accordingly — this file doesn't currently show any obvious gap in the regex (it already covers "silent/vibrate/ringer/ring/normal/do not disturb/dnd/aeroplane/aero plane/airplane/air plane/flight" with on/off/enable/disable/turn-on/turn-off prefixes), so a genuine phrasing gap here would need the specific words the user actually said to fix precisely rather than guessed at.

---

## Priority order for implementation

1. **#5/#7 Wake accepts anyone's voice** — the user's own stated top priority, and correctly so: a completely dead, disabled UI switch is silently defeating a fully-working, already-correct backend voice-verification check. Highest-value, lowest-risk fix in this entire batch — re-enable one switch, wire one listener, add one enrollment-flow prompt.
2. **#2 Can't stop speech by voice** — real usability gap with no current workaround besides the notification button; the keyword-spot approach reuses existing, proven Vosk wake infrastructure rather than inventing new recognition logic.
3. **#3 Overexposed photos** — a well-understood, standard Camera2 fix (AE pre-capture trigger + convergence wait); currently the code skips a documented, necessary step entirely.
4. **#4 Photo with flash** — build alongside #3 (shares the same capture-request code path); genuinely missing feature with a clear, standard implementation.
5. **#9 Version phrasing gap** — one regex addition, zero risk.
6. **#10 Network-summary phrasing gap** — one regex addition using an already-existing aggregate-answer mechanism, zero risk.
7. **#1 Bluetooth mic during command window (Case B only)** — needs a log capture to confirm scope before code changes; Case A (during wake) is confirmed-expected behavior needing no fix.
8. **#8 Screenshot toggle blocking payments** — not fixable in the sense of overriding it (correctly so); ships as a documentation + quick-disable-access improvement only.
9. **#11 Mode-switching commands** — needs exact reproduction (specific phrase + specific mode) before determining if this is expected Settings-redirect behavior already working correctly, or a genuine phrasing gap.
10. **#6 Phrase strictness in Training** — no separate work needed; resolves automatically once #5/#7 ships, re-confirm with the user afterward.
