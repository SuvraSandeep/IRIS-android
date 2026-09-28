# Training completion fix — 11.0.4

Based on main 3d1bcfad (11.0.3). This release fixes verified finalization/UI paths; it does not claim the user's specific eighth recording now passes without seeing its rejection evidence.

The last accepted take led to a review with no actionable Save button. Dismissing device authentication, a missing screen lock, or an authentication error could therefore leave eight completed takes on a dead-end review. Cancelling the save dialog also discarded the active session. Review now exposes Save profile, and deferring/dismissing the dialog keeps completed takes. Device authentication remains mandatory.

Profile construction, repeated evidence validation, encrypted writing and read-back previously ran on the UI thread inside the authentication callback. They now run on a worker after explicit authentication, using a deep snapshot of the eight paired takes. UI results check the originating training generation. A busy guard prevents duplicate commits; the cancel control is disabled during an active save. Revision checks, read-back validation, rollback and four independent held-out checks are unchanged.

A rejected recording logs its take index, audio quality, phrase distance/limit and owner score/limit. This separates microphone/phrase/identity rejection from authentication or save failure. No raw recording is added to logging.

Tests cover actionable review/save failure, isolated snapshots after clearing UI state, and eight valid paired takes building and round-tripping a profile that still rejects another speaker. Offline regressions passed; Android build/test results are recorded in the PR. These are synthetic evidence tests, not a successful physical-phone training session.

Scope limit: this is the complete existing application with the above fixes, not the requested replacement neural phrase detector. Neural speech gating, noise suppression, separate longer owner enrollment and a trained noise-augmented phrase classifier remain unimplemented. No universal wake/noise reliability claim is made.
