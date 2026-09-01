# STEERING — Wake Word, Speaker Identity & Personal Profile

**Owner:** single user (private build). **Status:** approved design, ready to implement in phases.
**Chosen options:** 1A (audio-mode fix) · 2B (model-based speaker verification, ONNX) · 3A (Porcupine wake).

This steering file governs the next implementation phases. Follow it; update it if the design changes.
Also obey `PROJECT-RULES.md` (keep `IRIS-FEATURES.html` in sync, bump versions, ship zip + workflow).

---

## 0. Assumptions (correct me if wrong)
- Wake phrase: **"Hello IRIS"** (custom Porcupine keyword).
- Picovoice free AccessKey is acceptable; stored in the **personal JSON** (`wake.picovoice_access_key`).
- ONNX runtime + a speaker-embedding model may be bundled/downloaded (size is fine).
- Personal JSON (`iris-me.json`) lives **outside** the source zip (project root); build copies it into assets. An on-phone override at `Download/iris-me.json` takes priority if present.
- The assistant must know the user from the JSON **even when the AI brain is off** (feeds rule-based memory).

---

## 1. Feature 1A — Fix music tone changing while listening

**Problem:** `configureAudioRoute()` calls `audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION)`, forcing the whole device into call/communication audio mode → media is re-EQ'd/rerouted (tone change) and hardware stays high-power.

**Change:**
- Do **not** set `MODE_IN_COMMUNICATION` for the built-in mic path. Keep `MODE_NORMAL`.
- Only switch to communication mode (and `setCommunicationDevice`/`startBluetoothSco`) when the chosen mic is a **Bluetooth SCO / BLE headset**.
- Restore `previousAudioMode` on stop (already tracked) — ensure it's reset in `stopIris`.
- Never request audio focus for wake listening (don't duck/pause the user's music).

**Acceptance:** playing music keeps its normal tone while IRIS is armed/listening on the phone mic.

---

## 2. Feature 3A — Porcupine wake ("Hello IRIS")

**Dependency:** `ai.picovoice:porcupine-android:3.x` (Maven Central). Add to `app/build.gradle`.

**One-time user setup (document in-app + README):**
1. Sign up free at `console.picovoice.ai` → copy **AccessKey**.
2. Create a **custom keyword** "Hello IRIS" for **Android** → download the `.ppn` file.
3. Put the AccessKey in `iris-me.json` (`wake.picovoice_access_key`).
4. Place the `.ppn` at project root as `hello-iris.ppn`; the build copies it to assets (like the Vosk model).

**Engine wiring (new `PorcupineWakeEngine.java`):**
- Init with AccessKey + keyword `.ppn` from assets; `PorcupineManager` callback fires on detection.
- Very low CPU/battery vs continuous Vosk STT.
- On detection → hand control to the **speaker-verification gate** (§4), not straight to command.

**Fallback:** if AccessKey or `.ppn` is missing/invalid, fall back to the existing **Vosk grammar wake** (3B) automatically and log it. IRIS must never be dead just because the key isn't set.

**Selection logic:** `WakeManager` picks Porcupine when configured, else Vosk-grammar. Setting: none needed — auto-detect from presence of key + ppn.

---

## 3. Feature 2B — Speaker verification (only the owner's voice)

**Runtime:** add **ONNX Runtime for Android** (`com.microsoft.onnxruntime:onnxruntime-android`) OR bundle **sherpa-onnx** JNI libs. Prefer `onnxruntime-android` + a raw speaker-embedding model to minimize moving parts.

**Model:** a speaker-embedding model producing a fixed vector, e.g. **3D-Speaker ERES2Net (192-dim)** — matches the existing `SpeakerVerifier.EMBEDDING_SIZE = 192`. Bundle/download `speaker-embedding.onnx` (project root → assets via build).

**Implement `SpeakerVerifier` for real (replace the no-op):**
- `loadModel()` → create ONNX session from the model.
- `extractEmbedding(short[] pcm16k)` → run the model, return L2-normalized float[192].
- Keep existing `cosineSimilarity`, `enrollFromSamples`, `verify`, `verifyTier` (already correct).

**Enrollment (from Training, §6):** average embeddings from many samples → the **voiceprint** (float[192]), saved to disk (§7 persistence).

**Wake → verify pipeline:**
1. Porcupine detects "Hello IRIS" and gives the buffered audio window.
2. `SpeakerVerifier.verify(window, voiceprint, threshold)`.
3. **Match** → proceed to command listening (existing flow).
4. **No match** → play the **"not recognized" cue** (§5) and go back to sleep; log a `false-wake candidate` for feedback.

**Threshold:** driven by the **sensitivity slider** (§5) and adaptively tuned by feedback (§6). Store current threshold in persistence.

**Security note (documented):** voice verification is the spam/other-people filter; it is NOT unbreakable (a recording could pass). Sensitive actions (calls/SMS/WhatsApp) keep the **require-unlock** gate as the real security boundary.

---

## 4. Wake → Verify → Act state machine

```
SLEEP ──(Porcupine: "Hello IRIS")──▶ VERIFY
VERIFY ──(voice matches)──▶ COMMAND (existing STT + handleCommand)
VERIFY ──(no match)──▶ CUE("not recognized") ──▶ SLEEP   [log candidate]
COMMAND ──(done / timeout)──▶ SLEEP
```
Everything after VERIFY reuses the current command pipeline unchanged.

---

## 5. "Not recognized" cue + sensitivity slider

**Cue (customizable):**
- Toggle in Settings + `iris-me.json` (`wake.not_recognized_cue_enabled`, default on).
- **Varied phrasing** (never the same line twice in a row), **tone-aware** (uses the personality setting):
  - Sarcastic: "Nice try, but you're not the boss.", "That voice doesn't ring a bell."
  - Warm: "Hmm, I didn't quite recognise you.", "Sorry, that didn't sound like you."
  - Professional: "Voice not recognised.", "Authentication failed."
  - Silent tone: no speech, just a soft tick / nothing.
- Pull phrasing from a small pool per tone; pick randomly avoiding immediate repeat.

**Sensitivity slider (Settings + `verification_sensitivity` 0.0–1.0):**
- Maps to the cosine threshold: lenient (≈0.30) ↔ strict (≈0.75).
- Show a plain-language label under the slider ("Lenient — fewer misses" ↔ "Strict — fewer false wakes").

---

## 6. Redesigned Training section (long, robust, feedback-driven)

Replace the current wake-training with a **multi-phase enrollment + ongoing feedback** experience.

### 6.1 Enrollment wizard (robust voiceprint)
- **Phase 1 — Phrase capture:** record "Hello IRIS" **8–12 times**, varied: normal, slightly fast, slightly slow, quieter, from ~1 m away.
- **Phase 2 — Free speech:** record **2–3 short sentences** (~5–8 s) to strengthen the voiceprint beyond just the phrase.
- **Phase 3 — Noise sample (optional):** capture a few seconds of your room's ambient sound to calibrate rejection.
- Per-sample **quality meter** (level + SNR); reject/re-record poor samples.
- Compute averaged voiceprint (`enrollFromSamples`), show an **enrollment strength** score, allow "add more samples" any time.

### 6.2 Feedback loop (adaptive)
Two one-tap feedback actions, reachable from the home orb long-press, the Activity tab, and a persistent "Was that right?" prompt after uncertain events:
- **"That wasn't me"** (false wake / false accept) → lowers acceptance for that embedding, nudges threshold **stricter**, stores the clip as a **negative** example.
- **"I called but you didn't wake"** (miss / false reject) → nudges threshold **more lenient**, and offers **"Add this to my voiceprint"** (one tap) to enroll the missed clip.
- IRIS keeps the last N wake attempts' short audio clips in a ring buffer so feedback can reference the actual clip.

**Adaptive tuning:** maintain a running threshold adjusted within safe bounds from feedback counts (e.g., EMA of accept/reject outcomes). Never auto-move outside the slider's min/max; the slider sets the baseline.

### 6.3 UI
- Long, sectioned Training screen: **Enrollment**, **Voiceprint strength**, **Sensitivity**, **Feedback history**, **Backup/Restore**.
- Show recent wake events with ✓/✗ and quick feedback buttons.

---

## 7. Persistence & backup ("don't lose it")

**Stored artifacts:** voiceprint (float[192]), current adaptive threshold, feedback history/counters, enrollment metadata.

**Locations (layered):**
1. App-internal file (fast path).
2. **External copy** under `Android/data/<pkg>/files/iris-voice/` or `Download/IRIS/` so it survives reinstalls.
3. **One-tap Export/Restore**: bundle everything into a single `iris-identity.irisbackup` (JSON + base64 voiceprint) the user can drop into **Google Drive / GitHub** manually. Reuse the existing profile export/import plumbing.

**Optional (later phase):** direct Google Drive auto-sync via Drive REST + Google Sign-In. Flagged as future because it needs OAuth setup; not required for v1.

---

## 8. Personal JSON — `iris-me.json`

**Delivery:** lives at **project root** (outside the source zip). The build/workflow copies it into `app/src/main/assets/iris-me.json`. At runtime the loader prefers an on-phone override at `Download/iris-me.json` if present (edit without rebuild), else the bundled asset.

**Loader (`PersonalProfile.java`):** parse on startup; expose getters; **seed the rule-based MemoryStore** (name, relationships, contacts, preferences) so the assistant knows the user **even with AI off**; also injected into the AI prompt when AI is on. Missing file → sensible empty defaults (app still works).

**Schema:** see `iris-me.json` (delivered alongside this file). Covers identity (name, DOB, phone, personal + office email, blood group, languages), wake config (phrase, picovoice key, sensitivity, cue toggle), personality/voice, family (mother/father/spouse/siblings with names + numbers), relationships, arbitrary **special_contacts** (label→name/phone/email), places (home/work/favourites), preferences (tea/coffee, units, music app, nav app, food, wake/sleep times), work (company/role/hours), health (notes/meds/emergency contact), daily_routine, about_me free text, and a `custom` object for anything else.

---

## 9. Dependencies, permissions, build

- **build.gradle:** add `ai.picovoice:porcupine-android`, `com.microsoft.onnxruntime:onnxruntime-android`. Keep okhttp pinned 4.12.0. `noCompress 'onnx','ppn','tflite'`.
- **Assets bundled at build:** `hello-iris.ppn`, `speaker-embedding.onnx` (from project root, copied by the workflow, non-fatal if missing → Vosk fallback / verification-disabled).
- **Workflow:** extend `build-iris-apk.yml` (and inner copy) to copy `iris-me.json`, `hello-iris.ppn`, and `speaker-embedding.onnx` from repo root into assets before building; continue-on-error each.
- **Permissions:** existing RECORD_AUDIO suffices. No new dangerous permission for verification.

---

## 10. Settings additions
- Sensitivity slider (baseline threshold).
- "Not recognized" cue on/off.
- Voiceprint strength + "Add more samples".
- Export / Restore identity backup.
- (Wake engine + key are read from `iris-me.json`; show a read-only status: "Wake: Porcupine ✓ / Vosk fallback".)

---

## 11. Rollout phases (each builds, bumps version, updates IRIS-FEATURES.html)
1. **1A audio-mode fix** (tiny, immediate music fix).
2. **Personal JSON** loader + `iris-me.json` template + workflow copy + seed memory.
3. **2B speaker verification** (ONNX + real SpeakerVerifier + enrollment) — behind training.
4. **3A Porcupine wake** + Vosk fallback + verify pipeline + not-recognized cue + sensitivity.
5. **Redesigned Training + feedback loop + persistence/backup.**
6. (Optional later) Google Drive auto-sync.

---

## 12. Risks / notes
- ONNX + Porcupine are native libs → verify they load on-device; guard everything so failure degrades gracefully (Vosk wake, verification-disabled) rather than crashing (honour the crash-proofing already in place).
- Speaker verification can be fooled by recordings → keep unlock gate for sensitive actions.
- Can't access Google's low-power DSP hotword (system-only); Porcupine is the efficient software alternative.
- Test each native piece in isolation before enabling by default.
