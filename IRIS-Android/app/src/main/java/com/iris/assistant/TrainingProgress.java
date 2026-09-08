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
        File f = new File(c.getFilesDir(), FILE);
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
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
        } catch (Exception ignored) { }
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
            for (int i = 0; i < tc; i++) {
                int frames = in.readInt();
                float[][] t = new float[frames][];
                for (int fr = 0; fr < frames; fr++) {
                    int feats = in.readInt();
                    float[] frame = new float[feats];
                    for (int j = 0; j < feats; j++) frame[j] = in.readFloat();
                    t[fr] = frame;
                }
                d.templates.add(t);
            }
            int rc = in.readInt();
            for (int i = 0; i < rc; i++) {
                int len = in.readInt();
                short[] s = new short[len];
                for (int j = 0; j < len; j++) s[j] = in.readShort();
                d.rawSamples.add(s);
            }
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
