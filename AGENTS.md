# Owner wake contract

Read IRIS-Android/PROJECT-RULES.md. Future changes to owner identity, wake phrases, strictness, audio processing, model selection, media rejection or authenticated write controls require an explicit current user request. Do not weaken these to improve apparent responsiveness.

Missing identity/model, invalid vectors and exceptions must reject wake. Quiet speech never bypasses owner checks. Enrollment accepts only the complete typed phrase and saves only after validation and device authentication. Command practice and generic imports cannot replace owner identity. WakeChangeApproval may only be entered from explicit authenticated MainActivity actions.

Run scripts/test-speech.sh and the Android APK build. Report actual device-test evidence; do not claim universal whisper, replay resistance or lock-screen system control. These instructions guide contributors; repository review/branch protection is a separate owner-controlled security boundary.
