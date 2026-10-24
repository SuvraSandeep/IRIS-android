package com.iris.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    /**
     * Streams chunks from /chat/stream, feeding completed sentences directly via StreamListener.
     */
    void chat(String message, JSONArray context, JSONObject profile, int connectMs, int readMs, StreamListener listener) {
        chatStream(message, context, profile, connectMs, readMs, listener);
    }

    interface StreamListener {
        /** Called on each completed sentence extracted from the SSE stream. */
        void onSentence(String sentence, int index);
        /** Called when the stream has concluded with the full response text. */
        void onComplete(String fullReply);
        /** Called if an unrecoverable network or stream error occurs. */
        void onError(Throwable error);
    }

    /**
     * Stream chat from POST /chat/stream, invoking listener.onSentence() for each completed sentence
     * and listener.onComplete() when the stream finishes.
     * If the server does not support streaming (e.g. 404), falls back automatically to POST /chat.
     */
    void chatStream(String message, JSONArray context, JSONObject profile, int connectMs, int readMs, StreamListener listener) {
        HttpURLConnection c = null;
        try {
            c = open("/chat/stream", "POST", connectMs, readMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            JSONObject body = new JSONObject();
            body.put("message", message == null ? "" : message);
            if (context != null) body.put("context", context);
            if (profile != null) body.put("profile", profile);
            writeBody(c, body.toString().getBytes(StandardCharsets.UTF_8));

            int code = c.getResponseCode();
            if (code == 404) {
                // Server does not support /chat/stream; fall back to synchronous /chat
                c.disconnect();
                c = null;
                String reply = chat(message, context, profile, connectMs, readMs);
                if (reply != null && !reply.isEmpty()) {
                    if (listener != null) {
                        listener.onSentence(reply, 1);
                        listener.onComplete(reply);
                    }
                } else if (listener != null) {
                    listener.onError(new java.io.IOException("Server returned 404 and fallback chat failed"));
                }
                return;
            }

            if (code / 100 != 2) {
                if (listener != null) listener.onError(new java.io.IOException("HTTP error " + code));
                return;
            }

            StringBuilder fullReply = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            int sentenceIndex = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("data:")) continue;
                    String jsonStr = line.substring(5).trim();
                    if (jsonStr.isEmpty()) continue;

                    JSONObject chunk = new JSONObject(jsonStr);
                    if (chunk.has("error")) {
                        throw new java.io.IOException(chunk.optString("error", "Stream error"));
                    }
                    String delta = chunk.optString("delta", "");
                    boolean done = chunk.optBoolean("done", false);

                    if (!delta.isEmpty()) {
                        fullReply.append(delta);
                        pending.append(delta);

                        // Check for sentence boundaries
                        while (true) {
                            int split = findSentenceSplit(pending.toString());
                            if (split <= 0) break;
                            String sentence = pending.substring(0, split).trim();
                            pending.delete(0, split);
                            if (!sentence.isEmpty()) {
                                sentenceIndex++;
                                if (listener != null) listener.onSentence(sentence, sentenceIndex);
                            }
                        }
                    }

                    if (done) break;
                }
            }

            // Flush any remaining text in pending buffer
            String remaining = pending.toString().trim();
            if (!remaining.isEmpty()) {
                sentenceIndex++;
                if (listener != null) listener.onSentence(remaining, sentenceIndex);
            }

            if (listener != null) {
                listener.onComplete(fullReply.toString().trim());
            }
        } catch (Throwable t) {
            if (listener != null) listener.onError(t);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int findSentenceSplit(String s) {
        if (s == null || s.length() < 3) return -1;
        int openBrackets = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                openBrackets++;
            } else if (c == ']' && openBrackets > 0) {
                openBrackets--;
            }

            if (openBrackets == 0) {
                if (c == '.' || c == '!' || c == '?') {
                    if (i + 1 < s.length()) {
                        char next = s.charAt(i + 1);
                        if (Character.isWhitespace(next)) {
                            if (c == '.' && isAbbreviation(s, i)) {
                                continue;
                            }
                            return i + 1;
                        }
                    }
                } else if (c == ']') {
                    if (i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1))) {
                        return i + 1;
                    }
                } else if (c == '\n') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        return i + 1;
                    }
                }
            }
        }
        return -1;
    }

    private static boolean isAbbreviation(String s, int dotIdx) {
        int start = dotIdx - 1;
        while (start >= 0 && (Character.isLetter(s.charAt(start)) || s.charAt(start) == '.')) {
            start--;
        }
        String word = s.substring(start + 1, dotIdx).replace(".", "").toLowerCase(java.util.Locale.ROOT);
        return word.equals("mr") || word.equals("mrs") || word.equals("ms")
                || word.equals("dr") || word.equals("vs") || word.equals("etc")
                || word.equals("eg") || word.equals("ie") || word.equals("sr") || word.equals("jr")
                || word.equals("prof") || word.equals("inc") || word.equals("ltd");
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

    /** POST /tts (JSON {text}) → WAV audio bytes, or null on failure. */
    byte[] tts(String text, int connectMs, int readMs) {
        if (text == null || text.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = open("/tts", "POST", connectMs, readMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            writeBody(c, new JSONObject().put("text", text).toString().getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() / 100 != 2) return null;
            return readBytes(c.getInputStream());
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
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
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
