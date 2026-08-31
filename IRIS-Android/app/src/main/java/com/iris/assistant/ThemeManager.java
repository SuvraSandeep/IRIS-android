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

    /** Tint a view's background drawable with the accent (rounded fills stay rounded). */
    public static void tintBackground(View v, int color) {
        if (v == null) return;
        if (v.getBackground() instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) v.getBackground().mutate();
            gd.setColor(color);
        } else {
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
        if (b.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) b.getBackground().mutate()).setColor(accent);
        }
    }
}
