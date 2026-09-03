package com.iris.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tiny HTTP client for IRIS Server Mode. No third-party deps — HttpURLConnection + org.json.
 * Every call returns null / false on ANY error so the caller can fall back to on-device.
 * See SERVER-MODE.md and SERVER-MODE-IMPLEMENTATION.md.
 */
final class ServerClient {
    private final String baseUrl;
    private final String token;

    ServerClient(String baseUrl, String token) {
        this.baseUrl = trimSlash(baseUrl == null ? "" : baseUrl.trim());
        this.token = token == null ? "" : token.trim();
    }

    boolean isConfigured() { return !baseUrl.isEmpty(); }

    /** GET /health → true only if reachable and {"ok":true}. */
    boolean health(int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = open("/health", "GET", timeoutMs, timeoutMs);
            if (c.getResponseCode() / 100 != 2) return false;
            return new JSONObject(readAll(c.getInputStream())).optBoolean("ok", false);
        } catch (Throwable t) {
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    /** POST /chat → reply text, or null on any failure. */
    String chat(String message, JSONArray context, JSONObject profile, int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = open("/chat", "POST", connectMs, readMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("message", message == null ? "" : message);
            if (context != null) body.put("context", context);
            if (profile != null) body.put("profile", profile);
            writeBody(c, body.toString().getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() / 100 != 2) return null;
            String reply = new JSONObject(readAll(c.getInputStream())).optString("reply", "");
            return reply.isEmpty() ? null : reply;
        } catch (Throwable t) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    /** POST /transcribe (16k mono WAV) → recognized text, or null on failure. */
    String transcribe(short[] pcm16kMono, int connectMs, int readMs) {
        if (pcm16kMono == null || pcm16kMono.length == 0) return null;
        HttpURLConnection c = null;
        try {
            c = open("/transcribe", "POST", connectMs, readMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/wav");
            writeBody(c, wav(pcm16kMono, 16000));
            if (c.getResponseCode() / 100 != 2) return null;
            String text = new JSONObject(readAll(c.getInputStream())).optString("text", "");
            return text.trim().isEmpty() ? null : text.trim();
        } catch (Throwable t) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    // ── helpers ──
    private HttpURLConnection open(String path, String method, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static void writeBody(HttpURLConnection c, byte[] data) throws Exception {
        try (OutputStream os = c.getOutputStream()) { os.write(data); }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Build a 16-bit PCM mono WAV from samples. */
    private static byte[] wav(short[] pcm, int sampleRate) {
        int dataLen = pcm.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        writeStr(out, "RIFF"); writeIntLE(out, 36 + dataLen); writeStr(out, "WAVE");
        writeStr(out, "fmt "); writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, 1);
        writeIntLE(out, sampleRate); writeIntLE(out, sampleRate * 2); writeShortLE(out, 2); writeShortLE(out, 16);
        writeStr(out, "data"); writeIntLE(out, dataLen);
        for (short s : pcm) writeShortLE(out, s);
        return out.toByteArray();
    }
    private static void writeStr(ByteArrayOutputStream o, String s) { for (char ch : s.toCharArray()) o.write(ch); }
    private static void writeIntLE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }
    private static void writeShortLE(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
}
