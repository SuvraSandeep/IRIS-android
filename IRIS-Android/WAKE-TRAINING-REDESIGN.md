# IRIS Wake Training — Ground-Up Redesign (v10.0.0 design)

## Scope of this revision

Original instruction was to redesign for robustness while keeping today's constraints
implicit (small footprint, existing dependencies). This revision removes those constraints
entirely, per explicit instruction: **model size, APK size, number of dependencies, and
number of bundled models are not limiting factors — the only goal is making voice recognition
as robust as it can genuinely be made on-device.** Every component below is chosen as the
strongest available real option, not the smallest or simplest. Where the honestly best option
is still simple (e.g. the state machine shape from the first draft), it's kept, because
simplicity there is a robustness property, not a size compromise — a state machine with fewer
states has fewer ways to be wrong, independent of how big the models feeding it are.

Researched externally against current (2025) literature and available pretrained models before
writing this revision — citations to what was checked are inline below, not just claimed.

## Why we are throwing away the old design, not patching it again

Six rounds of fixes this session (v8.30.0 → v9.3.1) each fixed a real bug in the enrollment
state machine — floating-point boundary bugs, index-corruption on retry, outlier-blame bugs,
calibration thresholds too strict for real speech, a Bluetooth quality regression, and finally
two more state-machine misrouting bugs (stale `enrollmentOverflow`, size-inferred spare-group
routing). Every fix was correct and verified. And it is *still* not reliable on your device.

That pattern — a steady stream of distinct, real bugs in the same 150 lines of code — is not
bad luck. It is what happens when a system's *complexity is fighting its own purpose*. The
current design has, simultaneously:

- **Two independent signal types** (a hand-rolled log-mel DTW sound-pattern matcher, and a
  separate Vosk 128-dim speaker x-vector), each with its own threshold, its own group-splitting
  (normal/quiet), its own calibration math, and its own accept/reject rule — combined with
  "either/or" logic that was never derived from a single coherent decision rule.
- **Two independent runtime wake-detection code paths** (an ASR-transcript+speaker-embedding
  path through Vosk's recognizer, and a *separate* raw-audio-clip DTW path via a manually
  attached `setClipListener`) that must be kept in sync by hand (this is exactly how the
  Bluetooth-quality regression happened — a second path silently bypassed a safeguard the first
  path already had).
- **A 14-step index-driven state machine** (5 normal + 5 quiet enrollment, 2+2 held-out
  verification, plus up to 2 "spare" recovery slots layered on top) where almost every field
  (`wakeSampleIndex`, `enrollmentOverflow`, `spareTakeIsQuiet`, `candidateNormal`,
  `candidateQuiet`, four different sample lists) has to stay in exact lockstep or training
  silently corrupts itself in a way that *looks* like calibration failure from the outside.
- **No persisted checkpoint of any of it** — a process death loses everything.

Patching this again would be round 7 of the same pattern. Instead: replace it with a design
that has **one signal, one collection phase, no recovery sub-state-machine, and a runtime
decision path that shares code with the training/calibration path** so they can never drift
apart again — and now, additionally, upgrade every stage of that one signal path to the
strongest real component available, since nothing is off the table.

## What "training a voice" actually needs to do (first principles)

Forget the current implementation for a moment. A wake-training system that must recognize
*only one specific person's voice*, on-device, with no cloud and no labeled dataset beyond
what that one person records, has exactly three jobs:

1. **Turn a recording into a fixed-size numeric fingerprint** that captures "voice
   characteristics" (who is speaking) largely independent of the exact words and background
   noise — this is speaker embedding extraction.
2. **Separate the target voice from everything else** — this has two distinct parts that the
   old design conflated:
   - **Separating speech from background/silence** (a signal-processing problem: voice
     activity detection / noise-floor gating) — this must happen BEFORE embedding extraction,
     not as a parallel acoustic-pattern matcher.
   - **Separating the owner's voice from other people's voices** (a pattern-recognition
     problem over the embedding space) — this is a similarity threshold over embeddings, and
     nothing else.
3. **Decide, from a handful of enrollment recordings, a reliable "is this the same person"
   boundary** that tolerates the enrolled person's own natural variation (loud/quiet, fast/slow,
   good day/bad day) while still rejecting other people — this is the calibration problem, and
   it should be solved ONCE, with ONE statistic, not layered ad-hoc percentile/ceiling/overflow
   logic on top of a second independent matcher.

Everything else in the current system — the DTW sound-pattern matcher, the normal/quiet group
split, the spare-take recovery, the `enrollmentOverflow` counter — is *accumulated machinery*
that grew up around problems that a cleaner design doesn't have in the first place.

## The core design decision: ONE signal, not two

**Drop the custom DTW log-mel sound-pattern matcher entirely.** Replace the single signal it
was bolted onto with the strongest available speaker-embedding pipeline, built from
purpose-built, heavily-validated components instead of hand-rolled DSP — this is the single
biggest robustness upgrade available, and now nothing stops us from taking it.

Why dropping DTW is correct, not a downgrade:
- The DTW matcher was originally added (per `VOICE-ENGINE-UPGRADE.md`/`SPEAKER-VERIFICATION.md`
  history) as a *secondary* check layered on top of the speaker embedding "for extra safety."
  It was never the primary signal — the speaker x-vector always was.
  It became load-bearing by accident, not by design, as fixes accumulated around it.
- A DTW matcher over raw log-mel frames is *inherently* pace/pitch/volume-sensitive — that's
  the entire reason Rounds 4-5 had to loosen its duration-ratio gate, split it into normal/quiet
  groups, and raise its ceiling three times. Those weren't bugs in the DTW matcher; they were
  DTW being asked to do a job (robust identity matching across natural human variation) that a
  learned speaker embedding is *specifically designed* for and DTW is not.
- Removing it also removes the entire "normal vs. quiet volume calibrated separately" concept
  from the identity check — that split existed ONLY because the DTW matcher's absolute energy
  levels differ between loud and quiet speech. A learned speaker embedding is far less
  volume-sensitive by design.

### Upgraded speaker embedding model: dedicated ECAPA-TDNN, not just Vosk's bundled vector

Vosk's bundled `vosk-model-spk-0.4` speaker vector is a reasonable x-vector, but it's a small,
older, general-purpose model shipped as one piece of a speech-recognition toolkit, not a
model chosen and tuned specifically for speaker verification. Since size and dependency count
no longer matter, use a dedicated, current, published state-of-the-art model instead:

- **ECAPA-TDNN** (Emphasized Channel Attention, Propagation and Aggregation in TDNN) is
  confirmed as one of the two best-performing publicly available architectures for speaker
  verification in current comparative research — an MDPI 2024 benchmark
  (mdpi.com/2076-3417/14/4/1329) measured ECAPA-TDNN at 1.71% Equal Error Rate and TitaNet at
  1.91% EER, both clearly ahead of older x-vector approaches. A 2025 Interspeech robustness
  study (isca-archive.org/interspeech_2025/ferrofilho25_interspeech.pdf) comparing ECAPA-TDNN,
  TitaNet, ECAPA2 and ReDimNet under real domain shift (far-field speech, background noise,
  codec compression — exactly IRIS's real operating conditions) found ReDimNet degraded least,
  with ECAPA-TDNN/ECAPA2 close behind, and ALL of them meaningfully better than legacy x-vector
  systems.
- **Recommended primary model: ECAPA-TDNN**, via a pretrained, published checkpoint (e.g. the
  SpeechBrain `spkrec-ecapa-voxceleb` release, trained on VoxCeleb1/2 — thousands of real
  speakers across real recording conditions), exported to ONNX and run via ONNX Runtime Mobile
  (or converted to TFLite) for on-device inference. This is a straightforward, well-documented
  export path with no cloud dependency at inference time — the model is downloaded once (at
  build time or first run) and runs fully offline afterward, consistent with this project's
  standing offline-first requirement.
- **Secondary/fallback model: TitaNet** (NVIDIA NeMo) — nearly identical accuracy to
  ECAPA-TDNN in the same benchmark. Not required for v1 of this redesign, but worth keeping as
  a documented alternative if the ECAPA-TDNN ONNX export proves harder to integrate than
  expected on real hardware during implementation.
- **This replaces `VoskEngine.extractSpk()`'s embedding source, not `VoskEngine` itself.**
  Vosk continues to do wake-phrase ASR (grammar-constrained recognition, unchanged) and can
  keep producing its own x-vector as a free-riding secondary signal (see Ensemble section
  below) — but the PRIMARY identity decision now comes from the dedicated ECAPA-TDNN model,
  which is measurably more accurate and more robust to exactly the domain shifts (background
  noise, far-field/phone-mic distance, compression) that a real phone microphone in a real
  room produces.

### Upgraded voice-activity detection: Silero VAD, not a hand-rolled energy gate

The old `SoundPattern.extract()`'s speech-boundary logic (sort frame energies, take the 10th
percentile as a noise floor, trim below a multiplier of it) is a reasonable simple heuristic,
but it is exactly the kind of hand-tuned DSP that this session's bug history shows breaks down
under real, varied conditions. Replace it with **Silero VAD**
(github.com/snakers4/silero-vad) — a small, pretrained, enterprise-grade neural voice-activity
detector, MIT-licensed, ONNX-exportable, specifically validated across "over 100 languages...
different domains with various background noise and quality levels"
(huggingface.co/tphakala/silero-vad). It emits a genuine per-window speech probability instead
of a fixed energy-percentile heuristic, so it correctly handles cases the old heuristic
struggled with by construction: quiet rooms, noisy rooms, a whisper against a loud background,
a car engine, wind noise. It never transcribes anything — it only answers "is this a voice" —
so it introduces no additional privacy surface.

Silero VAD runs on every take (training AND runtime wake detection) before the embedding model
ever sees the audio: it finds the actual speech boundaries, the clip is trimmed to just that
window, THEN the ECAPA-TDNN embedding is extracted from the trimmed, speech-only audio. This is
a strictly more robust "separate speech from background" step than the old energy-floor gate,
using a purpose-trained model instead of a hand-picked energy multiplier — and, deliberately,
it is still just a gating/trimming step, not a second identity signal, so it does not
reintroduce the two-independent-matchers problem the rest of this redesign eliminates.

This means: **one embedding per take** (from ECAPA-TDNN, on Silero-VAD-trimmed audio), **one
group of embeddings, one calibration statistic, one threshold, one accept/reject decision** as
the core rule — with an optional, clearly-separated ensemble layer on top for extra robustness
(next section), never a second competing primary signal.

## Maximizing robustness further: ensemble scoring and liveness/anti-spoofing

Since robustness is now the only goal, two additional layers are worth adding — deliberately
designed so neither reintroduces the old two-competing-signal problem, because both are
explicitly SECONDARY/confirmatory signals with one clear primary decision, not two independent
gates that both must be separately tuned and kept in sync.

### Ensemble embedding scoring (confirmatory, not competing)

Run BOTH the dedicated ECAPA-TDNN embedding AND Vosk's own bundled x-vector (already extracted
for free as part of running Vosk's ASR — `extractSpk()` already exists and costs nothing extra
to call) on every take, and combine them as a **weighted average of the two cosine similarity
scores** against their respective enrolled centroids, not an "either accepts" OR gate:

```
finalScore = 0.8 * cosine(ecapaEmbedding, ecapaCentroid) + 0.2 * cosine(voskEmbedding, voskCentroid)
accept if finalScore >= threshold
```

This is fundamentally different from the old design's "either group accepts" logic in one
critical way: the old design let EITHER of two independently-thresholded signals pass on its
own (an OR gate, which only ever makes false-acceptance MORE likely, since either signal alone
is sufficient to admit). A weighted-average ensemble is the opposite: both models must broadly
agree, and a single model's blind spot is *smoothed by* the other rather than being a second
independent way to get in. This is a standard, well-understood technique for improving speaker
verification robustness (score-level fusion), not a repeat of the old design's structural
mistake — the two most similar things about them are surface-level (two models), not
structural (fusion vs. OR-gating are opposite risk directions).

Because the Vosk x-vector is already being computed as a side effect of Vosk's own ASR
pass in the unified single-path detection flow (see Runtime section below), this ensemble adds
**zero additional runtime cost** at wake-detection time — it is pure upside once the dedicated
ECAPA-TDNN model is also in place.

### Liveness / replay-attack resistance (optional hardening layer, honestly scoped)

Research on this is real and current (e.g. arxiv.org/html/2509.14789v1 on multi-channel replay
detection, mdpi.com/2078-2489/14/1/7 on high-frequency-spectrum-loss detection for replayed
audio), but virtually all of the strong published results depend on multi-channel/beamforming
microphone arrays or large dedicated anti-spoofing training sets — neither of which a
single-phone-mic personal assistant genuinely has access to. Per this project's own standing
`AGENTS.md` contract ("do not claim... replay resistance... without real device-test
evidence"), this redesign does NOT claim replay resistance as a delivered property.

What IS realistic and worth adding as a genuine, if modest, hardening layer:
- **High-frequency spectral-energy check** (mdpi.com/2078-2489/14/1/7's core finding): played-
  back audio through a phone speaker measurably loses very-high-frequency content compared to
  live speech, because consumer speakers and the playback-then-recapture round trip both act as
  low-pass filters. A simple, cheap check — comparing the fraction of spectral energy above
  ~7kHz between the captured clip and what's typical for live speech — can raise (not prove)
  suspicion of a replayed recording. This is implemented as an additional score contributing a
  small penalty to `finalScore` above, not a hard gate, specifically so it cannot ever be the
  single reason wake fails to fire on a genuine quiet/muffled live utterance (a hard gate here
  would risk exactly the kind of "training broken again" false-negative history this whole
  redesign is trying to end).
- This is explicitly labeled in code and in the eventual `IRIS-FEATURES.html` entry as "replay
  hardening," never "replay-proof" or "anti-spoofing" — consistent with not overclaiming a
  property that hasn't been validated on real attack attempts on your actual device.

## New enrollment flow (state machine, not state soup)

### Step count: 6 total takes, no groups, no spares

- **4 enrollment takes** — used to build the owner's voiceprint (centroid).
- **2 held-out verification takes** — never touch the centroid; must independently match it.

This drops from 14 steps (5+5+2+2, plus up to 2 silent spares) to **6 steps, always exactly 6,
no exceptions**. There is no "spare take" concept at all, because there's no group-specific
calibration failure mode left to recover from (see below) — the one remaining calibration
check is evaluated once, over one flat list, with a single well-tested "outlier-tolerant
centroid" algorithm that already exists and is proven in this codebase.

Why 4+2 instead of the old 5+5+2+2:
- The old 5+5 split existed only to give the DTW matcher separate normal/quiet statistics.
  With no DTW matcher, there is no reason to require the user to deliberately whisper during
  enrollment — normal speaking voice, said 4 times, is enough for a stable speaker-embedding
  centroid (this matches the "5-10 voice samples" already recommended in this project's own
  `SPEAKER-VERIFICATION.md` design notes for embedding-only enrollment).
- 2 held-out verification takes (down from 4) still gives a genuine "does this actually work"
  gate before saving, without asking for 6 confirmatory takes on top of 4 enrollment takes.
- If you want quiet-voice wake to keep working (saying the wake phrase softly, e.g. at night),
  that's a **runtime sensitivity setting**, not a **training-time data-collection split** — see
  "Quiet speech" below. This is a deliberate, better separation of concerns: how variable your
  voice is allowed to be at wake-time is a policy knob, not something you should have to record
  extra training data to support.

### Per-take pipeline (replaces `captureNextWakeSample()`'s branching)

```
record 3s PCM (TimedRecorder, unchanged)
    → SileroVad.trim(pcm)                   [neural VAD-based trim; reject if no usable speech found]
    → EcapaEmbedding.extract(trimmed)       [PRIMARY: dedicated ECAPA-TDNN, new]
    → VoskEngine.embed(trimmed)             [SECONDARY: existing embed() path, unchanged, free side-effect of ASR]
    → reject if either embedding invalid (wrong dim, NaN, zero norm — WakePolicy already checks this)
    → append to the ONE pair of lists: ecapaTakes.add(ecapaEmbedding); voskTakes.add(voskEmbedding)
    → advance takeIndex
```

There is no branching on "is this a spare," "is this quiet," "which group is this." There are
exactly two parallel lists during enrollment (`ecapaTakes`, `voskTakes` — one pair per take,
not two independently-thresholded groups; see Ensemble section above for why this is different
in kind from the old normal/quiet split), and a second pair during verification (`ecapaHeldOut`,
`voskHeldOut`). `takeIndex` is a simple counter from 0 to 5 (4 enrollment + 2 verification), and
`identityTake` is simply `takeIndex < 4`. That single line replaces the current
`spareTake`/`identityTake`/`quietTake` three-way branch entirely — because there is nothing
left to branch on.

### Calibration: one statistic per model, evaluated once, at the natural end of enrollment

After take 4, run the **existing, proven** `WakePolicy.enrollment(takes)` algorithm — the
majority-agreement centroid builder already in this codebase (kept, unmodified in its logic) —
**twice, independently, once per embedding list**: `ecapaCentroid =
WakePolicy.enrollment(ecapaTakes)`, `voskCentroid = WakePolicy.enrollment(voskTakes)`. Each
sample is scored against the others in its OWN list; samples that agree with at least half
their peers are kept; the mean of the survivors (normalized) becomes that model's centroid. It
already tolerates 1-2 inconsistent takes without failing outright. This is the ONLY calibration
step, run twice because there are now two embedding sources, not because there are two
competing decision paths — both centroids feed the SAME single weighted-score decision rule
described in the Ensemble section, never an independent accept/reject each. There is no
separate "does this batch calibrate" check, no ceiling, no percentile, no spare-take recovery
loop — because `WakePolicy.enrollment()` already IS an outlier-tolerant aggregator; the old
design's DTW-side `SoundPattern.calibrate()` was solving a problem (outlier robustness) that
the voice side had already solved correctly for embeddings, just not for sound patterns,
because sound patterns are being deleted.

**Failure mode, simplified to one case**: calibration fails if EITHER `WakePolicy.enrollment()`
call returns `null` (fewer than 3 of the 4 takes agree with each other in that model's
embedding space). There's only one way to recover: **retry the whole 4-take enrollment batch**
(not a single "spare" slot) with a clear message: "Your 4 recordings didn't agree closely
enough with each other. Let's record all 4 again — try to say it the same natural way each
time." This is a strictly simpler, more honest recovery model than the old spare-take
machinery: no index arithmetic, no group tracking, no overflow counter, nothing to
desynchronize. A full-batch retry of 4 short recordings is not a meaningfully worse user
experience than the old system's opaque "one more take" loop that could fire twice and still
fail confusingly.

### Verification: unchanged in spirit, trivial in code, now ensemble-scored

Each of the 2 held-out takes is checked with the same weighted-average `finalScore` formula
from the Ensemble section: `0.8 * cosine(ecapaEmbedding, ecapaCentroid) + 0.2 *
cosine(voskEmbedding, voskCentroid) >= threshold`. If either take fails, retry that single take
(not the whole batch — a verification take is disposable, unlike an enrollment take, since it
never touches either centroid). After both pass, save both centroids into the profile.

### What this eliminates, concretely (every bug class from this session)

| Bug class (this session) | Why it cannot recur |
|---|---|
| Floating-point `.85` boundary bug | No pattern-matcher energy thresholds left; single cosine-similarity threshold, already epsilon-safe in `WakePolicy` |
| Save-flow data loss | Unchanged — `SAVE_FAILED` stage kept as-is (UI/stage layer, not touched) |
| Index-corruption on retry (round 3) | No index-based removal anywhere; `WakePolicy.enrollment()` filters by value, not position — already true today, now the ONLY filtering logic in the system |
| Calibration ceiling too strict (round 4) | No separate calibration ceiling exists; `WakePolicy.enrollment()`'s "agree with half of peers" rule is the sole robustness check, and was never the thing that was too strict |
| Bluetooth quality regression (round 5) | Independent of this redesign; already fixed; unaffected by removing DTW |
| Percentile-masking / duration-gate looseness (round 5) | The entire `SoundPattern` class (DTW, calibrate, rankByConsistency, bestSubset) is deleted — there is no percentile statistic or duration gate left to tune |
| Spare-group misrouting (round 6, claim 1) | No spare-take concept; nothing to misroute |
| Stale `enrollmentOverflow` (round 6, claim 2) | No overflow counter; nothing to go stale |
| Premature verification / corrupted candidates (round 6, claim 3) | Both centroids are computed exactly once, from exactly one pair of lists, right after take 4; there is no code path that recomputes them during verification |
| No persistent progress (round 6, claim 4) | Addressed directly below — a genuinely simpler state to persist, since there's one list instead of four |

## Runtime wake detection: unify the two paths into one, now scored as an ensemble

Today, `VoskEngine.startWakeDetection()` runs **two parallel listeners** on the same audio:
Vosk's ASR recognizer (grammar-constrained to the wake phrase, gated on word confidence +
`spk_frames` + phrase match) AND a separate `setClipListener` raw-PCM callback that runs the
DTW sound-pattern check and a second, independent `embed()` call. Both can independently decide
"wake detected." This dual-path design is itself risk: it's exactly the shape of bug that let
the Bluetooth-quality regression happen (one path got a safeguard, the other didn't), and it
means the *training* pipeline and the *runtime* pipeline extract embeddings through two
different call sites that must be kept consistent by hand.

**New design: one path, two embeddings feeding one ensemble score.**

```
Vosk ASR recognizer (grammar = wake phrase only, unchanged)
    → phrase matched + word confidence + duration gate (existing checks, unchanged)
    → spk_frames gate (existing, unchanged — confirms enough voiced audio was seen)
    → SileroVad.trim(rawClip) + EcapaEmbedding.extract(trimmed)   [PRIMARY, new]
    → extractSpk(json)                                            [SECONDARY, existing, free]
    → finalScore = 0.8*cosine(ecapa,ecapaCentroid) + 0.2*cosine(vosk,voskCentroid)
                   [minus a small penalty from the optional replay-hardening spectral check]
    → wake fires if finalScore >= threshold(sensitivity), else silently rearms
```

The `setClipListener`/DTW path is deleted from `VoskEngine.startWakeDetection()` entirely —
there is only ONE audio callback path now, which both extracts the ensemble embeddings and
makes the single ensemble decision, rather than two callbacks each independently deciding.
`AudioRouteController`'s `allowBluetooth` fix from round 5 is unaffected — it still applies to
the single remaining recognizer-based path, which was already the fixed one; only the
redundant clip-listener path (which never had that fix and can't regress it) is removed.

This is a strict robustness upgrade with no structural regression risk: the speaker embedding
gate is preserved and strengthened (dedicated ECAPA-TDNN model + Silero VAD trimming, ensemble-
scored with Vosk's own vector), and the old DTW approximation of the same idea — the actual
source of most of this session's bugs — is gone.

## Quiet speech at wake-time: a sensitivity setting, not a training split

The old design demanded the user deliberately record 5 *quiet* takes during training so a
separate quiet-calibrated DTW group could recognize soft speech later. Replace this with what
this project already has a mechanism for: **`WakePolicy.threshold(sensitivity)`** already
scales the acceptance threshold from 0.85 (strict) down to 0.65 (lenient) based on the existing
`voiceSensitivity()` setting. A single embedding-based centroid, matched at a slightly relaxed
threshold, already accepts quieter/softer speech from the *same* enrolled person, because
learned speaker embeddings (both ECAPA-TDNN and Vosk's x-vector) are far less volume-dependent
than a raw energy-pattern matcher was — this is exactly the property that made choosing an
embedding-only design correct in the first place, and the dedicated ECAPA-TDNN model (trained
on thousands of real speakers across real recording conditions, per the VoxCeleb training set
it ships with) is, if anything, even more robust to volume variation than Vosk's smaller
bundled vector was on its own.

No new code is needed here beyond what already exists; the training flow simply stops asking
for quiet-voice takes, and the existing sensitivity setting continues to do the same job it
already does today for the embedding side of the old dual check — now scaling BOTH models'
combined ensemble threshold, in one place.

## Persisting in-progress training (claim 4, now actually tractable)

The old design would have needed to persist 4 separate lists (`soundNormalSamples`,
`soundQuietSamples`, plus their voice-embedding counterparts) plus `enrollmentOverflow` plus
`sessionRoute` — real complexity, which is why it was correctly flagged as its own scoped
decision last round rather than rushed.

The new design needs to persist exactly: `takeIndex` (int), `ecapaTakes`/`voskTakes` (two
`List<float[]>`, up to 4 embeddings each), `ecapaHeldOut`/`voskHeldOut` (two `List<float[]>`,
up to 2 each), `sessionRoute` (existing enum), and the wake phrase text. That's still a small,
flat, fixed-shape state — two pairs of lists instead of one pair of matching lists, but nowhere
near the old four-independently-sized-and-indexed lists plus an overflow counter that could
desynchronize.

Design: extend the existing (currently-disconnected) `TrainingProgress.java` class with a new
schema version specifically for this flow — `save(context, takeIndex, ecapaTakes, voskTakes,
ecapaHeldOut, voskHeldOut, sessionRoute, phrase)` called after every successfully accepted take
(not on every keystroke — only on state transitions, so this is a handful of small writes per
session, each under 5KB: 4×2×128 floats + 2×2×128 floats + a few ints and enums). Written the
same atomic-write way the class already does for its legacy schema (temp file + rename).
Restored in `beginWakeTraining()`: if a valid in-progress save exists for the *same* wake
phrase and *same* route, offer to resume ("Continue your in-progress voice training? 3 of 6
takes recorded.") instead of silently starting over; `TrainingProgress.clear()` (already called
on cancel/finish) removes it.

This is real disk persistence of biometric-adjacent data (embeddings, not raw audio — the
embeddings are already one-way per the existing privacy design in `SPEAKER-VERIFICATION.md`,
so this is not raw voice data at rest, similar in kind to the profile embeddings already stored
encrypted via `ProfileStore`/`SecureStore`). It should go through **the same encrypted-storage
path** already used for the saved profile (`SecureStore`'s AES-256-GCM), not a new plaintext
file — this is a hard requirement, not a nice-to-have, consistent with `AGENTS.md`'s standing
contract on audio-processing/identity data.

## What stays completely unchanged

- **UI**: `view_training.xml`, `TrainingStepDots`, the wizard state views, `OwnerTrainingStage`
  (the stage-machine enum/title/message logic) — none of this is wake-detection logic, all of
  it is presentation. Per your instruction, none of it is touched.
- **`TimedRecorder`** — fixed-duration recording capture, unrelated to the matching algorithm.
- **`WakePolicy.owner()`, `WakePolicy.enrollment()`, `WakePolicy.cosine()`,
  `WakePolicy.threshold()`** — all already correct, already the RIGHT abstraction, just
  previously only half of a two-signal system, and now called twice (once per embedding
  model) instead of once, with their outputs combined by the new ensemble scorer rather than
  each independently gating.
- **`VoskEngine.embed()` / `extractSpk()`** — unchanged; kept as the SECONDARY signal in the
  ensemble; same extraction call site used by both training and runtime detection (this
  sharing is itself part of the fix, not new code).
- **`AudioRouteController`'s `allowBluetooth` fix, headset dual-route design** — unaffected;
  applies identically to the single remaining detection path.
- **Save flow (`SAVE_FAILED` stage, floating point epsilon handling)** — unaffected.
- **Backgrounding pause/resume (`onStop()`/`onStart()` logic)** — unaffected; actually becomes
  more robust once combined with real disk persistence (a killed process now also resumes, not
  just a paused one).

## What gets deleted

- `SoundPattern.java` in its entirety (`extract`, `calibrate`, `rankByConsistency`,
  `bestSubset`, `distance`, `score`, the FFT/mel-filterbank code) — replaced by Silero VAD
  (a pretrained model, not hand-rolled DSP) for the speech-boundary/trim step, wrapped in a
  small `SileroVad.trim(short[] pcm)` utility (loads the ONNX model, runs it, no feature
  extraction, no DTW, no distance metric — see Model Integration section below).
- `SoundWakeProfile.java` — the split normal/quiet examples/threshold schema. Deleted; the
  owner profile schema drops its `sound`/`headset.sound` fields entirely (schema bump, see
  below), replaced by `ecapaCentroid`/`voskCentroid` fields (see Model Integration section).
- The `soundNormalSamples`/`soundQuietSamples`/`soundValidation`/`enrollmentOverflow`/
  `spareTakeIsQuiet` fields and the entire enrollment-boundary block in `MainActivity.java`
  (~150 lines) — replaced by the ~20-line dual-embedding pipeline described above.
- `HeadsetTrainingPlan.java`'s sound-pattern-specific comments/fields (the headset route's
  *embedding* enrollment is kept — a phone-mic and a headset-mic profile are still trained
  separately, that requirement is untouched; only the sound-pattern half of each is removed).
- The "Sound calibration diagnostics" tool from `MainActivity.installOwnerTools()` — replaced
  by a "Voice enrollment diagnostics" tool showing the real leave-one-out embedding agreement
  scores from `WakePolicy.enrollment()`'s own logic FOR BOTH models plus the combined ensemble
  score and the replay-hardening penalty, so a failure is fully explainable from the screen
  rather than requiring log-reading.

## Model integration plan (the part that didn't exist when size/dependencies were constrained)

Three new offline models/components, all downloaded once (build time or first run, matching
this project's existing Vosk model-bundling precedent) and run 100% on-device thereafter —
none of them call any network API at inference time, preserving the offline-first requirement:

| Component | Role | Format | Size (approx, informational only — not a constraint) |
|---|---|---|---|
| ECAPA-TDNN (SpeechBrain `spkrec-ecapa-voxceleb`) | Primary speaker embedding | ONNX, via ONNX Runtime Mobile | ~20 MB |
| Silero VAD | Speech/silence boundary trimming | ONNX, via ONNX Runtime Mobile | ~1-2 MB |
| Vosk `vosk-model-spk-0.4` | Secondary speaker embedding (ensemble) | already bundled, unchanged | already in APK |

Both new models share ONNX Runtime Mobile as the inference engine (`com.microsoft.onnxruntime:
onnxruntime-android`), so this is ONE new native dependency, not two — both the embedding model
and the VAD model run through it. New wrapper classes, mirroring `VoskEngine`'s existing
load/close lifecycle pattern:

- `EcapaEmbedding.java` — `load(Context)`, `extract(short[] pcm) → float[192]` (ECAPA-TDNN's
  native output dimension; NOT forced to match Vosk's 128 — the two embedding spaces are
  intentionally independent, each compared only against its own centroid, never against each
  other, which is why the Ensemble section's fusion is a weighted SUM of two cosine scores, not
  a shared vector space), `close()`.
- `SileroVad.java` — `load(Context)`, `trim(short[] pcm) → short[]` (returns the speech-only
  sub-clip, or the original array unchanged if the model finds no clear boundary to trim,
  falling back gracefully rather than corrupting the clip), `close()`.

`OwnerVoiceProfile`'s schema gains `ecapaCentroid: float[192]` and `voskCentroid: float[128]`
(replacing the deleted `sound`/`headset.sound` fields), each independently length-and-NaN
validated exactly the way `WakePolicy.owner()` already validates `EMBED_DIM`-sized vectors
today — extended to also validate the new 192-dim ECAPA vectors via a new `ECAPA_EMBED_DIM=192`
constant alongside the existing `EMBED_DIM=128`.

## Schema impact (breaking change, by design — same as round 5's precedent)

`OwnerVoiceProfile` schema bumps again. Old profiles (with `sound`/`headset.sound` fields) are
rejected, not silently downgraded — consistent with the existing "no silent fallback" contract
in `AGENTS.md`. Every user, including you, retrains once after this ships. Given training has
not reliably completed anyway across six rounds, this is not a meaningful new cost.

## Testing plan (what "done" means before this ships)

1. **Unit tests** (JUnit, offline, no Android dependency) for the new/kept pieces:
   - `WakePolicy.enrollment()` / `owner()` / `threshold()` — already tested, re-verify
     unchanged, plus new tests for the ensemble `finalScore` weighting function itself
     (pure math, no model inference needed to unit-test the fusion formula).
   - New `TrainingProgress` schema — save/load round-trip, corruption handling, stale-session
     detection (different phrase/route than what's saved), now covering two embedding-list
     pairs instead of one.
2. **Model integration smoke tests** (need an actual device/emulator with the ONNX models
   bundled, cannot be pure-JVM offline tests like the rest of this project's suites):
   - ECAPA-TDNN and Silero VAD both load successfully and produce correctly-shaped,
     finite-valued output on a real recorded clip.
   - Silero VAD's trim boundaries look sane on a clip with obvious leading/trailing silence
     (manually inspect a few real clips' trim points during implementation).
3. **Empirical embedding-volume-robustness check** (the specific claim this design leans on
   for dropping the quiet-take split): record real normal-volume and real quiet/soft-voice
   samples from the same person, confirm the ensemble score at a relaxed sensitivity accepts
   both against one single-volume-trained centroid pair. This is the one genuinely new
   empirical claim in this design and must be checked with real recordings, not assumed —
   now doubly important since it's being asked of two models instead of one.
4. **Full regression suite** — same as every prior round: `ParseSources`, offline suites,
   layout suites (`tests/check-training-layout.py` — re-verify its "alt-phrase UI stays hidden"
   assertions still hold, since the training flow's step count changes from 14 to 6), JUnit.
5. **One real on-device enrollment + wake test**, same as always — this design should make that
   test far more likely to succeed on the first try, since there is an order of magnitude less
   state-machine complexity AND a materially stronger model doing the actual recognition, but
   it still needs to actually be tried on the device before declaring success.

## Implementation order (once you approve this design)

1. Add ONNX Runtime Mobile dependency; bundle/download the ECAPA-TDNN and Silero VAD ONNX
   models (matching the existing Vosk model-bundling approach in this project).
2. `SileroVad.java` (load + trim) and `EcapaEmbedding.java` (load + extract) — new classes,
   modeled on `VoskEngine`'s existing lifecycle pattern.
3. `OwnerVoiceProfile` schema bump: drop `sound`/`headset.sound`, add `ecapaCentroid`/
   `voskCentroid`; add `ECAPA_EMBED_DIM` constant to `WakePolicy`.
4. `OwnerTrainingPlan` constants updated (`ENROLLMENT=4, VERIFY=2, TOTAL=6`) — renamed to drop
   "normal/quiet" terminology entirely, since there are no more volume-based groups.
5. `MainActivity`'s enrollment-boundary block rewritten to the dual-embedding pipeline above
   (net deletion of code despite adding a second embedding model, since the spare-take/
   overflow/group-routing machinery is what's being removed).
6. `VoskEngine.startWakeDetection()`'s `setClipListener`/DTW path removed; single-path
   detection wired to the new ensemble `finalScore` function.
7. `TrainingProgress` new schema (two embedding-list pairs) + wiring into
   `beginWakeTraining()`/per-take save/`cancelWakeTraining()`/`finishWakeTraining()`, routed
   through `SecureStore` encryption.
8. Diagnostics tool rewritten against the new dual-model data, showing both models' individual
   scores plus the combined ensemble score.
9. (Optional, per the honestly-scoped hardening section) high-frequency spectral-energy penalty
   term added to `finalScore`, clearly labeled "replay hardening" everywhere it's surfaced.
10. Delete `SoundPattern.java`, `SoundWakeProfile.java`, and their tests; delete now-dead fields.
11. Full test suite + on-device verification per the Testing Plan above.
12. Version bump: this is a major architectural change to a core feature, now additionally
    introducing two new on-device ML models → **+1.0.0** per the project's own versioning
    convention (this is not a "small feature/bug fix," it is a ground-up rebuild of the
    training and wake-matching engine, explicitly requested as such).

## Open decisions for you before implementation starts

1. **4 enrollment + 2 verification (6 total)** is my recommended step count — confirm, or tell
   me a different split if you want more/fewer takes.
2. **Deleting the quiet-voice training requirement** in favor of a runtime sensitivity setting
   is the biggest behavioral change here — confirm you're fine asking for 4 normal-voice takes
   only, relying on sensitivity for quiet/soft wake later, rather than dedicated quiet training.
3. **Persisted training progress will be encrypted via `SecureStore`**, same as the saved
   profile — confirm that's the right bar (vs., e.g., not persisting at all and relying only on
   the existing background-pause behavior, which is simpler but doesn't survive a real process
   kill).
4. **Ensemble weighting (0.8 ECAPA-TDNN / 0.2 Vosk)** is a reasonable starting split favoring
   the stronger dedicated model — confirm, or tell me if you'd rather start at an equal 0.5/0.5
   weighting and adjust based on real device testing.
5. **The "replay hardening" spectral penalty** (item 9 in Implementation order) is the one
   piece here that is a genuinely open research area rather than a proven technique for this
   exact use case — confirm you want it included as a soft penalty from day one, or would
   rather ship the core redesign first and add this afterward once the core system is proven
   solid on your device.
6. Everyone with an existing trained profile must retrain once after this ships (unavoidable
   schema break, consistent with round 5's precedent) — confirming you accept that, same as you
   implicitly did for round 5.
