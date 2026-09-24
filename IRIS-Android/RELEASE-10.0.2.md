# IRIS 10.0.2 — wake capture and headset verification recovery

Fixes intermittent wake capture and the difficult transition from four headset examples to independent verification. Version code 335.

- Training and live wake share bounded utterance capture: 300 ms pre-roll, the existing 1.2-second pause, and an 8-second maximum. Idle history and earlier phrases cannot pollute a new clip. Continuous sound cannot leave the endpoint waiting forever. Route changes clear old audio.
- Headset training explicitly requests a headset input, even when the general preference is Phone. Capture waits up to five seconds for a confirmed route to settle before indicating Listening. Phone training explicitly requests Phone. Microphone failures let the user retry with earlier takes retained.
- One wake analysis runs at a time, with the newest pending utterance retained. Earlier code silently dropped every retry while analysis was busy. Pending PCM is erased when replaced or stopped.
- After repeated phrase/speaker verification mismatches, the user may replace one inconsistent enrollment example and retain the other three. All held-out checks are cleared and four fresh checks are required; failed verification audio is never promoted into enrollment. Saving remains device-authenticated.
- Headset validation uses the same effective owner threshold as live listening. Training diagnostics show the active route's examples and phrase/speaker scores. Live rejection scores are available in Logs. Owner thresholds and sound calibration limits are unchanged.

Existing schema-8 (10.0.1) profiles remain compatible. Install the update, retry headset enrollment, wait for Listening before speaking, and use a natural, complete phrase followed by a pause. A very short or unclear phrase may not provide enough speaker evidence; the app now explains this rather than calling it a generic mismatch. Media playback still pauses wake.

Validation: offline regression tests cover capture after 20 seconds idle, differing audio read sizes, repeated phrases, continuous sound, route reset, recorder failure/cleanup, and a busy analysis queue. Android JUnit includes enrollment replacement and independent validation recovery. The Android APK build must pass before delivery. No physical-device test has been performed for this update; tests do not establish universal owner recognition or replay resistance.

Device acceptance: complete all eight headset takes, save, then try ten normal wake attempts using each trained input, including after 20 seconds idle and after a command. Check a different phrase and a different speaker are rejected. If an attempt fails, Logs → WAKE REJECTED records the input and both scores so the next correction can use measured evidence.

Source archive excludes signing keys; repository APK builds retain the existing signing configuration.
