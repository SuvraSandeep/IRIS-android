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
    private long generation;
    private final java.util.concurrent.ExecutorService collector = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "IRIS-Telemetry"); t.setDaemon(true); return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean collecting = new java.util.concurrent.atomic.AtomicBoolean();
    private long slowCounter;
    private volatile TelemetrySnapshot latest = TelemetrySnapshot.empty();
    private long serviceStartElapsed;
    private TelemetrySnapshot slowSnapshot = TelemetrySnapshot.empty();

    private final Runnable fastTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
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
        serviceStartElapsed = IrisListeningService.serviceStartedAt;
        network.rxMeter().reset(); network.txMeter().reset(); network.sampleTraffic();
        network.start();
        events.add(TelemetryEventLog.Category.SYS, "Command deck telemetry started");
        rebuild(true);
        main.postDelayed(fastTick, FAST_TICK_MS);
    }

    /** Called when the deck is hidden or the screen turns off — no background polling. */
    public void stop() {
        if (!running) return;
        running = false;
        generation++;
        main.removeCallbacks(fastTick);
        network.stop();
    }

    public void close() { stop(); listener = null; collector.shutdownNow(); }

    public boolean isRunning() { return running; }

    /** Rebuild the snapshot. {@code full} also refreshes the slower/expensive sources.
     *  {@code oneShot} allows a single collection to run even when continuous polling
     *  (start()/stop()) is not active -- see refreshNow()'s doc for why this distinction
     *  matters: a fresh, never-started controller (e.g. IrisListeningService's per-question
     *  phone-facts probe) previously could never collect at all, silently answering from an
     *  empty TelemetrySnapshot forever, since this guard treated "not currently polling" as
     *  "do nothing" unconditionally. */
    private void rebuild(boolean full) { rebuild(full, false); }
    private void rebuild(boolean full, boolean oneShot) {
        if ((!running && !oneShot) || !collecting.compareAndSet(false, true)) return;
        final long token = generation;
        collector.execute(() -> {
            try {
                network.sampleTraffic();
                TelemetrySnapshot snapshot = collect(full);
                main.post(() -> {
                    if ((!running && !oneShot) || token != generation) return;
                    latest = snapshot;
                    events.add(TelemetryEventLog.Category.WAKE, snapshot.display("wake_ready"));
                    events.add(TelemetryEventLog.Category.SENSOR, snapshot.display("active_sensors"));
                    if (listener != null) listener.onTelemetry(snapshot);
                    if (onOneShotComplete != null) { Runnable cb = onOneShotComplete; onOneShotComplete = null; cb.run(); }
                });
            } finally { collecting.set(false); }
        });
    }
    private volatile Runnable onOneShotComplete;

    private TelemetrySnapshot collect(boolean full) {
        TelemetrySnapshot.Builder b = TelemetrySnapshot.builder(SystemClock.elapsedRealtime());
        try {
            network.contribute(b, settings.telemetryPublicIp());
        } catch (Throwable t) {
            LogStore.append(ctx, "TELEMETRY", "network collector failed: " + t);
        }
        if (full) {
            TelemetrySnapshot.Builder slow = TelemetrySnapshot.builder(SystemClock.elapsedRealtime());
            try {
                bluetooth.contribute(slow);
            } catch (Throwable t) {
                LogStore.append(ctx, "TELEMETRY", "bluetooth collector failed: " + t);
            }
            try {
                resources.contribute(slow, IrisListeningService.isRunning ? IrisListeningService.serviceStartedAt : 0);
            } catch (Throwable t) {
                LogStore.append(ctx, "TELEMETRY", "resources collector failed: " + t);
            }
            try {
                PhoneDetailsCollector.contribute(ctx, slow);
            } catch (Throwable t) {
                LogStore.append(ctx, "TELEMETRY", "phone details collector failed: " + t);
            }
            addIrisState(slow);
            try {
                slowSnapshot = slow.build();
            } catch (Throwable t) {
                LogStore.append(ctx, "TELEMETRY", "slow snapshot build failed: " + t);
            }
        }
        try {
            for (TelemetrySnapshot.Metric m : slowSnapshot.all().values()) b.put(m);
        } catch (Throwable t) {
            LogStore.append(ctx, "TELEMETRY", "merging slow snapshot failed: " + t);
        }
        return b.build().agedAt(SystemClock.elapsedRealtime(), STALE_AFTER_MS);
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
            b.value("wake_ready", IrisListeningService.isRunning ? IrisListeningService.wakeReadiness : "Service stopped", "", "IRIS service", now);
            // Never claim the owner is verified before a real match.
            b.value("owner_check", wake.isVoiceEnrolled() ? "Voice enrolled; not proof of a current match" : "Not enrolled",
                    "", "ProfileStore", now);
        } catch (Throwable t) {
            b.unsupported("wake_ready", "ProfileStore");
        }
        for (IrisSensorUsageRegistry.Hardware hw : IrisSensorUsageRegistry.Hardware.values()) {
            b.value("hw_" + hw.name().toLowerCase(java.util.Locale.ROOT),
                    IrisSensorUsageRegistry.status(hw), "", "IrisSensorUsageRegistry", now);
        }
    }

    /** Force a full refresh (e.g. the user opened a panel). Fire-and-forget: the caller must
     *  read latest() from the listener callback or a subsequent event, not immediately after
     *  this call returns, since collection always happens on a background thread. */
    public void refreshNow() { rebuild(true, true); }

    /** One-shot refresh that reports back via {@code onComplete} once the snapshot is ready,
     *  for callers that need to read latest() right after refreshing (e.g. a phone-facts voice
     *  answer) rather than merely re-rendering a listener-driven view. Works even when
     *  start()/stop() polling was never engaged -- see rebuild(boolean,boolean)'s doc for the
     *  real bug this fixes: a fresh, never-started controller previously could never collect
     *  at all via refreshNow(), so latest() stayed TelemetrySnapshot.empty() forever.
     *  If a collection is already in flight, onComplete still runs (on the current latest()),
     *  rather than being silently dropped, since compareAndSet would otherwise reject this
     *  request outright with no signal to the caller. */
    public void refreshNow(Runnable onComplete) {
        if (onComplete == null) { refreshNow(); return; }
        if (!collecting.compareAndSet(false, true)) {
            // A collection is already in flight; it will report to whichever caller started
            // it, not to us. Still notify this caller rather than leaving it silently
            // unanswered -- see this method's doc for why that matters.
            main.post(onComplete);
            return;
        }
        collecting.set(false); // release the probe CAS above; rebuild(...) takes it again itself
        onOneShotComplete = onComplete;
        rebuild(true, true);
    }
}

