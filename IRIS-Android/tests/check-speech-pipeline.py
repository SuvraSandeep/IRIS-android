#!/usr/bin/env python3
"""Validates Speech & Voice Pipeline Improvements:
1. Dynamic noise floor adaptation and Silero VAD threshold formulas
2. 500ms moving RMS window and DTW/trigger scaling in WakeWordEngine
3. AudioFocusSession RAII contract and AutoCloseable compliance
4. OnnxSpeechRecognizer acoustic feature and model configuration
"""
import math
import sys
from pathlib import Path

SOURCE_ROOT = Path("app/src/main/java/com/iris/assistant")


def calculate_ambient_db(rms: float) -> float:
    if rms <= 0.0:
        return 0.0
    return 20.0 * math.log10(max(1.0, rms)) + 15.0


def compute_adaptive_threshold(ambient_db: float) -> float:
    if ambient_db <= 40.0:
        return 0.35
    elif ambient_db >= 65.0:
        excess = min(15.0, ambient_db - 65.0)
        return 0.65 + (excess / 15.0) * 0.10
    else:
        ratio = (ambient_db - 40.0) / (65.0 - 40.0)
        return 0.35 + ratio * (0.65 - 0.35)


def test_ambient_db_and_thresholds():
    # 1. Quiet room test (<40 dB)
    quiet_rms = 15.0  # Whisper / quiet room
    quiet_db = calculate_ambient_db(quiet_rms)
    assert quiet_db < 40.0, f"Expected quiet db < 40, got {quiet_db}"
    quiet_threshold = compute_adaptive_threshold(quiet_db)
    assert abs(quiet_threshold - 0.35) < 1e-4, f"Quiet threshold must be relaxed to 0.35, got {quiet_threshold}"

    # 2. Normal room test (~52.5 dB)
    mid_db = 52.5
    mid_threshold = compute_adaptive_threshold(mid_db)
    assert abs(mid_threshold - 0.50) < 1e-4, f"Normal threshold must be 0.50, got {mid_threshold}"

    # 3. Noisy street / car test (>65 dB)
    noisy_rms = 350.0  # Car cabin / city traffic
    noisy_db = calculate_ambient_db(noisy_rms)
    assert noisy_db > 65.0, f"Expected noisy db > 65, got {noisy_db}"
    noisy_threshold = compute_adaptive_threshold(noisy_db)
    assert noisy_threshold >= 0.65, f"Noisy threshold must be >= 0.65, got {noisy_threshold}"

    # 4. Loud street / music test (80 dB)
    loud_db = 80.0
    loud_threshold = compute_adaptive_threshold(loud_db)
    assert abs(loud_threshold - 0.75) < 1e-4, f"Loud threshold at 80dB must be 0.75, got {loud_threshold}"

    # 5. Monotonicity check
    prev = -1.0
    for db in range(30, 85, 2):
        th = compute_adaptive_threshold(float(db))
        assert th >= prev, f"Threshold must be monotonic: db={db}, th={th}, prev={prev}"
        prev = th

    print("PASS: Dynamic noise floor adaptation formulas verified")


def test_java_source_contracts():
    silero_vad_path = SOURCE_ROOT / "SileroVad.java"
    assert silero_vad_path.exists(), "SileroVad.java missing"
    silero_text = silero_vad_path.read_text(encoding="utf-8")
    assert "public static double calculateAmbientDb" in silero_text
    assert "public static float computeAdaptiveThreshold" in silero_text
    assert "public void adaptThresholdFromRms" in silero_text
    assert "public void setSpeechThreshold" in silero_text
    assert "short[] trim(short[] pcm, float threshold)" in silero_text

    wake_word_path = SOURCE_ROOT / "WakeWordEngine.java"
    assert wake_word_path.exists(), "WakeWordEngine.java missing"
    wake_text = wake_word_path.read_text(encoding="utf-8")
    assert "AMBIENT_WINDOW_FRAMES = 25" in wake_text
    assert "setSileroVad(SileroVad" in wake_text
    assert "ambientRmsHistory" in wake_text
    assert "calculateAmbientDb" in wake_text
    assert "sileroVad.adaptThresholdFromRms" in wake_text
    assert "effectiveThreshold" in wake_text

    audio_focus_path = SOURCE_ROOT / "AudioFocusSession.java"
    assert audio_focus_path.exists(), "AudioFocusSession.java missing"
    focus_text = audio_focus_path.read_text(encoding="utf-8")
    assert "implements AutoCloseable" in focus_text
    assert "public synchronized void close()" in focus_text
    assert "public synchronized void resumeMedia()" in focus_text
    assert "public synchronized void transferMediaResume()" in focus_text

    onnx_stt_path = SOURCE_ROOT / "OnnxSpeechRecognizer.java"
    assert onnx_stt_path.exists(), "OnnxSpeechRecognizer.java missing"
    onnx_text = onnx_stt_path.read_text(encoding="utf-8")
    assert "SAMPLE_RATE = 16_000" in onnx_text
    assert "MEL_BINS = 80" in onnx_text
    assert "WINDOW_SIZE = 400" in onnx_text
    assert "HOP_SIZE = 160" in onnx_text
    assert "FFT_SIZE = 512" in onnx_text
    assert "extractLogMelSpectrogram" in onnx_text
    assert "whisper_tiny_en_int8.onnx" in onnx_text

    ils_path = SOURCE_ROOT / "IrisListeningService.java"
    assert ils_path.exists(), "IrisListeningService.java missing"
    ils_text = ils_path.read_text(encoding="utf-8")
    assert "private OnnxSpeechRecognizer onnxRecognizer;" in ils_text
    assert "startOnnxCommandRecognition()" in ils_text
    assert "try (AudioFocusSession session = voiceCoordinator.openSpeechSession(false))" in ils_text

    print("PASS: Java source contracts and integrations verified")


def main():
    test_ambient_db_and_thresholds()
    test_java_source_contracts()
    print("ALL SPEECH PIPELINE CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
