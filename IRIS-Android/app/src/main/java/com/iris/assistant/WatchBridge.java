package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Experimental same-phone Zepp relay. Loopback HTTP, local WebSocket, and BLE GATT service. */
final class WatchBridge implements AutoCloseable {
    static final int PORT = 18473;
    interface Actions { String run(String action); }

    private final Context context;
    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile WatchGattBridge gattBridge;
    private long lastAction;
    static volatile String state = "Disabled";

    WatchBridge(Context c, Actions a) {
        context = c;
        actions = a;
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("watch_bridge", 0).getBoolean("enabled", false);
    }

    static void enabled(Context c, boolean on) {
        c.getSharedPreferences("watch_bridge", 0).edit().putBoolean("enabled", on).apply();
    }

    static String token(Context c) throws Exception {
        return SecureStore.read(c, "watch-token.txt", "");
    }

    static String rotate(Context c) throws Exception {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder t = new StringBuilder();
        for (byte b : bytes) t.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        SecureStore.write(c, "watch-token.txt", t.toString());
        return t.toString();
    }

    static boolean authorized(String actual, String expected) {
        if (expected == null || expected.length() != 64 || actual == null) return false;
        String val = actual.trim();
        if (val.regionMatches(true, 0, "Bearer ", 0, 7)) {
            val = val.substring(7).trim();
        }
        return MessageDigest.isEqual(
                val.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                expected.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    static boolean allowed(String method, String path) {
        if (method.equals("OPTIONS")) return true;
        return (method.equals("GET") && (path.equals("/status") || path.equals("/ping")))
                || (method.equals("POST") && (path.equals("/find") || path.equals("/stop") || path.equals("/talk") || path.equals("/ping")));
    }

    void start() {
        if (!enabled(context)) return;
        if (gattBridge == null) {
            gattBridge = new WatchGattBridge(context, actions);
            gattBridge.start();
        }
        new Thread(() -> {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
                server = s;
                if (closed) { s.close(); return; }
                state = "Listening on this phone only";
                while (!closed) {
                    try {
                        final Socket socket = s.accept();
                        client = socket;
                        new Thread(() -> {
                            try (socket) {
                                socket.setSoTimeout(2500);
                                serve(socket);
                            } catch (Exception e) {
                                if (!closed) VoiceHealth.event("WATCH_REQUEST", "Rejected or disconnected");
                            } finally {
                                if (client == socket) client = null;
                            }
                        }, "IRIS-WatchConn").start();
                    } catch (Exception e) {
                        if (!closed) VoiceHealth.event("WATCH_REQUEST", "Accept error");
                    }
                }
            } catch (Exception e) {
                if (!closed) state = "Unavailable: local port could not open";
            }
        }, "IRIS-WatchBridge").start();
    }

    private static String line(InputStream in, long deadline) throws IOException {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Request timeout");
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == 10) return s.toString().replace("\r", "");
            if (b > 0x7F) throw new IOException("Non-ASCII byte in request");
            s.append((char) b);
        }
        throw new IOException("Header too long");
    }

    private void serve(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        long readDeadline = SystemClock.elapsedRealtime() + 2000;
        String[] request = line(in, readDeadline).split(" ");
        if (request.length != 3) {
            respond(socket, 400, "Bad request");
            return;
        }

        String auth = null;
        long sentAt = 0;
        boolean origin = false;
        int length = 0, total = 0;
        boolean end = false;
        boolean isWs = false;
        String wsKey = null;

        for (int i = 0; i < 24; i++) {
            String l = line(in, readDeadline);
            total += l.length();
            if (total > 4096) throw new IOException("Too many headers");
            if (l.isEmpty()) { end = true; break; }
            int at = l.indexOf(':');
            if (at < 1) throw new IOException("Bad header");
            String k = l.substring(0, at).toLowerCase(java.util.Locale.ROOT);
            String v = l.substring(at + 1).trim();
            if (k.equals("authorization")) {
                if (auth != null) throw new IOException("Duplicate authorization");
                auth = v;
            }
            if (k.equals("x-iris-sent-at")) sentAt = Long.parseLong(v);
            if (k.equals("transfer-encoding")) origin = true;
            if (k.equals("content-length")) length = Integer.parseInt(v);
            if (k.equals("upgrade") && v.equalsIgnoreCase("websocket")) isWs = true;
            if (k.equals("sec-websocket-key")) wsKey = v;
        }

        if (isWs && wsKey != null && request[0].equals("GET") && (request[1].equals("/ws") || request[1].startsWith("/ws?"))) {
            if (closed || !enabled(context)) {
                respond(socket, 403, "Pairing required");
                return;
            }
            boolean preAuth = authorized(auth, token(context));
            byte[] keyBytes = (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII);
            String accept = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(keyBytes));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            serveWebSocket(socket, in, out, preAuth);
            return;
        }

        if (request[0].equals("OPTIONS")) {
            respond(socket, 200, "Preflight OK");
            return;
        }

        if (!end || origin || !allowed(request[0], request[1])) {
            respond(socket, 400, "Unsupported request");
            return;
        }
        if (length > 0) {
            long remaining = length;
            byte[] discard = new byte[Math.min(length, 4096)];
            while (remaining > 0) {
                int r = in.read(discard, 0, (int) Math.min(discard.length, remaining));
                if (r < 0) break;
                remaining -= r;
            }
        }
        if (closed || !enabled(context) || !authorized(auth, token(context))) {
            respond(socket, 403, "Pairing required");
            return;
        }
        if (request[0].equals("POST") && (sentAt == 0 || Math.abs(System.currentTimeMillis() - sentAt) > 60000)) {
            respond(socket, 408, "Watch request expired; try again");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!request[1].equals("/status") && now - lastAction < 1500) {
            respond(socket, 429, "Wait before retrying");
            return;
        }
        if (!request[1].equals("/status")) lastAction = now;

        final String path = request[1], credential = auth;
        CompletableFuture<String> result = new CompletableFuture<>();
        long deadline = SystemClock.elapsedRealtime() + 2000;
        Runnable job = () -> {
            if (closed || SystemClock.elapsedRealtime() > deadline || !enabled(context)) {
                result.complete("Bridge disabled or request expired");
                return;
            }
            try {
                if (!authorized(credential, token(context))) {
                    result.complete("Pairing expired");
                    return;
                }
                result.complete(actions.run(path));
            } catch (Exception e) {
                result.complete("Action unavailable");
            }
        };
        main.post(job);
        try {
            respond(socket, 200, result.get(2500, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            main.removeCallbacks(job);
            respond(socket, 503, "Phone busy; request expired");
        }
    }

    private void serveWebSocket(Socket socket, InputStream in, OutputStream out, boolean preAuth) throws Exception {
        socket.setSoTimeout(30000);
        boolean authenticated = preAuth;
        while (!closed && !socket.isClosed()) {
            int b1 = in.read();
            if (b1 < 0) break;
            int b2 = in.read();
            if (b2 < 0) break;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            int len = b2 & 0x7F;
            if (len == 126) {
                int h = in.read(); int l = in.read();
                if (h < 0 || l < 0) break;
                len = (h << 8) | l;
            } else if (len == 127) {
                break;
            }
            byte[] mask = new byte[4];
            if (masked) {
                int r = 0;
                while (r < 4) {
                    int n = in.read(mask, r, 4 - r);
                    if (n < 0) return;
                    r += n;
                }
            }
            byte[] payload = new byte[len];
            int r = 0;
            while (r < len) {
                int n = in.read(payload, r, len - r);
                if (n < 0) return;
                r += n;
            }
            if (masked) {
                for (int i = 0; i < len; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            if (opcode == 0x8) {
                sendWsFrame(out, 0x8, new byte[0]);
                break;
            }
            if (opcode == 0x9) {
                sendWsFrame(out, 0xA, payload);
                continue;
            }
            if (opcode == 0x1) {
                String text = new String(payload, StandardCharsets.UTF_8).trim();
                org.json.JSONObject msg;
                try {
                    msg = new org.json.JSONObject(text);
                } catch (Exception e) {
                    sendWsResponse(out, "Bad request");
                    continue;
                }
                String token = msg.optString("token", "");
                if (!authenticated && !authorized("Bearer " + token, token(context))) {
                    sendWsResponse(out, "Pairing required");
                    continue;
                }
                authenticated = true;
                String action = msg.optString("action", msg.optString("path", ""));
                if (!action.startsWith("/")) action = "/" + action;
                if (!action.equals("/status") && !action.equals("/find") && !action.equals("/stop") && !action.equals("/talk") && !action.equals("/ping")) {
                    sendWsResponse(out, "Unsupported request");
                    continue;
                }
                final String path = action;
                CompletableFuture<String> result = new CompletableFuture<>();
                main.post(() -> {
                    if (closed || !enabled(context)) {
                        result.complete("Bridge disabled");
                        return;
                    }
                    try {
                        result.complete(actions.run(path));
                    } catch (Exception e) {
                        result.complete("Action unavailable");
                    }
                });
                try {
                    sendWsResponse(out, result.get(2500, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    sendWsResponse(out, "Phone busy; request expired");
                }
            }
        }
    }

    private static void sendWsResponse(OutputStream out, String message) throws Exception {
        byte[] body = new org.json.JSONObject().put("message", message).toString().getBytes(StandardCharsets.UTF_8);
        sendWsFrame(out, 0x1, body);
    }

    private static void sendWsFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        out.write(0x80 | (opcode & 0x0F));
        int len = payload.length;
        if (len < 126) {
            out.write(len);
        } else {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(payload);
        out.flush();
    }

    private void respond(Socket socket, int code, String message) throws Exception {
        byte[] body = new org.json.JSONObject().put("message", message).toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String reason = code == 200 ? "OK" : (code == 400 ? "Bad Request" : (code == 403 ? "Forbidden" : (code == 408 ? "Request Timeout" : (code == 429 ? "Too Many Requests" : "Error"))));
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, X-IRIS-Sent-At, Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    public void close() {
        closed = true;
        state = "Disabled";
        main.removeCallbacksAndMessages(null);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        if (gattBridge != null) {
            try { gattBridge.close(); } catch (Exception ignored) { }
            gattBridge = null;
        }
    }
}
