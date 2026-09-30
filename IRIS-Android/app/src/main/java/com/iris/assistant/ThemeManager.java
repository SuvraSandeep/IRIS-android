package com.iris.assistant;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/**
 * Central place for runtime theming: resolves the user's chosen accent colour
 * and theme (Dark / AMOLED) and applies them to views after inflation.
 *
 * Layouts keep their default colours; this only retints the small set of
 * accent-bearing elements, which keeps theming reliable and cheap.
 */
public final class ThemeManager {

    private ThemeManager() { }

    /** Named accent presets shown in the picker (label → hex). */
    public static final String[][] ACCENTS = {
            {"Cyan", "#22D3EE"},
            {"Violet", "#8B5CF6"},
            {"Mint", "#34D399"},
            {"Amber", "#FBBF24"},
            {"Magenta", "#F472B6"},
            {"Rose", "#FB7185"},
    };

    /** Material You: the wallpaper-derived system accent, or 0 when unavailable.
     *
     *  Android 12 (API 31) is the first release that exposes the extracted wallpaper palette as
     *  framework colour resources. Returns 0 on anything older, and on any device where the
     *  resource cannot be resolved, so callers can simply omit the option instead of showing a
     *  swatch that would silently fall back to the default. */
    public static int dynamicAccent(android.content.Context context) {
        if (context == null || android.os.Build.VERSION.SDK_INT < 31) return 0;
        try {
            return context.getColor(android.R.color.system_accent1_200);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Hex string for a colour, in the "#RRGGBB" form the accent setting stores. */
    public static String toHex(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** Resolve the user's accent colour to an int, falling back to cyan. */
    public static int accent(AppSettings settings) {
        try {
            return Color.parseColor(settings.accentColor());
        } catch (Exception e) {
            return Color.parseColor(AppSettings.DEFAULT_ACCENT);
        }
    }

    /** A dimmed version of a colour (for pressed/track states). */
    public static int dim(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.55f; // reduce brightness
        return Color.HSVToColor(hsv);
    }

    /** The window/base background colour for the active theme. */
    public static int baseBackground(AppSettings settings) {
        return AppSettings.THEME_AMOLED.equals(settings.theme())
                ? 0xFF000000 : 0xFF070816;
    }

    /** Apply the base theme background to an activity's window + root view. */
    public static void applyTheme(Activity activity, AppSettings settings, View root) {
        int bg = baseBackground(activity == null ? null : settings);
        if (root != null) root.setBackgroundColor(bg);
        if (activity != null && activity.getWindow() != null) {
            activity.getWindow().setStatusBarColor(bg);
            activity.getWindow().setNavigationBarColor(bg);
        }
    }

    /** Resolve the tintable fill of a background drawable.
     *
     *  Interactive backgrounds (bg_deck_card, bg_chip, bg_tab_*) are &lt;ripple&gt; wrappers so a
     *  tap gives press feedback. A RippleDrawable is NOT a GradientDrawable, so the accent
     *  tinting below would silently stop working (and primaryButton would no-op, dropping the
     *  command-bar button's accent fill) if it only checked for GradientDrawable. Each ripple
     *  tags its inner shape with @android:id/background, so look through the wrapper and return
     *  the real fill. Returns null when there is nothing tintable, and callers keep their
     *  previous fallback behaviour in that case. */
    private static GradientDrawable fillDrawable(android.graphics.drawable.Drawable d) {
        if (d == null) return null;
        if (d instanceof GradientDrawable) return (GradientDrawable) d.mutate();
        if (d instanceof android.graphics.drawable.RippleDrawable) {
            android.graphics.drawable.RippleDrawable ripple =
                    (android.graphics.drawable.RippleDrawable) d.mutate();
            android.graphics.drawable.Drawable inner =
                    ripple.findDrawableByLayerId(android.R.id.background);
            if (inner instanceof GradientDrawable) return (GradientDrawable) inner;
            for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                if (ripple.getDrawable(i) instanceof GradientDrawable) {
                    return (GradientDrawable) ripple.getDrawable(i);
                }
            }
        }
        return null;
    }

    /** Tint a view's background drawable with the accent (rounded fills stay rounded). */
    public static void tintBackground(View v, int color) {
        if (v == null) return;
        GradientDrawable fill = fillDrawable(v.getBackground());
        if (fill != null) {
            fill.setColor(color);
        } else if (v.getBackground() != null) {
            v.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /** Colour a TextView's text with the accent. */
    public static void tintText(TextView tv, int color) {
        if (tv != null) tv.setTextColor(color);
    }

    /** Style a button as the primary accent action. */
    public static void primaryButton(Button b, int accent) {
        if (b == null) return;
        GradientDrawable fill = fillDrawable(b.getBackground());
        if (fill != null) fill.setColor(accent);
    }
}
