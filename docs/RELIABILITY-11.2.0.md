# IRIS 11.2.0

- Restore a spoken greeting for standalone owner-verified wakes when voice replies are enabled. Speech already buffered after the wake keeps the direct command handoff. The greeting pauses analysis to prevent self-transcription; wait for it to finish before giving a command. Buffered-speech detection is an energy heuristic and needs device testing in noise.
- Retain an accepted acoustic candidate when a later lower-distance candidate fails full phrase/negative checks. Identity thresholds, required models, route verification and revision guards are unchanged.
- Route charging questions before generic phone facts and planning. Read the current sticky battery broadcast; distinguish active charging, plugged-in but paused, fully charged and unknown state.
- Add “where are you”, “where are you IRIS” and “ring my phone” after wake. Play a 30-second alarm, request the built-in speaker, provide a Stop notification, and restore alarm volume unless changed by the user. Stop/service teardown and playback errors release resources.
- Settings → Find my phone explains alarm/DND setup. Silent mode uses the alarm stream. Android's total-silence mode and priority DND without alarms remain blockers; IRIS reports these instead of claiming playback. Android 15 apps cannot universally disable other DND rules. Speaker routing and audibility require device tests, especially with Bluetooth.
- Feedback shows the captured route, provides a fresh recording when the list is empty, prevents accepted wakes from being labelled missed, and consumes successfully applied evidence. Authentication, held-out checks, revision guards, expiry and rollback remain mandatory.

## Validation

Added offline phone command, battery-state and retained-candidate regression cases. The full scripts/test-speech.sh suite passes locally, including parsing all 105 Java source files. The installed compiler module was invoked through a temporary javac launcher. Added a Robolectric feedback-consumption/erasure test; Android unit tests and APK build remain unrun because the Gradle distribution download is unavailable. GitHub Actions is the remaining build/test gate after publication approval. No physical-device testing or measured wake accuracy is claimed.

Test on device: repeated standalone wakes; wake plus immediate command; greeting with replies disabled; headset/phone routes; other-speaker rejection; fresh missed-attempt feedback and rollback; USB/wireless/paused/unplugged charging; finder under silent/DND allowing alarms/total silence, Bluetooth, Stop, timeout and service shutdown.

Commit message: Restore wake replies, fix charging questions and add a bounded phone finder
