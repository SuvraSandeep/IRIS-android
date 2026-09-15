# IRIS 8.23.0 (316): visible, bounded owner training

Root cause found after 8.22.1: model preparation updated wakeTrainingStatus inside the hidden normal panel. The visible wizard still had XML defaults “Recording…” and “Step 1 of 3”. This made waiting for a model look like a frozen recording, before AudioRecord started. The earlier recorder-only fix did not cover this stage.

- Status is now outside both toggled panels, and all wizard text is rendered from the actual stage.
- Visible model-loading/microphone/recording/analysis stages, elapsed time, and bounded deadlines. Recording is shown only after PCM arrives.
- Dedicated owner-wizard handler; cancellation does not remove unrelated activity callbacks. Navigation cancels the current wizard before detaching its views.
- Model errors are shown explicitly. Missing speech callbacks time out; speaker failures no longer hide behind a recording label.
- 14 accepted takes: 5 normal, 5 quiet, 2 held-out normal, 2 held-out quiet. Verification samples do not train the centroids. Existing exact-phrase and owner thresholds remain intact.
- Manual Retry this take for rejected samples, clear cancellation, no misleading legacy resume promise.
- Includes the 8.22.1 non-blocking recorder/deadline and analysis-timeout repairs.

Validation includes the shipped XML visibility/default-state regression, actual TimedRecorder running against deterministic Android fakes (no-data, failed reads, failed init, blocked startup, cancellation), stage clocks and the sample plan, plus the existing regression suites. These tests do not replace headset/microphone testing on the owner's device.

Commit message: fix: render real training stages and expand verified owner enrollment
