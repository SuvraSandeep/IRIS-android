# Lag investigation and fix — 11.0.2

Inspected main db6fdf8 (11.0.0) and the unmerged PR20 stability patch. This revision extends PR20; it retains the native model lifetime/recovery fixes.

## Confirmed blocking paths

* SystemTelemetryController.fastTick runs on the main Handler every second. It called rebuild -> addIrisState -> ProfileStore.getWakeProfile -> SecureStore.read, then ownerEvidence -> a second encrypted file read and full JSON/profile copy. The profile includes acoustic examples and validation evidence. Slow collectors also executed on the main thread every four seconds.
* LogStore.append synchronously read, decrypted, retention-filtered, encrypted and atomically rewrote up to 600,000 characters per event. Callers include the main thread and the sequential voice worker. SecureStore's class-wide monitor serialized logs and all profile files together.
* Opening, clearing and exporting activity also performed storage work on the main thread.

These are real unbounded-latency main/audio-thread dependencies. They explain a mechanism for lag and delayed wake analysis, but no device trace was supplied to establish their measured contribution or diagnose every reported crash.

## Fix

Telemetry collection runs on one worker, coalesces overlapping refreshes, publishes on the main thread, discards stale lifecycle results and closes with its activity. Voice-profile display refresh is limited to the slower collection cycle. Readiness uses the already-read document and validated cache without cloning identity evidence.

Log writes use a 128-entry background queue and capped 4,000-character messages; overload drops best-effort diagnostics instead of blocking or accumulating unbounded memory. Reads and clear operations are ordered on the same worker; the UI and document export use background callers. Clear invalidates old displayed/filter results. Recent queued logs can be lost if Android kills the process; the separate fatal crash report remains synchronous.

SecureStore uses a per-file monitor, retaining same-file serialization, AtomicFile writes, AES-GCM and synchronized key creation. A log encryption/write no longer holds the profile's file monitor. No owner thresholds, authentication gates, enrollment evidence or model selection changed.

## Verification

Offline speech regression script passed. Added Android regression cases for blocked storage, saturated logging queues, independent file locks and telemetry returning while profile storage is blocked. APK/Android suite results are recorded in the PR after CI. These checks prove thread isolation, not a measured handset frame rate or first-call wake accuracy. No retraining is required.
