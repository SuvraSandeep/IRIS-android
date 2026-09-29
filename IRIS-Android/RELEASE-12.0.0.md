# IRIS 12.0.0 — reliability tools and optional companion entry points

Includes 11.2.1 repeat-wake/rearm fixes. New Settings controls expose microphone health, actual route, recent candidate distances/rejection stages, local labelled wake-study results, metadata sharing, offline language readiness, opt-in encrypted command history, editable routines, experimental watch pairing and default assistant selection.

Training still uses four enrollment plus four held-out phrase/owner checks. It now coaches normal/soft/quicker/relaxed distance variations. Missed-wake recording requires device authentication, offers in-memory playback and a two-minute expiry, and keeps authenticated save, revision guards and rollback. Optional ten-second speaker refinement never changes the phrase, requires the existing owner to match, anchors identity drift, and revalidates every saved check. Full enrollment remains required for substantial identity changes.

Command history is off by default, encrypted, bounded to 100 records and seven days, and pruned on access. It records parser intent and the latest response; the separate action ledger records confirmed outcomes. Legacy routes are labelled as such rather than inventing an intent. Retry uses the existing executor. Routines contain up to six editable commands; each action is reviewed and tapped individually. This release does not automate a multi-step sequence.

Android 13+ can report on-device language installations and request a model download; provider responses are not guarantees of recognition accuracy. Conflicting close-confidence system alternatives prompt repetition. Vosk remains available independently.

The watch project is an experimental Zepp remote with a bounded loopback bridge, revocable secret, and status/find/stop/talk-notification endpoints. Real Zepp localhost compatibility is unverified. The assistant service provides a selectable system gesture entry with a Talk button, not hardware hotword enrollment. Neither feature weakens owner voice checks.

## Validation and device acceptance

Run `scripts/test-speech.sh`, `:app:testDebugUnitTest`, `assembleDebug`, and `npm run check` in IRIS-Watch. Synthetic/Robolectric tests establish control flow and profile invariants, not acoustic accuracy. On the actual phone, label 50–100 deliberate calls across screen-off, repeated commands, quiet/noise and each trained mic; measure misses and unintended wakes. Exercise feedback cancel/expiry/background, reject another speaker, retain all four saved checks and try rollback. Check live charging plugged/unplugged. Test finder with each real DND mode and restored alarm volume. Validate watch transport and assistant selection on physical devices before relying on them.
