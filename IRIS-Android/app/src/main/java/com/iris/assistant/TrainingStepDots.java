package com.iris.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Draws one dot per training take: filled mint = done, ringed cyan = current, dim = pending.
 *  Purely decorative progress feedback alongside the existing numeric "N / total" text — no
 *  state of its own, just a visual read of (completed, total, currentIsRetry). */
public final class TrainingStepDots extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int completed, total;
    private boolean currentNeedsRetry;

    public TrainingStepDots(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void update(int completed, int total, boolean currentNeedsRetry) {
        this.completed = Math.max(0, completed);
        this.total = Math.max(1, total);
        this.currentNeedsRetry = currentNeedsRetry;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (total <= 0) return;
        float w = getWidth(), h = getHeight();
        float gap = Math.min(14f, w / (total * 2f));
        float slot = w / total;
        float radius = Math.min(h / 2.6f, (slot - gap) / 2f);
        float cy = h / 2f;
        for (int i = 0; i < total; i++) {
            float cx = slot * i + slot / 2f;
            if (i < completed) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(52, 211, 153)); // positive / mint
                canvas.drawCircle(cx, cy, radius, paint);
            } else if (i == completed) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, radius / 2.5f));
                paint.setColor(currentNeedsRetry ? Color.rgb(251, 191, 36) : Color.rgb(34, 211, 238)); // warning / cyan
                canvas.drawCircle(cx, cy, radius, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(90, 100, 116, 139)); // dim text_muted
                canvas.drawCircle(cx, cy, radius * .7f, paint);
            }
        }
    }
}
