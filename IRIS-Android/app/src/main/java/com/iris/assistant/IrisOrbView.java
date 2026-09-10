package com.iris.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * The Command Deck orb. Every animation represents a REAL state — nothing moves to look busy.
 *
 * States (spec §3):
 *   off       dim outline, stationary rings
 *   wake      slow breathing glow, outer ring rotates once per ~20s
 *   command   waveform driven by the measured microphone level
 *   thinking  segmented ring travelling around the orb
 *   speaking  gentle pulses tied to speech playback
 *   done      one outward green ripple, then back to the previous state
 *   paused    amber ring + pause glyph (wake paused because media is playing)
 *   blocked   amber broken ring (permission or model unavailable) — tappable
 *   error     brief red outline, then a stable error state
 *
 * Honours Reduce motion and the deck's battery-saving visual mode: in static mode the same
 * states are still distinguishable, just not animated.
 */
public class IrisOrbView extends View {

    public static final String PHASE_OFF = "off";
    public static final String PHASE_WAKE = "wake";
    public static final String PHASE_COMMAND = "command";
    public static final String PHASE_CONFIRM = "confirm";
    public static final String PHASE_THINKING = "thinking";
    public static final String PHASE_SPEAKING = "speaking";
    public static final String PHASE_PAUSED = "paused";
    public static final String PHASE_BLOCKED = "blocked";
    public static final String PHASE_ERROR = "error";

    private static final int HEALTHY = 0xFF63FF9D;
    private static final int ATTENTION = 0xFFFFBF69;
    private static final int ERROR = 0xFFFF637D;
    private static final int INACTIVE = 0xFF3D4E56;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final float[] waveform = new float[28];

    private float pulse;              // breathing / speaking 0..1
    private float spin;               // slow outer-ring rotation 0..360
    private float segment;            // processing segment 0..360
    private float ripple = -1f;       // completion ripple 0..1, <0 = inactive
    private boolean active;
    private String phase = PHASE_OFF;
    private float voiceLevel;
    private Bitmap contactImage;
    private int accentColor = 0xFF35D9F4;
    private boolean reduceMotion;
    private boolean staticMode;
    private long errorAt;

    private ValueAnimator pulseAnimator, spinAnimator, segmentAnimator, rippleAnimator;

    public IrisOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(2600);                    // slow breathing
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); if (active) invalidate(); });

        spinAnimator = ValueAnimator.ofFloat(0f, 360f);
        spinAnimator.setDuration(20_000);                   // one turn per ~20s (spec: 18–24s)
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(new LinearInterpolator());
        spinAnimator.addUpdateListener(a -> { spin = (float) a.getAnimatedValue(); if (active) invalidate(); });

        segmentAnimator = ValueAnimator.ofFloat(0f, 360f);
        segmentAnimator.setDuration(1100);                  // travelling processing segment
        segmentAnimator.setRepeatCount(ValueAnimator.INFINITE);
        segmentAnimator.setInterpolator(new LinearInterpolator());
        segmentAnimator.addUpdateListener(a -> { segment = (float) a.getAnimatedValue(); invalidate(); });
    }

    // ─────────────────────────── configuration ───────────────────────────

    public void setAccent(int color) { this.accentColor = color; invalidate(); }

    /** Reduce motion: keep the states readable but stop continuous animation. */
    public void setReduceMotion(boolean reduce) {
        this.reduceMotion = reduce;
        applyAnimators();
    }

    /** Battery-saving visual mode — same effect as reduce motion for the orb. */
    public void setStaticMode(boolean staticVisuals) {
        this.staticMode = staticVisuals;
        applyAnimators();
    }

    private boolean animationsAllowed() { return isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && !reduceMotion && !staticMode; }

    @Override protected void onDetachedFromWindow() {
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (spinAnimator != null) spinAnimator.cancel();
        if (segmentAnimator != null) segmentAnimator.cancel();
        if (rippleAnimator != null) rippleAnimator.cancel();
        super.onDetachedFromWindow();
    }
    @Override public void onVisibilityAggregated(boolean shown) {
        super.onVisibilityAggregated(shown); applyAnimators();
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) phase = PHASE_OFF;
        setContentDescription(active ? "Turn IRIS off" : "Turn IRIS on");
        applyAnimators();
        invalidate();
    }

    public void setPhase(String phase) {
        String next = phase == null ? PHASE_OFF : phase;
        if (PHASE_ERROR.equals(next) && !PHASE_ERROR.equals(this.phase)) errorAt = System.currentTimeMillis();
        this.phase = next;
        this.active = !PHASE_OFF.equals(next);
        if (!PHASE_CONFIRM.equals(next)) contactImage = null;
        setContentDescription(describe(next));
        applyAnimators();
        invalidate();
    }

    public String phase() { return phase; }

    /** One outward green ripple when an action actually completed. */
    public void signalCompleted() {
        if (!animationsAllowed()) { ripple = -1f; invalidate(); return; }
        if (rippleAnimator != null) rippleAnimator.cancel();
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f);
        rippleAnimator.setDuration(760);
        rippleAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        rippleAnimator.addUpdateListener(a -> { ripple = (float) a.getAnimatedValue(); invalidate(); });
        rippleAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { ripple = -1f; invalidate(); }
        });
        rippleAnimator.start();
    }

    public void setContactImage(Bitmap bitmap) { contactImage = bitmap; invalidate(); }

    /** Real measured microphone amplitude, 0..1 — drives the listening waveform only. */
    public void setVoiceLevel(float level) {
        voiceLevel = Math.max(0f, Math.min(1f, level));
        if (PHASE_COMMAND.equals(phase)) {
            System.arraycopy(waveform, 1, waveform, 0, waveform.length - 1);
            waveform[waveform.length - 1] = voiceLevel;
            invalidate();
        }
    }

    private void applyAnimators() {
        boolean allow = animationsAllowed() && active;
        toggle(pulseAnimator, allow && (PHASE_WAKE.equals(phase) || PHASE_SPEAKING.equals(phase)
                || PHASE_COMMAND.equals(phase) || PHASE_CONFIRM.equals(phase)));
        toggle(spinAnimator, allow && PHASE_WAKE.equals(phase));
        toggle(segmentAnimator, allow && PHASE_THINKING.equals(phase));
    }

    private static void toggle(ValueAnimator a, boolean on) {
        if (a == null) return;
        if (on) {
            if (!a.isStarted()) a.start();
            else if (a.isPaused()) a.resume();
        } else if (a.isStarted()) {
            a.pause();
        }
    }

    private String describe(String p) {
        switch (p) {
            case PHASE_WAKE:     return "IRIS is waiting for your wake phrase. Tap to stop.";
            case PHASE_COMMAND:  return "IRIS is listening for a command.";
            case PHASE_CONFIRM:  return "IRIS is waiting for your confirmation.";
            case PHASE_THINKING: return "IRIS is processing.";
            case PHASE_SPEAKING: return "IRIS is speaking.";
            case PHASE_PAUSED:   return "Wake paused because media is playing. Tap for details.";
            case PHASE_BLOCKED:  return "Wake unavailable: permission or model missing. Tap for details.";
            case PHASE_ERROR:    return "IRIS hit an error. Tap for details.";
            default:             return "IRIS is off. Tap to start.";
        }
    }

    // ─────────────────────────── drawing ───────────────────────────

    private int stateColor() {
        switch (phase) {
            case PHASE_PAUSED:
            case PHASE_BLOCKED:  return ATTENTION;
            case PHASE_ERROR:    return ERROR;
            case PHASE_COMMAND:  return HEALTHY;
            case PHASE_SPEAKING: return HEALTHY;
            case PHASE_OFF:      return INACTIVE;
            default:             return accentColor;
        }
    }

    private static int withAlpha(int color, int alpha) { return (alpha << 24) | (color & 0xFFFFFF); }

    private static int darker(int c) {
        int r = (int) (((c >> 16) & 0xFF) * 0.55f);
        int g = (int) (((c >> 8) & 0xFF) * 0.55f);
        int b = (int) ((c & 0xFF) * 0.55f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) * 0.31f;
        int state = stateColor();
        boolean animate = animationsAllowed();
        // An inactive orb must LOOK inactive even while the rest of the deck animates.
        float breath = (active && animate && (PHASE_WAKE.equals(phase) || PHASE_SPEAKING.equals(phase)))
                ? pulse : 0.35f;

        // ── halo ──
        if (active) {
            float halo = base * (1.42f + breath * 0.16f + voiceLevel * 0.2f);
            paint.setShader(new RadialGradient(cx, cy, halo,
                    new int[]{ withAlpha(state, 0x5C), withAlpha(state, 0x2E), Color.TRANSPARENT },
                    new float[]{ 0f, 0.58f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, halo, paint);
            paint.setShader(null);
        }

        // ── completion ripple (only after a real action) ──
        if (ripple >= 0f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(base * 0.06f * (1f - ripple));
            paint.setColor(withAlpha(HEALTHY, (int) (0xCC * (1f - ripple))));
            canvas.drawCircle(cx, cy, base * (1.1f + ripple * 0.75f), paint);
        }

        // ── rings ──
        paint.setStyle(Paint.Style.STROKE);
        float ringR = base * 1.2f;
        arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);

        if (PHASE_BLOCKED.equals(phase)) {
            // Broken ring: unmistakably "not working".
            paint.setStrokeWidth(base * 0.05f);
            paint.setColor(withAlpha(ATTENTION, 0xDD));
            for (int i = 0; i < 6; i++) canvas.drawArc(arc, i * 60f + 8f, 34f, false, paint);
        } else if (PHASE_PAUSED.equals(phase)) {
            paint.setStrokeWidth(base * 0.05f);
            paint.setColor(withAlpha(ATTENTION, 0xDD));
            canvas.drawCircle(cx, cy, ringR, paint);
        } else if (PHASE_THINKING.equals(phase)) {
            // Faint track + travelling segment.
            paint.setStrokeWidth(base * 0.035f);
            paint.setColor(withAlpha(accentColor, 0x33));
            canvas.drawCircle(cx, cy, ringR, paint);
            paint.setStrokeWidth(base * 0.06f);
            paint.setColor(withAlpha(accentColor, 0xEE));
            canvas.drawArc(arc, animate ? segment : -90f, 70f, false, paint);
        } else if (PHASE_ERROR.equals(phase)) {
            paint.setStrokeWidth(base * (System.currentTimeMillis() - errorAt < 900 ? 0.09f : 0.05f));
            paint.setColor(withAlpha(ERROR, 0xEE));
            canvas.drawCircle(cx, cy, ringR, paint);
        } else if (active) {
            // Armed: outer tick ring that rotates once per ~20s.
            paint.setStrokeWidth(base * 0.03f);
            paint.setColor(withAlpha(state, 0x55));
            canvas.drawCircle(cx, cy, ringR, paint);
            paint.setStrokeWidth(base * 0.05f);
            paint.setColor(withAlpha(state, 0xAA));
            float sweep = PHASE_COMMAND.equals(phase) ? 300f : 120f;
            canvas.drawArc(arc, animate ? spin : -90f, sweep, false, paint);
        } else {
            // Off: dim, stationary outline.
            paint.setStrokeWidth(base * 0.03f);
            paint.setColor(withAlpha(INACTIVE, 0x99));
            canvas.drawCircle(cx, cy, ringR, paint);
        }

        // ── listening waveform: real amplitude only ──
        if (PHASE_COMMAND.equals(phase)) {
            paint.setStrokeWidth(base * 0.045f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(withAlpha(HEALTHY, 0xDD));
            float span = base * 1.6f, step = span / (waveform.length - 1);
            for (int i = 0; i < waveform.length; i++) {
                float x = cx - span / 2f + i * step;
                float amp = base * 0.5f * Math.max(0.04f, waveform[i]);
                canvas.drawLine(x, cy - amp, x, cy + amp, paint);
            }
        }

        // ── sphere ──
        paint.setStyle(Paint.Style.FILL);
        int inner = active ? state : 0xFF1B2B33;
        int outer = active ? darker(state) : 0xFF243740;
        paint.setShader(new RadialGradient(cx - base * .25f, cy - base * .3f, base * 1.45f,
                new int[]{ active ? 0xFFF2FBFD : 0xFF7E939C, inner, outer },
                new float[]{ 0f, .27f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, base, paint);
        paint.setShader(null);

        paint.setColor(0xFF05090D);
        canvas.drawCircle(cx, cy, base * .50f, paint);

        if (contactImage != null && PHASE_CONFIRM.equals(phase)) {
            BitmapShader shader = new BitmapShader(contactImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(base / contactImage.getWidth(), base / contactImage.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - contactImage.getWidth() * scale / 2f, cy - contactImage.getHeight() * scale / 2f);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawCircle(cx, cy, base * .47f, paint);
            paint.setShader(null);
        } else if (PHASE_PAUSED.equals(phase)) {
            // Pause glyph, so the reason is readable at a glance.
            paint.setColor(ATTENTION);
            float bw = base * 0.09f, bh = base * 0.26f, gap = base * 0.08f;
            canvas.drawRect(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f, paint);
            canvas.drawRect(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f, paint);
        } else if (PHASE_BLOCKED.equals(phase) || PHASE_ERROR.equals(phase)) {
            paint.setColor(PHASE_ERROR.equals(phase) ? ERROR : ATTENTION);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(base * 0.42f);
            paint.setFakeBoldText(true);
            canvas.drawText("!", cx, cy + base * 0.15f, paint);
            paint.setFakeBoldText(false);
        } else {
            paint.setColor(active ? 0xFFFFFFFF : 0xFF6B818C);
            float dot = base * (0.2f + (PHASE_SPEAKING.equals(phase) ? breath * 0.06f : 0f));
            canvas.drawCircle(cx, cy, dot, paint);
        }

        // ── caption ──
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(base * .17f);
        paint.setFakeBoldText(true);
        paint.setColor(active ? 0xFFE4F2F5 : 0xFF93ABB5);
        canvas.drawText(caption(), cx, cy + base * 1.5f, paint);
        paint.setFakeBoldText(false);
    }

    private String caption() {
        switch (phase) {
            case PHASE_WAKE:     return "AWAITING WAKE PHRASE";
            case PHASE_COMMAND:  return "LISTENING";
            case PHASE_CONFIRM:  return "YOUR DECISION";
            case PHASE_THINKING: return "PROCESSING";
            case PHASE_SPEAKING: return "SPEAKING";
            case PHASE_PAUSED:   return "WAKE PAUSED";
            case PHASE_BLOCKED:  return "WAKE UNAVAILABLE";
            case PHASE_ERROR:    return "ERROR";
            default:             return "TAP TO WAKE";
        }
    }
}

