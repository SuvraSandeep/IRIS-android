package com.iris.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Tracks internet availability + server health so IRIS can decide, per turn, whether to use
 * the online server or fall back to fully offline. Includes a circuit breaker and a
 * "too slow → go offline" guard. See SERVER-MODE-IMPLEMENTATION.md.
 */
final class ConnectivityMonitor {
    private volatile boolean online = true;
    private int consecutiveFailures;
    private long circuitOpenUntil;
    private long lastLatencyMs;

    /** Register a default-network callback so online state flips instantly on connect/drop. */
    void register(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            Network active = cm.getActiveNetwork();
            NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
            online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { online = true; }
                @Override public void onLost(Network network) { online = false; }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
            });
        } catch (Throwable ignored) { }
    }

    /** True only when server mode is on, configured, online, breaker closed, and not "too slow". */
    boolean shouldUseServer(AppSettings s) {
        if (s == null || !s.serverModeEnabled() || s.serverUrl().isEmpty()) return false;
        if (!online) return false;
        if (System.currentTimeMillis() < circuitOpenUntil) return false;
        if (s.autoOfflineWhenSlow() && lastLatencyMs > s.serverSlowMs()) return false;
        return true;
    }

    void recordSuccess(long ms) { consecutiveFailures = 0; lastLatencyMs = ms; }

    void recordFailure() {
        if (++consecutiveFailures >= 3) circuitOpenUntil = System.currentTimeMillis() + 60_000L; // 60s cooldown
    }

    boolean isOnline() { return online; }
}
