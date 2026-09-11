package com.iris.assistant;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists in-progress wake-phrase training (captured samples + step) so the user can
 * exit and resume later. Stored as a small binary blob in filesDir.
 */
final class TrainingProgress {
    private static final String FILE = "wake_training.dat";
    private static final int VERSION = 1;
    // Mirrors ProfileStore.getWakeProfile()'s caps on templates/frames/features — a corrupt or
    // truncated resume file must never be allowed to drive an allocation size straight from
    // untrusted file bytes (a bad int here previously could throw OutOfMemoryError, which is an
    // Error, not caught by load()'s catch(Exception) — so a corrupt file could crash the app).
    private static final int MAX_TEMPLATES = 5;
    private static final int MAX_FRAMES_PER_TEMPLATE = 120;
    private static final int MAX_FEATURES_PER_FRAME = 20;
    private static final int MAX_RAW_SAMPLES = 5;
    private static final int MAX_RAW_SAMPLE_LEN = 16_000 * 10; // 10s of 16kHz audio, generous cap

    static final class Data {
        String phrase = "";
        int sampleIndex = 0;
        final List<float[][]> templates = new ArrayList<>();
        final List<short[]> rawSamples = new ArrayList<>();
    }

    static boolean exists(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        return f.exists() && f.length() > 0;
    }

    static void clear(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (f.exists()) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }
    }

    static void save(Context c, String phrase, int sampleIndex,
                     List<float[][]> templates, List<short[]> raw) {
        // Write to a temp file and atomically rename over the real one, so a crash/kill mid-write
        // can never leave a truncated file that exists() reports as present but load() can't read
        // (previously: direct FileOutputStream over the live file — a partial write there is a
        // silent "resume available" that fails when the user actually tries to resume).
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        File dest = new File(c.getFilesDir(), FILE);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(VERSION);
            o.writeUTF(phrase == null ? "" : phrase);
            o.writeInt(sampleIndex);
            o.writeInt(templates == null ? 0 : templates.size());
            if (templates != null) for (float[][] t : templates) {
                o.writeInt(t.length);
                for (float[] frame : t) { o.writeInt(frame.length); for (float v : frame) o.writeFloat(v); }
            }
            o.writeInt(raw == null ? 0 : raw.size());
            if (raw != null) for (short[] s : raw) {
                o.writeInt(s.length);
                for (short v : s) o.writeShort(v);
            }
            o.flush();
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!tmp.renameTo(dest)) {
            // Rename can fail across filesystems/edge cases; fall back to a direct copy so a
            // save attempt isn't silently lost.
            try (FileInputStream in = new FileInputStream(tmp);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (Exception ignored) { }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static Data load(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != VERSION) return null;
            Data d = new Data();
            d.phrase = in.readUTF();
            d.sampleIndex = in.readInt();
            int tc = in.readInt();
            if (tc < 0 || tc > MAX_TEMPLATES) return null;
            for (int i = 0; i < tc; i++) {
                int frames = in.readInt();
                if (frames < 0 || frames > MAX_FRAMES_PER_TEMPLATE) return null;
                float[][] t = new float[frames][];
                for (int fr = 0; fr < frames; fr++) {
                    int feats = in.readInt();
                    if (feats < 0 || feats > MAX_FEATURES_PER_FRAME) return null;
                    float[] frame = new float[feats];
                    for (int j = 0; j < feats; j++) frame[j] = in.readFloat();
                    t[fr] = frame;
                }
                d.templates.add(t);
            }
            int rc = in.readInt();
            if (rc < 0 || rc > MAX_RAW_SAMPLES) return null;
            for (int i = 0; i < rc; i++) {
                int len = in.readInt();
                if (len < 0 || len > MAX_RAW_SAMPLE_LEN) return null;
                short[] s = new short[len];
                for (int j = 0; j < len; j++) s[j] = in.readShort();
                d.rawSamples.add(s);
            }
            // Reconcile: sampleIndex must never point past the samples actually captured — a
            // partial/interrupted save could otherwise leave it stale, resuming the wizard at a
            // step with no corresponding data (captureNextWakeSample would then have nothing to
            // show for the "already captured" steps it thinks exist).
            if (d.sampleIndex < 0 || d.sampleIndex > d.templates.size()) d.sampleIndex = d.templates.size();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    static int peekIndex(Context c) {
        Data d = load(c);
        return d == null ? 0 : d.sampleIndex;
    }
}
