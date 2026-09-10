# IRIS 8.11.3 — owner wake repair

Based on main 6328b1404e82652072bfb10eb268b756389d14bf.

- Mandatory local Vosk owner verification; unavailable identity/model data rejects.
- Removed Android wake fallback, last-word/fuzzy triggers and repeated-rejection bypass.
- Full phrase, minimum word confidence .85, duration .5–4 seconds and speaker-frame checks.
- Generation guard and one-shot callback; service enforces a three-second cooldown.
- Local media playback suppresses wake. Pause the video or use the Talk button.
- Loading/enrollment/media status appears in the listening notification.
- Minimum three consistent, quality-checked enrollment clips; complete-phrase transcription for wake samples.
- Test screen now uses the production Vosk wake and speaker policy.
- Miss feedback requests fresh authenticated enrollment, never blends rejected audio.
- Removed unused fail-open SpeakerVerifier stub. The existing Vosk speaker model is authoritative.
- Calls and messages respect require-unlock even with lock-screen camera enabled.
- Source, workflow and feature deck updated together.

## How to use

1. Open Training while unlocked; complete offline model setup once.
2. Use a distinctive phrase such as “Hello IRIS”. Record it in your natural Indian English accent. Leave a little silence before and after it. Avoid background video during enrollment.
3. Wait for successful voice enrollment. Use Test wake phrase; it verifies both phrase and owner.
4. Turn listening on. Say the complete phrase. During a video, pause playback first or use Talk.
5. If missed repeatedly, use fresh enrollment. Increasing strictness reduces false accepts but can reject more genuine attempts.

## Verification and limits

Automated regression tests: `bash tests/run-wake-tests.sh` (JDK 17).
CI runs these tests and builds the APK. No phone is attached to the development environment.
Thresholds are conservative starting values, not measured false-accept guarantees.
A recording of the owner's voice from another device may still pass speaker verification; there is no trained replay/liveness classifier. Sensitive actions must retain require-unlock.
The detector remains Vosk grammar recognition, not a newly trained dedicated keyword neural model. Training validates samples and enrolls identity; it does not retrain the speech model.

Device acceptance: test silence, household noise, TV dialogue, another speaker, owner near/far, media starting while armed, model unavailable, repeated rejects, and owner recordings. Measure false wakes per hour and genuine acceptance rate before relaxing thresholds. Do not claim zero false wakes without these recordings.
