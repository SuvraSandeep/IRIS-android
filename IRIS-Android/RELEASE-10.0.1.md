# IRIS 10.0.1 — recorded phrase and owner wake repair

Fixes successful training followed by a silent wake detector. Wake no longer depends on an exact ASR transcript: the typed phrase is a label. Four phrase examples plus four independent phrase-and-owner checks use the same matcher and fixed Vosk preprocessing as live listening. Both acoustic phrase and speaker identity must pass; missing evidence fails closed.

Schema 8 intentionally requires one fresh phone-microphone enrollment. Previous schema-7 profiles stored only speaker identity and cannot reconstruct a wake phrase. Add a separate headset profile afterwards. Background wake and manual testing honor the selected input when media is stopped. Bluetooth microphone use can require a communication audio route; wake remains paused during media playback.

Captures retain route metadata after recorder cleanup. Training enforces the intended route. Phone and headset checkpoints retain paired phrase/speaker evidence in encrypted storage, bound to profile revision and model fingerprint. Completed checkpoints go to authenticated review. Calibration replaces the least consistent phrase example and keeps the other three paired takes. Held-out examples never enter the enrollment bank. Feedback remains authenticated and revalidates every held-out take.

Saved-voice tests expose rejection reasons, select the actual route's profile, and restore previously running listening. Headset retry/save recovery stays in the headset flow. Refinement respects schema sample-count limits. Optional ECAPA/VAD downloads do not silently change the active model or preprocessing.

Validation: offline regression script, synthetic phrase/owner checks, recorder lifecycle tests with platform fakes, profile/persistence JUnit tests, Android build in GitHub Actions. No physical Android microphone test is claimed. Acoustic matching and speaker checks do not guarantee replay resistance or universal accuracy.

Device acceptance: train and save using Phone; test repeated phrases after 10+ seconds idle, wrong phrase in the owner's voice, different speaker, background/foreground transitions, app restart, interruption and resume, headset enrollment/test, microphone changes, playback pause, and return to listening after a manual test.
