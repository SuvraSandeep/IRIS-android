# IRIS 8.11.1 — lock-screen camera reliability

Based on main 22f1461 (merged Phase 5, version 8.11.0).

The existing manifest already enables `showWhenLocked` and `turnScreenOn` for IRIS's camera. Photo/video commands already use that activity without requiring unlock. This release fixes failures around that enabled path, rather than globally removing lock-screen protection.

- Open Camera2 only after the capture activity resumes, with a startup watchdog.
- Post a tappable camera notification before attempting an automatic launch. A resumed-activity acknowledgement removes it; unacknowledged requests expire after 60 seconds. No camera full-screen-call notification is used. Android can silently block a background launch even for a foreground service.
- `open camera` requests the system secure camera. `open camera and take a photo` routes to automatic IRIS capture.
- Choose supported JPEG/video sizes and the actual sensor orientation instead of fixed lens assumptions. Report a missing front/back lens rather than substituting another lens.
- Serialize capture cleanup with camera callbacks; close late sessions; stop/cancel on leaving the visible camera screen.
- Add a visible Stop and save button. Report elapsed video time when stopped early.
- Check output streams/descriptors, publish pending photo/video records only on success, and delete failed output. Do not announce successful saves when publication fails.

## Use

Grant Camera, Microphone and Notifications while unlocked, turn IRIS on, then lock the phone. Say `take a photo`, `take a selfie`, or `record back camera video for 30 seconds`. The screen lights up with the capture activity, but keyguard is not dismissed. If it does not appear automatically, tap the IRIS camera notification. Stop video on its screen or notification. Microphone listening is paused during capture, so use the Stop button rather than expecting a spoken stop while the recorder owns the mic.

Android 10+: pictures go to Pictures/IRIS and videos to Movies/IRIS. Older devices retain the app-specific IRIS folder behavior. Android may require unlock or consent for screenshots/screen recording, secure app content, and other app actions. This change cannot enable unrestricted access to everything while locked.

## Validation

Run the existing `bash IRIS-Android/scripts/test-speech.sh` regression/syntax suite. Physical device checks still required: rear/front photo, timed video, early Stop, immediate cancellation, camera-busy failure, revoked mic permission, notifications disabled, silently blocked launch, screen-off during recording, and output visibility/orientation. Android APK compilation remains unverified in this workspace because Gradle downloads are unavailable.

The previously rejected credential-bearing archive/profile-bundling workflows remain excluded. Source changes are published on a review branch; no merge/build is triggered.

Android reference: https://developer.android.com/guide/components/activities/background-starts
