package com.iris.assistant;

/**
 * Converts 16 kHz mono PCM audio into an 80-band log-mel spectrogram
 * suitable for speaker embedding models.
 *
 * Window: 25 ms (400 samples), Hop: 10 ms (160 samples), 512-point FFT,
 * 80 mel filter banks spanning 0–8000 Hz.
 */
public final class MelSpectrogram {
    private static final int SAMPLE_RATE = 16_000;
    private static final int FFT_SIZE = 512;
    private static final int WINDOW = 400;   // 25 ms
    private static final int HOP = 160;      // 10 ms
    private static final int NUM_MELS = 80;
    private static final double MEL_LOW = 0;
    private static final double MEL_HIGH = 8000;

    private static float[][] melFilterBank;

    private MelSpectrogram() { }

    /**
     * Compute log-mel spectrogram from 16 kHz PCM audio.
     * @return float[numFrames][80] — one row per time frame, 80 mel bands per row.
     */
    public static float[][] compute(short[] audio) {
        if (audio.length < WINDOW) return new float[0][0];
        if (melFilterBank == null) melFilterBank = createMelFilterBank();

        int numFrames = 1 + (audio.length - WINDOW) / HOP;
        float[][] result = new float[numFrames][NUM_MELS];

        for (int frame = 0; frame < numFrames; frame++) {
            int offset = frame * HOP;
            double[] power = powerSpectrum(audio, offset);
            for (int m = 0; m < NUM_MELS; m++) {
                double energy = 0;
                for (int k = 0; k < power.length; k++) {
                    energy += melFilterBank[m][k] * power[k];
                }
                result[frame][m] = (float) Math.log(Math.max(energy, 1e-10));
            }
        }

        // Per-channel mean normalization
        for (int m = 0; m < NUM_MELS; m++) {
            double mean = 0;
            for (int f = 0; f < numFrames; f++) mean += result[f][m];
            mean /= numFrames;
            for (int f = 0; f < numFrames; f++) result[f][m] -= (float) mean;
        }

        return result;
    }

    private static double[] powerSpectrum(short[] audio, int offset) {
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];

        // Apply Hann window and copy to FFT buffer
        for (int i = 0; i < WINDOW && offset + i < audio.length; i++) {
            double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (WINDOW - 1)));
            real[i] = audio[offset + i] / 32768.0 * hann;
        }

        fft(real, imag);

        int bins = FFT_SIZE / 2 + 1;
        double[] power = new double[bins];
        for (int k = 0; k < bins; k++) {
            power[k] = real[k] * real[k] + imag[k] * imag[k];
        }
        return power;
    }

    /** In-place Cooley-Tukey FFT. Arrays must be power-of-2 length. */
    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        // Butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wR = Math.cos(angle), wI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curR = 1, curI = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uR = real[i + j], uI = imag[i + j];
                    double vR = real[i + j + len / 2] * curR - imag[i + j + len / 2] * curI;
                    double vI = real[i + j + len / 2] * curI + imag[i + j + len / 2] * curR;
                    real[i + j] = uR + vR;
                    imag[i + j] = uI + vI;
                    real[i + j + len / 2] = uR - vR;
                    imag[i + j + len / 2] = uI - vI;
                    double newCurR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR;
                    curR = newCurR;
                }
            }
        }
    }

    private static float[][] createMelFilterBank() {
        int bins = FFT_SIZE / 2 + 1;
        float[][] filters = new float[NUM_MELS][bins];

        double melLow = hzToMel(MEL_LOW);
        double melHigh = hzToMel(MEL_HIGH);
        double[] melPoints = new double[NUM_MELS + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melLow + (melHigh - melLow) * i / (NUM_MELS + 1);
        }

        double[] hzPoints = new double[melPoints.length];
        int[] fftBins = new int[melPoints.length];
        for (int i = 0; i < melPoints.length; i++) {
            hzPoints[i] = melToHz(melPoints[i]);
            fftBins[i] = (int) Math.round(hzPoints[i] * FFT_SIZE / SAMPLE_RATE);
            fftBins[i] = Math.min(fftBins[i], bins - 1);
        }

        for (int m = 0; m < NUM_MELS; m++) {
            int left = fftBins[m], center = fftBins[m + 1], right = fftBins[m + 2];
            for (int k = left; k <= center && k < bins; k++) {
                filters[m][k] = center == left ? 1f : (float) (k - left) / (center - left);
            }
            for (int k = center; k <= right && k < bins; k++) {
                filters[m][k] = right == center ? 1f : (float) (right - k) / (right - center);
            }
        }
        return filters;
    }

    private static double hzToMel(double hz) { return 2595.0 * Math.log10(1.0 + hz / 700.0); }
    private static double melToHz(double mel) { return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0); }
}
