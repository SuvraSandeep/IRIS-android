package com.iris.assistant;

/**
 * Command Deck telemetry regression checks.
 * These exist to guarantee the dashboard never lies: unknown is not "off", and
 * unavailable traffic is not "0".
 */
public final class TelemetryTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static void metricRules() {
        // A real value displays with its unit.
        TelemetrySnapshot.Metric ok = TelemetrySnapshot.Metric.of("battery", "78", "%", "BatteryManager", 100);
        check(ok.isAvailable(), "value available");
        check(ok.display().equals("78 %"), "value display: " + ok.display());

        // Empty value must NOT be treated as available (this is the "unknown shown as off" bug).
        TelemetrySnapshot.Metric blank = TelemetrySnapshot.Metric.of("ssid", "", "", "WifiInfo", 100);
        check(!blank.isAvailable(), "blank not available");
        check(blank.display().equals("Not available"), "blank display: " + blank.display());

        // Permission and unsupported are distinct, and neither invents a number.
        check(TelemetrySnapshot.Metric.permissionRequired("ssid", "WifiInfo").display()
                .equals("Permission required"), "permission display");
        check(TelemetrySnapshot.Metric.unsupported("cpu_temp", "none").display()
                .equals("Not available"), "unsupported display");
        check(!TelemetrySnapshot.Metric.permissionRequired("x", "y").display().contains("0"), "no fake zero");

        // Stale keeps the value but flags it.
        TelemetrySnapshot.Metric stale = ok.stale();
        check(!stale.isAvailable(), "stale not available");
        check(stale.display().contains("stale"), "stale marked: " + stale.display());
    }

    private static void snapshotRules() {
        TelemetrySnapshot s = TelemetrySnapshot.builder(1000)
                .value("battery", "78", "%", "BatteryManager", 1000)
                .permission("wifi_ssid", "WifiInfo")
                .build();
        check(s.size() == 2, "snapshot size");
        check(s.display("battery").equals("78 %"), "snapshot value");
        check(s.display("wifi_ssid").equals("Permission required"), "snapshot permission");
        // An absent key must be reported, never silently zero.
        check(s.display("nope").equals("Not available"), "absent key");
        check(!s.get("nope").isAvailable(), "absent not available");

        // Ageing marks old readings stale so a frozen collector can't look live.
        TelemetrySnapshot aged = s.agedAt(1000 + 60_000, 5_000);
        check(aged.get("battery").availability == TelemetrySnapshot.Availability.STALE, "aged stale");
        // Permission entries stay permission entries.
        check(aged.get("wifi_ssid").availability == TelemetrySnapshot.Availability.PERMISSION_REQUIRED,
                "aged keeps permission");
        // A fresh snapshot is untouched.
        check(s.agedAt(1100, 5_000).get("battery").isAvailable(), "fresh stays available");

        check(TelemetrySnapshot.empty().display("anything").equals("Not available"), "empty snapshot");
        check(s.freshness(1000).equals("live"), "freshness live");
        check(s.freshness(1000 + 30_000).equals("30s ago"), "freshness seconds");
        check(TelemetrySnapshot.empty().freshness(5000).equals("no data"), "freshness none");
    }

    private static void trafficRules() {
        TrafficRateMeter m = new TrafficRateMeter(60);

        // First sample establishes a baseline: rate is UNKNOWN, not zero.
        check(m.sample(1000, 0) == -1, "first sample unknown");
        check(!m.hasRate(), "no rate yet");
        check(m.displayRate().equals("measuring…"), "measuring text: " + m.displayRate());

        // 1024 bytes over 1s = 1024 B/s.
        check(m.sample(2024, 1000) == 1024, "rate computed");
        check(m.hasRate(), "has rate");

        // Zero traffic must read as a real 0 (flat line), not "unknown".
        check(m.sample(2024, 2000) == 0, "zero traffic is zero");
        check(m.sampleCount() == 2, "samples recorded: " + m.sampleCount());

        // A counter that goes backwards = reset. Must never produce negative traffic.
        check(m.sample(500, 3000) == -1, "counter reset -> unknown");
        check(m.rate() < 0, "no negative rate");

        // UNSUPPORTED counters report unavailable, not 0.
        TrafficRateMeter u = new TrafficRateMeter();
        check(u.sample(TrafficRateMeter.UNSUPPORTED, 0) == -1, "unsupported unknown");
        check(!u.supported(), "marked unsupported");
        check(u.displayRate().equals("Not available"), "unsupported display: " + u.displayRate());

        // Session totals and history bounds.
        TrafficRateMeter s = new TrafficRateMeter(3);
        s.sample(0, 0); s.sample(100, 1000); s.sample(300, 2000); s.sample(600, 3000); s.sample(1000, 4000);
        check(s.sessionBytes() == 1000, "session bytes: " + s.sessionBytes());
        check(s.history().length == 3, "history bounded: " + s.history().length);
        check(s.peak() >= 300, "peak tracked: " + s.peak());

        // Flat zero traffic still yields samples so the sparkline draws a line.
        TrafficRateMeter z = new TrafficRateMeter();
        z.sample(50, 0); z.sample(50, 1000); z.sample(50, 2000);
        check(z.sampleCount() == 2 && z.peak() == 0, "flat line renders");

        s.reset();
        check(s.sampleCount() == 0 && !s.hasRate(), "reset clears");
    }

    private static void eventRules() {
        TelemetryEventLog log = new TelemetryEventLog(200);
        check(log.add(TelemetryEventLog.Category.NET, "Default connection changed to Wi-Fi"), "first event");
        // Unchanged reading must not create a new line.
        check(!log.add(TelemetryEventLog.Category.NET, "Default connection changed to Wi-Fi"), "dedupe same");
        check(log.size() == 1, "dedupe keeps one: " + log.size());
        // A different message in the same category is a real change.
        check(log.add(TelemetryEventLog.Category.NET, "Default connection changed to cellular"), "changed event");
        // Same text in a different category is still meaningful.
        check(log.add(TelemetryEventLog.Category.WAKE, "Default connection changed to cellular"), "other category");
        check(!log.add(TelemetryEventLog.Category.NET, ""), "empty rejected");
        check(!log.add(null, "x"), "null category rejected");

        // Bounded: oldest dropped.
        TelemetryEventLog small = new TelemetryEventLog(3);
        for (int i = 0; i < 10; i++) small.add(TelemetryEventLog.Category.SYS, "event " + i);
        check(small.size() == 3, "bounded: " + small.size());
        check(small.recent(10).get(0).message.equals("event 9"), "newest first");

        // Filtering.
        check(log.recent(10, TelemetryEventLog.Category.WAKE).size() == 1, "filter wake");
        check(log.recent(10, TelemetryEventLog.Category.BT).isEmpty(), "filter empty");

        // Rendered line format: "HH:mm:ss  NET    message"
        String line = log.recent(10, TelemetryEventLog.Category.WAKE).get(0).line();
        check(line.matches("^\\d{2}:\\d{2}:\\d{2}\\s+WAKE\\s+.*"), "line format: " + line);
        check(TelemetryEventLog.Category.NET.tag.equals("NET"), "tag");

        log.clear();
        check(log.size() == 0, "clear");
        check(log.render(10, null).equals("No events yet."), "empty render");
    }

    private static void formatRules() {
        check(TelemetrySnapshot.rate(512).equals("512 B/s"), "B/s");
        check(TelemetrySnapshot.rate(2048).equals("2.0 KB/s"), "KB/s");
        check(TelemetrySnapshot.rate(0).equals("0 B/s"), "zero rate prints");
        check(TelemetrySnapshot.rate(-5).isEmpty(), "negative rate blank");
        check(TelemetrySnapshot.bytes(1536).equals("1.5 KB"), "bytes");
        check(TelemetrySnapshot.duration(90_000).equals("1m"), "duration minutes");
        check(TelemetrySnapshot.duration(3_600_000).equals("1h 0m"), "duration hours");
        check(TelemetrySnapshot.duration(90_000_000).equals("1d 1h"), "duration days");
    }

    public static void main(String[] args) {
        metricRules();
        snapshotRules();
        trafficRules();
        eventRules();
        formatRules();
        System.out.println("Passed " + checks + " telemetry checks.");
    }
}
