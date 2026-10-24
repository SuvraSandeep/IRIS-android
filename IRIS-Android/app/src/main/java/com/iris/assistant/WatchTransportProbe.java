package com.iris.assistant;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Checks the actual authenticated loopback server without ringing or starting the microphone. */
final class WatchTransportProbe {
    static void run(Activity a) {
        AlertDialog d = new AlertDialog.Builder(a)
            .setTitle("Testing phone bridge")
            .setMessage("Checking local connection…")
            .setPositiveButton("Close", null)
            .show();

        new Thread(() -> {
            String result;
            if (!IrisListeningService.isRunning) {
                if (a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        a.startForegroundService(new Intent(a, IrisListeningService.class).setAction(IrisListeningService.ACTION_START));
                        Thread.sleep(850);
                    } catch (Exception ignored) {}
                }
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", WatchBridge.PORT), 2500);
                socket.setSoTimeout(4000);
                socket.getOutputStream().write(("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "
                    + WatchBridge.token(a) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                if (status != null && status.startsWith("HTTP/1.1 200 ")) {
                    result = "🟢 Phone bridge is ONLINE and responding!\n\nPort 18473 is listening on loopback.\n\nNext steps:\n1. Open Zepp App settings and tap 'Ping Phone Bridge'.\n2. Open IRIS Remote on your T-Rex 3 watch!";
                } else {
                    result = "🔴 Bridge rejected this pairing code.\n\nGenerate a new code in IRIS Settings and update it in Zepp app.";
                }
            } catch (Exception e) {
                if (!IrisListeningService.isRunning) {
                    result = "🔴 IRIS Service is stopped.\n\nThe bridge runs while IRIS is active. Please start IRIS on the Home tab, then retry.";
                } else {
                    result = "🔴 Phone bridge port 18473 could not connect.\n\nMake sure the bridge is enabled in Settings, then retry.";
                }
            }
            final String report = result;
            a.runOnUiThread(() -> {
                if (d.isShowing() && !a.isFinishing()) d.setMessage(report);
            });
        }, "IRIS-BridgeProbe").start();
    }
}
