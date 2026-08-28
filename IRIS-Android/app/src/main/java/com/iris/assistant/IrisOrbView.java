package com.iris.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

public class IrisOrbView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pulse = 0f;
    private boolean active = false;
    private String phase = "off";
    private float voiceLevel = 0f;
    private Bitmap contactImage;
    private RadialGradient haloShader;
    private RadialGradient sphereShader;
    private int cachedWidth;
    private int cachedHeight;
    private String cachedPhase = "";
    private ValueAnimator pulseAnimator;

    public IrisOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1800);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(valueAnimator -> {
            pulse = (float) valueAnimator.getAnimatedValue();
            if (active) invalidate();
        });
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            phase = "off";
            if (pulseAnimator != null && pulseAnimator.isStarted()) pulseAnimator.pause();
        } else {
            if (pulseAnimator != null) {
                if (!pulseAnimator.isStarted()) pulseAnimator.start();
                else if (pulseAnimator.isPaused()) pulseAnimator.resume();
            }
        }
        setContentDescription(active ? "Turn IRIS off" : "Turn IRIS on");
        invalidate();
    }

    public void setPhase(String phase) {
        this.phase = phase == null ? "off" : phase;
        this.active = !"off".equals(this.phase);
        if (!"confirm".equals(this.phase)) contactImage = null;
        if (active && pulseAnimator != null) {
            if (!pulseAnimator.isStarted()) pulseAnimator.start();
            else if (pulseAnimator.isPaused()) pulseAnimator.resume();
        } else if (!active && pulseAnimator != null && pulseAnimator.isStarted()) {
            pulseAnimator.pause();
        }
        String description;
        switch (this.phase) {
            case "wake": description = "IRIS armed, waiting for wake phrase. Tap to stop."; break;
            case "command": description = "IRIS listening for a call command. Tap to stop."; break;
            case "confirm": description = "IRIS waiting for call confirmation. Tap to stop."; break;
            case "thinking": description = "IRIS is processing. Please wait."; break;
            default: description = "IRIS is off. Tap to start."; break;
        }
        setContentDescription(description);
        invalidate();
    }

    public void setContactImage(Bitmap bitmap) {
        contactImage = bitmap;
        invalidate();
    }

    public void setVoiceLevel(float level) {
        voiceLevel = Math.max(0f, Math.min(1f, level));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) * 0.31f;

        if (active) {
            float halo = base * (1.45f + pulse * 0.18f + voiceLevel * .22f);
            paint.setShader(new RadialGradient(cx, cy, halo,
                    new int[]{0x668B5CF6, 0x3322D3EE, Color.TRANSPARENT},
                    new float[]{0f, .58f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, halo, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(0xAA22D3EE);
            canvas.drawCircle(cx, cy, base * (1.12f + pulse * .08f + voiceLevel * .08f), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        int inner = active ? 0xFF8B5CF6 : 0xFF2A2D4B;
        int outer = active ? 0xFF22D3EE : 0xFF414665;
        if ("command".equals(phase)) { inner = 0xFF22D3EE; outer = 0xFF34D399; }
        else if ("confirm".equals(phase)) { inner = 0xFFF472B6; outer = 0xFF8B5CF6; }
        else if ("thinking".equals(phase)) { inner = 0xFFF59E0B; outer = 0xFFF472B6; }
        paint.setShader(new RadialGradient(cx - base * .25f, cy - base * .3f, base * 1.45f,
                new int[]{0xFFF8FAFC, inner, outer},
                new float[]{0f, .27f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, base, paint);
        paint.setShader(null);

        paint.setColor(0xFF090A18);
        canvas.drawCircle(cx, cy, base * .50f, paint);
        if (contactImage != null && "confirm".equals(phase)) {
            BitmapShader shader = new BitmapShader(contactImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(base / contactImage.getWidth(), base / contactImage.getHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - contactImage.getWidth() * scale / 2f,
                    cy - contactImage.getHeight() * scale / 2f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            canvas.drawCircle(cx, cy, base * .47f, paint);
            paint.setShader(null);
        } else {
            paint.setColor(active ? 0xFFFFFFFF : 0xFF8B91B4);
            canvas.drawCircle(cx, cy, base * .22f, paint);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(base * .19f);
        paint.setFakeBoldText(true);
        paint.setColor(active ? 0xFFFFFFFF : 0xFFB8BDD7);
        String label = "TAP TO WAKE";
        if ("wake".equals(phase)) label = "WAKE PHRASE ARMED";
        else if ("command".equals(phase)) label = "LISTENING";
        else if ("confirm".equals(phase)) label = "YOUR DECISION";
        else if ("thinking".equals(phase)) label = "THINKING";
        canvas.drawText(label, cx, cy + base * 1.42f, paint);
        paint.setFakeBoldText(false);
    }
}
