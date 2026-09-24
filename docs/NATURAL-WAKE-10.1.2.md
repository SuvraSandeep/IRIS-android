# Natural wake variation — 10.1.2 (338)

Root cause: phrase scoring averaged the three closest enrollment recordings. Calibration also required all four examples to resemble each other; distinct natural styles could make the fourth take fail. Repeating the same sound exactly is not a reasonable requirement.

New phone/headset training uses a versioned closest-example bank. Each of four authenticated examples represents an intended variation; diverse usable examples are retained. A bounded radius is calibrated from nearest neighbors (0.08 minimum base, 0.24 maximum base, final policy cap 0.32). Four independently recorded verification takes must pass this same matcher and the unchanged owner check. Unrelated sounds, invalid evidence and negative examples still reject. No transcript gate or automatic owner weakening was added.

Existing profiles preserve their legacy matcher. Retrain once with normal, soft, quicker and relaxed versions of the same phrase; then record four fresh checks. This is essential because old profiles may contain only near-identical examples. Save requires device authentication. Headsets have a separate bank.

Settings removes experimental continuous mode, the large ~1GB command model switch, the disabled owner-only toggle and duplicate feedback actions. Existing continuous preference resolves to wake; command recognition uses the small decoder. Keep system speech opt-in, microphone, authenticated strictness, permissions and privacy controls. Feedback wording no longer promises automatic easing of security.

Validation: local regression script and Android unit tests/APK build in PR CI. New synthetic tests cover distinct styles, pace changes, independent validation, corrupt evidence and stranger rejection. This does not establish real-device accuracy or exclusive-owner/replay resistance. If speaker identity, rather than phrase matching, still fails, authenticated correction or improved owner enrollment is needed; the algorithm cannot guarantee every voice variation.
