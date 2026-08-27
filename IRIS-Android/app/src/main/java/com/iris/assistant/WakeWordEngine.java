package com.iris.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WakeWordEngine {
    public interface Listener {
        void onStatus(String status);
        void onSample(float[][] features, String quality, float signalToNoise);
        void onWakeDetected(double distance);
        void onError(String message);
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME = 320;
    private static final int MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 3;
    private static final double[] FREQUENCIES = {250, 400, 600, 800, 1000, 1300, 1700, 2200, 2900, 3800};

    private final Context context;
    private final String micPreference;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile int generation;
    private AudioRecord recorder;
    private Thread worker;

    public WakeWordEngine(Context context) {
        this.context = context.getApplicationContext();
        this.micPreference = new AppSettings(context).preferredMicrophone();
    }

    public void captureOne(Listener listener) {
        start(listener, true, new ArrayList<>(), 0);
    }

    public void detect(List<float[][]> templates, double threshold, Listener listener) {
        start(listener, false, templates, threshold);
    }

    private synchronized void start(Listener listener, boolean captureOnly,
                                    List<float[][]> templates, double threshold) {
        stop();
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("Microphone permission is missing.");
            return;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            AudioRecord activeRecorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, FRAME * 8))
                    .build();
            recorder = activeRecorder;
            selectPreferredInput(activeRecorder);
            if (activeRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseSpecific(activeRecorder);
                recorder = null;
                listener.onError("Microphone initialization failed. Close other apps using the mic and try again.");
                return;
            }
            activeRecorder.startRecording();
            running = true;
            int activeGeneration = ++generation;
            worker = new Thread(() -> audioLoop(activeRecorder, activeGeneration,
                            listener, captureOnly, templates, threshold),
                    "IRIS-WakeWord");
            worker.start();
        } catch (Exception error) {
            releaseRecorder();
            listener.onError("Could not open the active microphone: " + error.getMessage());
        }
    }

    private void audioLoop(AudioRecord activeRecorder, int activeGeneration,
                           Listener listener, boolean captureOnly,
                           List<float[][]> templates, double threshold) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] frame = new short[FRAME];
        ShortCollector utterance = new ShortCollector(MAX_UTTERANCE_SAMPLES);
        double noiseFloor = 160;
        double peak = 0;
        int hotFrames = 0;
        int quietFrames = 0;
        boolean speaking = false;
        postStatus(listener, captureOnly ? "Listening for your wake phrase…" : "Wake phrase armed");

        while (running && generation == activeGeneration) {
            int count;
            try { count = activeRecorder.read(frame, 0, frame.length); }
            catch (Exception error) { postError(listener, "Microphone interrupted."); break; }
            if (count <= 0) continue;
            double rms = rms(frame, count);
            peak = Math.max(peak, rms);
            double trigger = Math.max(420, noiseFloor * 2.8);

            if (!speaking) {
                noiseFloor = noiseFloor * .97 + Math.min(rms, trigger) * .03;
                hotFrames = rms > trigger ? hotFrames + 1 : 0;
                if (hotFrames >= 2) {
                    speaking = true;
                    quietFrames = 0;
                    utterance.clear();
                    postStatus(listener, "Voice detected…");
                }
            }

            if (speaking) {
                utterance.add(frame, count);
                quietFrames = rms < trigger * .62 ? quietFrames + 1 : 0;
                if ((quietFrames >= 14 && utterance.size() > SAMPLE_RATE / 3)
                        || utterance.size() >= MAX_UTTERANCE_SAMPLES) {
                    float[][] features = extractFeatures(utterance.toArray());
                    double snr = peak / Math.max(1, noiseFloor);
                    peak = 0;
                    speaking = false;
                    hotFrames = 0;
                    quietFrames = 0;
                    if (features.length < 12) continue;
                    if (captureOnly) {
                        String quality = snr >= 5 ? "Clear" : snr >= 3 ? "Usable" : "Too noisy";
                        running = false;
                        double finalSnr = snr;
                        main.post(() -> listener.onSample(features, quality, (float) finalSnr));
                        break;
                    }
                    double distance = bestDistance(features, templates);
                    if (distance <= threshold) {
                        running = false;
                        main.post(() -> listener.onWakeDetected(distance));
                        break;
                    }
                    postStatus(listener, "Wake phrase armed");
                }
            }
        }
        synchronized (WakeWordEngine.this) {
            if (recorder == activeRecorder) {
                recorder = null;
                releaseSpecific(activeRecorder);
            }
        }
    }

    public synchronized void stop() {
        running = false;
        generation++;
        Thread oldWorker = worker;
        worker = null;
        if (oldWorker != null) {
            oldWorker.interrupt();
            try { oldWorker.join(500); } catch (InterruptedException ignored) { }
        }
        AudioRecord oldRecorder = recorder;
        recorder = null;
        releaseSpecific(oldRecorder);
    }

    private synchronized void releaseRecorder() {
        AudioRecord oldRecorder = recorder;
        recorder = null;
        releaseSpecific(oldRecorder);
    }

    private synchronized void releaseSpecific(AudioRecord target) {
        if (target == null) return;
        try { target.stop(); } catch (Exception ignored) { }
        try { target.release(); } catch (Exception ignored) { }
        if (recorder == target) recorder = null;
    }

    private void selectPreferredInput(AudioRecord target) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        String preference = micPreference;
        AudioDeviceInfo phone = null;
        AudioDeviceInfo automatic = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            boolean bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
            boolean wired = type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET;
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) phone = device;
            if (automatic == null && (bluetooth || wired)) automatic = device;
            if ("Bluetooth".equals(preference) && bluetooth) { target.setPreferredDevice(device); return; }
            if ("Wired / USB".equals(preference) && wired) { target.setPreferredDevice(device); return; }
        }
        if ("Phone".equals(preference) && phone != null) target.setPreferredDevice(phone);
        else if (automatic != null) target.setPreferredDevice(automatic);
    }

    private void postStatus(Listener listener, String status) {
        main.post(() -> listener.onStatus(status));
    }

    private void postError(Listener listener, String message) {
        main.post(() -> listener.onError(message));
    }

    public static double calibratedThreshold(List<float[][]> templates) {
        if (templates.size() < 2) return 1.05;
        double maximum = 0;
        for (int i = 0; i < templates.size(); i++) {
            for (int j = i + 1; j < templates.size(); j++) {
                maximum = Math.max(maximum, dtw(templates.get(i), templates.get(j)));
            }
        }
        return Math.max(.60, Math.min(1.85, maximum * 1.38 + .08));
    }

    private static double bestDistance(float[][] sample, List<float[][]> templates) {
        double best = Double.MAX_VALUE;
        for (float[][] template : templates) best = Math.min(best, dtw(sample, template));
        return best;
    }

    private static double dtw(float[][] a, float[][] b) {
        if (a.length == 0 || b.length == 0) return Double.MAX_VALUE;
        double[] previous = new double[b.length + 1];
        double[] current = new double[b.length + 1];
        Arrays.fill(previous, Double.POSITIVE_INFINITY);
        previous[0] = 0;
        int window = Math.max(12, Math.abs(a.length - b.length) + 8);
        for (int i = 1; i <= a.length; i++) {
            Arrays.fill(current, Double.POSITIVE_INFINITY);
            int from = Math.max(1, i - window);
            int to = Math.min(b.length, i + window);
            for (int j = from; j <= to; j++) {
                double cost = frameDistance(a[i - 1], b[j - 1]);
                current[j] = cost + Math.min(previous[j], Math.min(current[j - 1], previous[j - 1]));
            }
            double[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length] / Math.max(a.length, b.length);
    }

    private static double frameDistance(float[] a, float[] b) {
        double sum = 0;
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            double difference = a[i] - b[i];
            sum += difference * difference;
        }
        return Math.sqrt(sum / Math.max(1, length));
    }

    private static float[][] extractFeatures(short[] audio) {
        int window = 400;
        int hop = 160;
        if (audio.length < window) return new float[0][0];
        int frameCount = 1 + (audio.length - window) / hop;
        int stride = Math.max(1, (int) Math.ceil(frameCount / 85.0));
        List<float[]> rows = new ArrayList<>();
        for (int start = 0, index = 0; start + window <= audio.length; start += hop, index++) {
            if (index % stride != 0) continue;
            float[] feature = new float[FREQUENCIES.length + 2];
            double energy = 0;
            int crossings = 0;
            for (int i = 0; i < window; i++) {
                double sample = audio[start + i] / 32768.0;
                energy += sample * sample;
                if (i > 0 && ((audio[start + i] >= 0) != (audio[start + i - 1] >= 0))) crossings++;
            }
            feature[0] = (float) Math.log10(1e-7 + energy / window);
            feature[1] = crossings / (float) window;
            double mean = 0;
            for (int f = 0; f < FREQUENCIES.length; f++) {
                double power = goertzel(audio, start, window, FREQUENCIES[f]);
                feature[f + 2] = (float) Math.log10(1e-9 + power);
                mean += feature[f + 2];
            }
            mean /= FREQUENCIES.length;
            for (int f = 0; f < FREQUENCIES.length; f++) feature[f + 2] -= (float) mean;
            rows.add(feature);
        }
        float[][] result = rows.toArray(new float[0][]);
        normalizeColumns(result);
        return result;
    }

    private static double goertzel(short[] audio, int start, int length, double frequency) {
        double omega = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        double coefficient = 2.0 * Math.cos(omega);
        double q0;
        double q1 = 0;
        double q2 = 0;
        for (int i = 0; i < length; i++) {
            double hamming = .54 - .46 * Math.cos(2 * Math.PI * i / (length - 1));
            q0 = audio[start + i] * hamming + coefficient * q1 - q2;
            q2 = q1;
            q1 = q0;
        }
        return q1 * q1 + q2 * q2 - coefficient * q1 * q2;
    }

    private static void normalizeColumns(float[][] values) {
        if (values.length == 0) return;
        for (int column = 0; column < values[0].length; column++) {
            double mean = 0;
            for (float[] row : values) mean += row[column];
            mean /= values.length;
            double variance = 0;
            for (float[] row : values) variance += Math.pow(row[column] - mean, 2);
            double deviation = Math.sqrt(variance / values.length) + 1e-5;
            for (float[] row : values) row[column] = (float) ((row[column] - mean) / deviation);
        }
    }

    private static double rms(short[] samples, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) sum += (double) samples[i] * samples[i];
        return Math.sqrt(sum / Math.max(1, length));
    }

    private static class ShortCollector {
        private final short[] values;
        private int size;
        ShortCollector(int capacity) { values = new short[capacity]; }
        void clear() { size = 0; }
        int size() { return size; }
        void add(short[] source, int count) {
            int writable = Math.min(count, values.length - size);
            if (writable > 0) {
                System.arraycopy(source, 0, values, size, writable);
                size += writable;
            }
        }
        short[] toArray() { return Arrays.copyOf(values, size); }
    }
}
