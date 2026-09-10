package com.iris.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Starts/stops the collectors and publishes immutable snapshots.
 *
 * Update schedule (from the Command Deck spec):
 *   network + bluetooth : callback-driven
 *   traffic rates       : 1s while visible
 *   memory              : 3–5s
 *   battery             : broadcast-driven (read on refresh)
 *   storage             : on panel open / occasional
 *
 * Polling and animation stop when the page is hidden or the screen is off. Voice-service
 * operation is entirely independent of this class.
 */
public final class SystemTelemetryController {

    public interface Listener {
        void onTelemetry(TelemetrySnapshot snapshot);
    }

    /** How long a reading may age before it is marked STALE. */
    private static final long STALE_AFTER_MS = 15_000;
    private static final long FAST_TICK_MS = 1000;      // traffic
    private static final long SLOW_TICK_MS = 4000;      // memory/battery/etc.

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TelemetryEventLog events;
    private final NetworkTelemetryCollector network;
    private final BluetoothTelemetryCollector bluetooth;
    private final ResourceTelemetryCollector resources;
    private final AppSettings settings;

    private Listener listener;
    private boolean running;
    private long slowCounter;
    private volatile TelemetrySnapshot latest = TelemetrySnapshot.empty();
    private long serviceStartElapsed;

    private final Runnable fastTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            network.sampleTraffic();
            slowCounter += FAST_TICK_MS;
            if (slowCounter >= SLOW_TICK_MS) { slowCounter = 0; rebuild(true); }
            else rebuild(false);
            main.postDelayed(this, FAST_TICK_MS);
        }
    };

    public SystemTelemetryController(Context context) {
        this.ctx = context.getApplicationContext();
        this.settings = new AppSettings(this.ctx);
        this.events = new TelemetryEventLog(TelemetryEventLog.DEFAULT_CAPACITY);
        this.network = new NetworkTelemetryCollector(this.ctx, events);
        this.bluetooth = new BluetoothTelemetryCollector(this.ctx, events);
        this.resources = new ResourceTelemetryCollector(this.ctx, events);
    }

    public TelemetryEventLog events() { return events; }
    public TelemetrySnapshot latest() { return latest; }
    public NetworkTelemetryCollector networkCollector() { return network; }
    public BluetoothTelemetryCollector bluetoothCollector() { return bluetooth; }

    public void setListener(Listener l) { this.listener = l; }

    /** Note when the voice service came up, for the service-uptime row. */
    public void markServiceStart(long elapsed) { this.serviceStartElapsed = elapsed; }

    /** Called when the deck becomes visible. */
    public void start() {
        if (running) return;
        running = true;
        serviceStartElapsed = IrisListeningService.isRunning && serviceStartElapsed == 0
                ? SystemClock.elapsedRealtime() : serviceStartElapsed;
        network.start();
        events.add(TelemetryEventLog.Category.SYS, "Command deck telemetry started");
        rebuild(true);
        main.postDelayed(fastTick, FAST_TICK_MS);
    }

    /** Called when the deck is hidden or the screen turns off — no background polling. */
    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(fastTick);
        network.stop();
    }

    public boolean isRunning() { return running; }

    /** Rebuild the snapshot. {@code full} also refreshes the slower/expensive sources. */
    private void rebuild(boolean full) {
        try {
            TelemetrySnapshot.Builder b = TelemetrySnapshot.builder(SystemClock.elapsedRealtime());
            network.contribute(b, settings.telemetryPublicIp());
            if (full) {
                bluetooth.contribute(b);
                resources.contribute(b, serviceStartElapsed);
            } else {
                // Carry forward the slower values rather than dropping them to "unavailable".
                for (TelemetrySnapshot.Metric m : latest.all().values()) {
                    if (m.key.startsWith("bt_") || m.key.startsWith("audio_")
                            || m.key.equals(ResourceTelemetryCollector.K_BATTERY)
                            || m.key.equals(ResourceTelemetryCollector.K_CHARGING)
                            || m.key.equals(ResourceTelemetryCollector.K_RAM_FREE)
                            || m.key.equals(ResourceTelemetryCollector.K_RAM_IRIS)
                            || m.key.equals(ResourceTelemetryCollector.K_STORAGE_FREE)
                            || m.key.equals(ResourceTelemetryCollector.K_DEVICE)
                            || m.key.equals(ResourceTelemetryCollector.K_ANDROID)
                            || m.key.equals(ResourceTelemetryCollector.K_APP_VERSION)
                            || m.key.startsWith("battery_")
                            || m.key.equals(ResourceTelemetryCollector.K_THERMAL)
                            || m.key.equals(ResourceTelemetryCollector.K_POWER_SAVE)) {
                        b.put(m);
                    }
                }
            }
            addIrisState(b);
            // Age it so a frozen collector cannot keep looking live.
            latest = b.build().agedAt(SystemClock.elapsedRealtime(), STALE_AFTER_MS);
            if (listener != null) listener.onTelemetry(latest);
        } catch (Throwable ignored) { }
    }

    /** IRIS's own state: wake readiness, engine, and what hardware IRIS is really using. */
    private void addIrisState(TelemetrySnapshot.Builder b) {
        long now = SystemClock.elapsedRealtime();
        b.value("iris_service", IrisListeningService.isRunning ? "Running" : "Stopped", "", "IRIS", now);
        String mic = IrisListeningService.currentMic;
        if (mic != null && !mic.isEmpty()) b.value("iris_mic", mic, "", "IRIS", now);
        else b.unsupported("iris_mic", "IRIS");
        try {
            ProfileStore.WakeProfile wake = new ProfileStore(ctx).getWakeProfile();
            b.value("wake_phrase", wake.phrase == null || wake.phrase.isEmpty() ? "not set" : wake.phrase,
                    "", "ProfileStore", now);
            b.value("wake_ready", wake.isReady() ? "Ready" : "Needs setup", "", "ProfileStore", now);
            // Never claim the owner is verified before a real match.
            b.value("owner_check", wake.isVoiceEnrolled() ? "Owner check ready" : "Not enrolled",
                    "", "ProfileStore", now);
        } catch (Throwable t) {
            b.unsupported("wake_ready", "ProfileStore");
        }
        for (IrisSensorUsageRegistry.Hardware hw : IrisSensorUsageRegistry.Hardware.values()) {
            b.value("hw_" + hw.name().toLowerCase(java.util.Locale.ROOT),
                    IrisSensorUsageRegistry.status(hw), "", "IrisSensorUsageRegistry", now);
        }
    }

    /** Force a full refresh (e.g. the user opened a panel). */
    public void refreshNow() { if (running) rebuild(true); else rebuild(true); }
}
