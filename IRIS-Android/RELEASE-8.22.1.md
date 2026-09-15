# IRIS 8.22.1 (315)

Fixes wake training appearing stuck at Recording.

The timed recorder previously used blocking reads and waited for a full sample count with no wall-clock/no-data deadline. The wizard also left its visible level label at Recording while offline validation ran.

- Non-blocking PCM reads; three-second no-data and duration-plus-three-second capture deadlines.
- Independent duration-plus-eight-second watchdog covers microphone setup/driver stalls.
- Single terminal callback per attempt; cancelled/late level and result callbacks cannot restore stale UI.
- Explicit Recording complete / Checking phrase stages; 30-second analysis timeout preserves the existing owner profile.
- Capture failure stops training with a readable error instead of an automatic retry loop.
- Exact phrase and owner verification thresholds are unchanged.

Validation: offline regression/deadline tests and Android CI. Real-device headset stall reproduction still requires the owner's device.
