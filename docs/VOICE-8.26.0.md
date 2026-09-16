# Voice profiles and controlled corrections — 8.26.0

Base main: 5eca9497bcd15dc513a198785e2385f1476a9694. This implements the usable Vosk-based stage of the voice roadmap. It does not claim that a new acoustic model was trained or that every roadmap experiment is completed.

## Available in Training
- Pause-aware capture with an 8-second cap, retaining leading audio and a conservative 1.2-second trailing pause. Recorder watchdogs remain. A shared microphone lease prevents overlapping application-owned recorders; an actual route change rejects the take.
- Independent, unconstrained raw and gain-adjusted transcription. Exact complete-phrase recognition remains required; explicit conflicting extra words reject a take. The last-take diagnostic includes both transcripts, duration and observed input. No raw recordings are saved.
- Versioned normal/soft enrollment samples and four held-out verification takes. Speaker model weights/configuration are fingerprinted. Invalid newer profiles cannot fall back silently to weaker legacy matching.
- Improve my voice profile: collect a fresh enrollment session, retain original anchors, validate old and new held-out takes, preserve confirmed negatives, authenticate to save. Banks remain bounded.
- Recent wake events: up to 12 events for two minutes in process memory. Phrase-gate failures carry reasons; accepted/rejected identity events carry ephemeral vectors. Missing/expired evidence requires a fresh recording.
- A confirmed different-speaker accepted event can propose a negative exclusion if all saved owner validation takes still pass. The user reviews and authenticates; thresholds are unchanged. Same-owner playback must not be labeled as another speaker. This bounded example exclusion is not a population-calibrated anti-spoofing system.
- Authenticated undo restores the previous owner profile, including legacy profiles with valid identity data.
- Separate encrypted owner export/import. AES-256-GCM, PBKDF2-HMAC-SHA256 with fixed 210,000 iterations, random salt/nonce, bounded package size. Passphrase is required on the receiving phone. Exports omit raw audio and other speakers' examples. General contacts export/import no longer claims to transfer owner identity.
- Imported profiles require matching model fingerprints, four fresh voice checks on the receiving device and authenticated atomic saving. Old profiles remain active until save succeeds.
- Spoken feedback phrases open review; manual Training controls remain available when a wake is missed. Android may restrict opening the UI from a background service, so the spoken response also directs the owner to Training.

## Tests and release limits
Local regression suites cover phrase equality, endpoint timing, recorder deadlines/cancellation, microphone leases and encryption tampering. Gradle tests use real JSON, real Vosk API signatures, malformed profiles, profile round-trip decisions and negative-update validation protections. Android compilation/build and lint are required before sharing an APK.

No physical-device/accent/audio corpus is available in this environment. Consequently, raw-vs-processed recognition improvement, latency, Bluetooth behavior, battery cost and false-accept rates require device qualification. Only the owner's actual audio can establish why a clearly spoken “Iris” is omitted. Partial words are still rejected rather than guessed. Speaker vectors do not guarantee rejection of replay or synthetic voices.

## Explicit remaining roadmap experiments
- Benchmark Sherpa customizable keyword spotting and independent speaker models against consented accent/route fixtures before selecting a replacement runtime. This release retains Vosk; it does not silently download a new inference runtime or unbenchmarked model.
- Detailed sample-quality and word-confidence telemetry, optional opt-in PCM replay, route-specific prototype calibration, longer text-independent sentence enrollment, and a full retained Activity-independent session state machine remain further work.
- Current negative corrections use a fixed conservative similarity exclusion checked against stored owner takes, not a learned population-calibrated margin. Playback reports are explanatory UI only; no anti-spoof model is trained from them.
- Real-device test matrix, long-running battery/thermal tests and measured end-to-end success/false-wake targets remain required. No numerical accuracy claim is made by this release.

## First-device acceptance
1. Install without clearing app data. Confirm old wake still works before any new training.
2. Train “Hello Iris” normally/softly. Check that the recorder reaches Speak/Recording only after actual frames arrive; inspect diagnostics on a rejected take.
3. Cancel a session and confirm old identity remains. Complete a session and authenticate. Try a missing-word phrase and a different speaker as negatives.
4. Export with a passphrase, import on a compatible install, verify four fresh takes and save. Wrong passwords/incompatible or damaged files must not replace identity.
5. Review a real mistaken wake promptly. Ensure the candidate refuses changes that would reject held-out owner evidence. Apply an eligible correction, then undo it.
6. Test phone/headset separately, media playback, app backgrounding, permission loss and reconnecting the headset mid-take.
