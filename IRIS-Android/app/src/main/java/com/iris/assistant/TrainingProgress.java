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
    private static final int VERSION = 2;

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

    static synchronized void clear(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (f.exists()) { //noinspection ResultOfMethodCallIgnored
            f.delete(); }
    }

    static synchronized void save(Context c, String phrase, int sampleIndex,
                     List<float[][]> templates, List<short[]> raw) {
        File f = new File(c.getFilesDir(), FILE);
        android.util.AtomicFile file=new android.util.AtomicFile(f);
        FileOutputStream stream=null;
        try {
            stream=file.startWrite();
            DataOutputStream o=new DataOutputStream(new BufferedOutputStream(stream));
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
            o.flush();file.finishWrite(stream);
        } catch (Exception ignored) { if(stream!=null)file.failWrite(stream); }
    }

    static synchronized Data load(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists() || f.length()>2_000_000) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != VERSION) return null;
            Data d = new Data();
            d.phrase = in.readUTF();
            d.sampleIndex = in.readInt();
            if(d.sampleIndex<0 || d.sampleIndex>6 || d.phrase.length()>200)return null;
            int tc = in.readInt();
            if(tc<0 || tc>6)return null;
            for (int i = 0; i < tc; i++) {
                int frames = in.readInt();
                if(frames<0 || frames>120)return null;
                float[][] t = new float[frames][];
                for (int fr = 0; fr < frames; fr++) {
                    int feats = in.readInt();
                    if(feats<0 || feats>20)return null;
                    float[] frame = new float[feats];
                    for (int j = 0; j < feats; j++) frame[j] = in.readFloat();
                    t[fr] = frame;
                }
                d.templates.add(t);
            }
            int rc = in.readInt();
            if(rc<0 || rc>6 || rc!=d.sampleIndex || tc!=rc)return null;
            for (int i = 0; i < rc; i++) {
                int len = in.readInt();
                if(len<0 || len>80000)return null;
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
