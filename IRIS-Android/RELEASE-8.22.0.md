# IRIS 8.22.0 (314)

Repairs owner verification, exact-phrase enrollment, actual microphone reporting, photo flash sequencing and phone-mode responses on top of main 2545698.

- Owner-only wake rejects missing/malformed identity and errors. Owner strictness is separate from phrase sensitivity and has the correct direction.
- Training validates all spoken words offline before accepting each take, builds normal/quiet centroids and requires held-out verification plus authenticated atomic save. General command practice/import cannot replace owner identity. Existing unvalidated resumable takes are not reused.
- Vosk now uses application-owned AudioRecord capture with observed-route reporting. Phone/headset routing is measured; Bluetooth use may change media quality.
- Preview uses a separate drained YUV surface; only the final JPEG/result transaction is saved. Flash capability and outcome are checked; timeout does not fake flash success.
- System Battery Saver requests do not alter IRIS power saving or return early based on its state. DND responses check effective state. Airplane/Battery Saver still require Android settings interaction.
- Speech recognition is local; remote STT preferences cannot activate uploads.

Validation: 516 offline regression checks pass, plus source parsing. Android build pending. Real-device microphone, whisper, replay, camera and OEM mode tests remain required; no universal recognition, liveness or locked-screen system-toggle guarantee.

Commit message: fix: enforce owner wake and exact enrollment; repair capture routing and system modes
