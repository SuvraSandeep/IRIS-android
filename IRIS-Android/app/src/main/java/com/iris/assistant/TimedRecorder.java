package com.iris.assistant;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Records exactly N milliseconds of 16kHz mono PCM audio.
 * Provides a live audio level callback for UI waveform display.
 * No VAD, no guessing — just a clean timed recording.
 */
public final class TimedRecorder {
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SIZE = 512;
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onLevel(float normalizedLevel);  // 0.0 to 1.0, called ~30x/sec
        void onComplete(short[] audio);       // full recording
        void onError(String message);
    }

    private volatile boolean recording;
    private Thread thread;

    /**
     * Record audio for the specified duration.
     * @param durationMs recording duration in milliseconds (e.g. 3000)
     * @param listener callbacks for level updates and completion
     */
    public void record(int durationMs, Listener listener) {
        if (recording) return;
        recording = true;
        thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord mic = null;
            try {
                int totalSamples = SAMPLE_RATE * durationMs / 1000;
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                mic = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(Math.max(minBuf * 2, FRAME_SIZE * 8))
                        .build();

                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    main.post(() -> listener.onError("Microphone not available. Close other apps using it."));
                    return;
                }

                mic.startRecording();
                short[] audio = new short[totalSamples];
                short[] frame = new short[FRAME_SIZE];
                int offset = 0;

                while (recording && offset < totalSamples) {
                    int toRead = Math.min(FRAME_SIZE, totalSamples - offset);
                    int read = mic.read(frame, 0, toRead);
                    if (read > 0) {
                        System.arraycopy(frame, 0, audio, offset, read);
                        offset += read;
                        // Report live level
                        float level = rms(frame, read);
                        main.post(() -> listener.onLevel(level));
                    }
                }

                mic.stop();
                mic.release();
                mic = null;

                if (recording && offset >= totalSamples / 2) {
                    short[] result = offset == totalSamples ? audio
                            : java.util.Arrays.copyOf(audio, offset);
                    main.post(() -> listener.onComplete(result));
                } else {
                    main.post(() -> listener.onError("Recording was too short."));
                }
            } catch (Exception e) {
                main.post(() -> listener.onError("Recording failed: " + e.getMessage()));
            } finally {
                if (mic != null) {
                    try { mic.stop(); } catch (Exception ignored) { }
                    try { mic.release(); } catch (Exception ignored) { }
                }
                recording = false;
            }
        }, "IRIS-TimedRecorder");
        thread.start();
    }

    /** Stop recording early. */
    public void stop() {
        recording = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public boolean isRecording() { return recording; }

    /**
     * Compute RMS audio level, normalized to 0.0-1.0 range.
     */
    private static float rms(short[] samples, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) sum += (double) samples[i] * samples[i];
        double rms = Math.sqrt(sum / Math.max(1, length));
        return (float) Math.min(1.0, rms / 8000.0); // normalize: 8000 = loud speech
    }
}
