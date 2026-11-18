package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * The header's light/dark toggle (the sun/moon pill). Either way a gradient over the top of the wallpaper keeps the
 * clock, the header and the dock title readable on any picture: dark keeps the white text over a dark shade, light
 * turns the text dark over a light shade, for bright wallpapers. Saved with the other on-TV choices.
 */
final class HeaderTheme {

    static final String KEY = "header_theme";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    /** The light theme's text colour, the near-black tvOS uses on light backgrounds. */
    static final int INK = 0x1C1C1E;

    /** How far down the screen the shade reaches: past the dock title and the dock. */
    static final float SCRIM_HEIGHT = 0.68f;

    private HeaderTheme() {
    }

    static boolean isLight(Context context) {
        return LIGHT.equals(context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, DARK));
    }

    static void setLight(Context context, boolean light) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY, light ? LIGHT : DARK).apply();
    }

    /** A white text colour as the light theme's ink, keeping its alpha (dimmed whites become dimmed inks). */
    static int ink(int white) {
        return (white & 0xFF000000) | INK;
    }

    /** Top to bottom: strong behind the header, lighter behind the dock title, gone below the dock. */
    static int[] scrimColors(boolean light) {
        return light ? new int[] {0xC7FFFFFF, 0x6BFFFFFF, 0x00FFFFFF}
                : new int[] {0xBF000000, 0x66000000, 0x00000000};
    }

    static GradientDrawable scrim(boolean light) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, scrimColors(light));
    }

    /** How far the ambient glow's colour moves toward its own grey: loud brand colours become a quieter tint. */
    static final float GLOW_MUTE = 0.35f;

    /** An app's colour for the ambient glow, partly desaturated (alpha dropped: the glow mask carries it). */
    static int mutedGlow(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        float grey = 0.299f * r + 0.587f * g + 0.114f * b;
        r = Math.round(r + (grey - r) * GLOW_MUTE);
        g = Math.round(g + (grey - g) * GLOW_MUTE);
        b = Math.round(b + (grey - b) * GLOW_MUTE);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
