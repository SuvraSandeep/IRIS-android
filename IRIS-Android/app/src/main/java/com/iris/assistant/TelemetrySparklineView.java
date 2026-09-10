package com.iris.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 60-second traffic sparkline with a fixed-size sample buffer.
 *
 * A zero-traffic period draws a flat line at the baseline (not an empty chart), and an
 * unavailable counter draws nothing but says so — it never implies "0".
 */
public final class TelemetrySparklineView extends View {

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private long[] samples = new long[0];
    private long peak = 0;
    private boolean available = true;
    private int accent = 0xFF35D9F4;

    public TelemetrySparklineView(Context c) { this(c, null); }

    public TelemetrySparklineView(Context c, AttributeSet a) {
        super(c, a);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(0x2219343D);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.8f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(accent);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((accent & 0x00FFFFFF) | 0x22000000);
        label.setColor(0xFF93ABB5);
        label.setTextSize(dp(9f));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Tint to match the user's chosen accent. */
    public void setAccent(int color) {
        accent = color;
        line.setColor(color);
        fill.setColor((color & 0x00FFFFFF) | 0x22000000);
        invalidate();
    }

    /** Push the newest history from a meter. */
    public void update(TrafficRateMeter meter) {
        if (meter == null) return;
        available = meter.supported();
        samples = meter.history();
        peak = Math.max(1, meter.peak());
        invalidate();
    }

    public void showUnavailable() {
        available = false;
        samples = new long[0];
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = dp(2f);
        float top = pad, bottom = h - pad - dp(10f);   // leave room for the axis caption
        // Faint technical grid.
        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(0, y, w, y, grid);
        }
        if (!available) {
            label.setColor(0xFF93ABB5);
            canvas.drawText("traffic counters not available", pad, bottom - dp(2f), label);
            return;
        }
        if (samples.length < 2) {
            canvas.drawText("measuring\u2026", pad, bottom - dp(2f), label);
            drawAxis(canvas, w, h);
            return;
        }
        // Build the line. A flat zero series sits on the baseline and is still visible.
        path.reset();
        float stepX = w / (float) Math.max(1, samples.length - 1);
        for (int i = 0; i < samples.length; i++) {
            float x = i * stepX;
            float ratio = Math.max(0f, Math.min(1f, samples[i] / (float) peak));
            float y = bottom - (bottom - top) * ratio;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        // Soft area under the curve.
        Path area = new Path(path);
        area.lineTo((samples.length - 1) * stepX, bottom);
        area.lineTo(0, bottom);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
        drawAxis(canvas, w, h);
    }

    private void drawAxis(Canvas canvas, float w, float h) {
        label.setColor(0xFF6B818C);
        canvas.drawText("60s ago", 0, h - dp(1f), label);
        String now = "Now";
        float tw = label.measureText(now);
        canvas.drawText(now, w - tw, h - dp(1f), label);
        String peakLabel = TelemetrySnapshot.rate(peak);
        if (!peakLabel.isEmpty()) canvas.drawText(peakLabel, w / 2f - label.measureText(peakLabel) / 2f,
                h - dp(1f), label);
    }
}
