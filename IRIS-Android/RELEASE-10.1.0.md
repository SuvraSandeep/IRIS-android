# IRIS 10.1.0 — simple wake learning, matching modes and event feedback

Version code 336. Includes the unmerged 10.0.2 microphone, capture and retry fixes.

Training: give the sound an optional name, record it four times, try it four more times, then authenticate and save. The name is only a label; no transcript or pronunciation gate is used. The headset input is explicitly selected and confirmed. Failed attempts preserve earlier takes.

Easy / Balanced / Strict is available directly in Training. The control changes both sound tolerance (bounded to the existing maximum distance) and owner threshold (.65–.85), preserving mandatory speaker verification in all modes. A change rebuilds and revalidates the saved profile. The saved profile is the authoritative policy, so an older threshold can no longer silently override a requested easier setting, and undo restores the displayed/effective policy as well. Stricter changes that invalidate saved checks are refused with an explanation. Training, checkpoints and live listening share the chosen limits.

Feedback now acts on a recent event instead of forcing retraining:
- My wake sound was missed: add a bounded local sound example, only if the existing speaker profile verifies the speaker.
- My voice, wrong sound: reject that sound without treating the owner's voice as an impostor.
- Another person: retain the existing negative speaker correction flow.

Sound corrections are specific to the confirmed phone/headset route. All four saved sound and speaker validation takes must remain valid. Authentication, profile-revision checks, two-minute expiry, bounded correction banks, read-back verification and undo apply. Rejected voice evidence cannot silently replace owner identity; insufficient or mismatched speaker evidence needs an explicit policy adjustment or fresh enrollment.

Recent events temporarily retain derived sound patterns and speaker vectors in process memory, not raw recordings. Features are copied, bounded and erased on expiry. Accepted/rejected status reflects the final service decision, including playback restrictions. Updated phrase packages have an explicit format identifier; legacy compatible profiles still load, while old app versions cannot silently ignore new corrections.

Validation: offline regression suite; new JUnit tests for mode behavior, lowering a saved threshold, feedback, wrong speaker/missing evidence rejection, route separation, held-out protection, serialization, event expiry, and policy undo using a storage fake. Full Android tests/build/lint run in GitHub Actions. No hardware recognition accuracy is claimed. This does not guarantee every wake or replay resistance; very short/unclear phrases may not provide enough speaker evidence.

Use: install this APK, keep a compatible existing profile, select Easy or Balanced in Training, and test. If retraining, speak naturally and wait for Listening. After a missed or unwanted wake, open feedback within two minutes. Events with no usable sound/speaker evidence cannot be learned. Wake remains paused during media playback.
