package com.iris.assistant;

/** Battery discharge-rate estimator regression checks: no rate from too little history,
 *  no blending charging into discharging, and the rate math itself is correct. */
public final class BatteryRateTrackerTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static void noHistory() {
        BatteryRateTracker t = new BatteryRateTracker();
        check(!t.estimate().available, "no samples -> unavailable");
        t.record(80, false, 0L);
        check(!t.estimate().available, "single sample -> unavailable");
    }

    private static void tooShortSpan() {
        BatteryRateTracker t = new BatteryRateTracker();
        t.record(80, false, 0L);
        t.record(79, false, 10_000L);   // only 10s apart, below the 90s minimum
        check(!t.estimate().available, "short span -> not yet a rate");
    }

    private static void discharging() {
        BatteryRateTracker t = new BatteryRateTracker();
        t.record(80, false, 0L);
        t.record(70, false, 3_600_000L);   // exactly 1 hour, -10%
        BatteryRateTracker.Estimate e = t.estimate();
        check(e.available, "discharge rate available");
        check(!e.charging, "reported as discharging");
        check(Math.abs(e.percentPerHour - 10.0) < 0.001, "rate is 10%/hour: " + e.percentPerHour);
        check(BatteryRateTracker.describe(e).toLowerCase(java.util.Locale.ROOT).contains("draining"),
                "describe says draining: " + BatteryRateTracker.describe(e));
    }

    private static void charging() {
        BatteryRateTracker t = new BatteryRateTracker();
        t.record(50, true, 0L);
        t.record(70, true, 3_600_000L);   // +20% in 1 hour while charging
        BatteryRateTracker.Estimate e = t.estimate();
        check(e.available, "charge rate available");
        check(e.charging, "reported as charging");
        check(Math.abs(e.percentPerHour - 20.0) < 0.001, "rate is 20%/hour: " + e.percentPerHour);
        check(BatteryRateTracker.describe(e).toLowerCase(java.util.Locale.ROOT).contains("charging"),
                "describe says charging: " + BatteryRateTracker.describe(e));
    }

    private static void chargeStateFlipResetsWindow() {
        BatteryRateTracker t = new BatteryRateTracker();
        t.record(50, false, 0L);
        t.record(45, false, 3_600_000L);   // was discharging: -5%/hour
        // Plug in — must not blend the discharging history into a charging rate.
        t.record(46, true, 3_600_100L);
        check(!t.estimate().available, "charging-state flip resets the window (needs new history)");
    }

    private static void rateNeverNegative() {
        BatteryRateTracker t = new BatteryRateTracker();
        t.record(90, false, 0L);
        t.record(80, false, 3_600_000L);
        check(t.estimate().percentPerHour >= 0, "percentPerHour is never negative (direction is the charging flag)");
    }

    private static void unavailableDescribesAsMeasuring() {
        String d = BatteryRateTracker.describe(new BatteryRateTracker().estimate());
        check(d.toLowerCase(java.util.Locale.ROOT).contains("measuring"), "unavailable reads as measuring: " + d);
    }

    public static void main(String[] args) {
        noHistory();
        tooShortSpan();
        discharging();
        charging();
        chargeStateFlipResetsWindow();
        rateNeverNegative();
        unavailableDescribesAsMeasuring();
        System.out.println("Passed " + checks + " battery-rate checks.");
    }
}
