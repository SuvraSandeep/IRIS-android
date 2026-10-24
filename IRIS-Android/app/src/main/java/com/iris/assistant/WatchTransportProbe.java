package com.iris.assistant;
import android.app.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Checks the actual authenticated loopback server without ringing or starting the microphone. */
final class WatchTransportProbe {
    static void run(Activity a) {
        if (a == null || a.isFinishing()) return;
        AlertDialog d = new AlertDialog.Builder(a)
            .setTitle("Testing phone bridge")
            .setMessage("Checking local connection on port 18473…")
            .setPositiveButton("Close", null)
            .show();

        WatchBridge.startShared(a);
        new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}

            String result;
            try {
                String token = WatchBridge.token(a);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", WatchBridge.PORT), 3000);
                    socket.setSoTimeout(4000);
                    socket.getOutputStream().write(("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "
                        + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String status = reader.readLine();
                    if (status != null && status.startsWith("HTTP/1.1 200 ")) {
                        result = "🟢 Phone bridge is ONLINE and responding!\n\nPort 18473 is listening on loopback.\n\nNext steps:\n1. Open Zepp App settings and tap 'PING PHONE BRIDGE'.\n2. Open IRIS Remote on your T-Rex 3 watch!";
                    } else if (status != null && status.contains("403")) {
                        result = "🔴 Bridge rejected pairing key.\n\nCopy the key from IRIS Settings → Amazfit T-Rex 3 Bridge and paste into Zepp app settings.";
                    } else {
                        result = "🔴 Bridge response error: " + status;
                    }
                }
            } catch (Exception e) {
                result = "🔴 Phone bridge port 18473 could not connect.\n\nMake sure the bridge is enabled in Settings, then tap Test again.";
            }
            final String report = result;
            a.runOnUiThread(() -> {
                try {
                    if (!a.isFinishing() && !a.isDestroyed() && d.isShowing()) {
                        d.setMessage(report);
                    }
                } catch (Throwable ignored) {}
            });
        }, "IRIS-BridgeProbe").start();
    }
}
