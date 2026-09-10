# IRIS owner-approved wake contract

Read IRIS-Android/PROJECT-RULES.md before editing.

The owner explicitly requested protection of trained-voice wake behaviour on 2026-09-10.
Future agents must obtain explicit approval in the current user request before changing:
- wake phrases, alternate phrases, enrolled owner identity or quiet-voice profiles;
- owner matching thresholds, phrase confidence, cooldowns, media rejection or retry behaviour;
- audio gain, enrollment validation, model selection, or authentication for wake settings.

The owner's request to fix trained-voice/whisper wake authorizes the 8.17.0 patch. It is not
standing permission for later agents to weaken or retune these settings. For other work,
preserve this contract and these settings. Stop and ask if a proposed change affects them.

Required invariants:
- Never wake from partial text, noise alone, missing speaker evidence or repeated rejections.
- Do not lower identity thresholds automatically or learn identity from rejected/live audio.
- Quiet capture is not permission to skip owner verification.
- Profile imports and general command training must not replace wake identity.
- Apply replacement enrollment only after validation and explicit device-authenticated save.
- Runtime setting writes must remain behind WakeChangeApproval; only MainActivity's
  explicit authenticated approval flow may enter its write scope.
- Keep test and production wake phrase/audio/identity checks consistent.
- Do not claim universal whisper recognition, replay resistance or guaranteed lock-screen
  execution. Test microphone distance, noise, media, permissions and OEM behaviour.

Run scripts/test-speech.sh and an Android APK build for relevant changes. Never change tests
merely to permit an unauthorized weakening of this policy.

This is an instruction for cooperating contributors, not a GitHub security boundary.
Repository owners can additionally configure branch protection and human review in GitHub.
