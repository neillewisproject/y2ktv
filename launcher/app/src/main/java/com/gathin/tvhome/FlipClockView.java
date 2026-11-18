package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.Calendar;
import java.util.Locale;

/**
 * The clock screensaver's Flip style: hours and minutes on two dark cards with a hinge line, and one short flip
 * when a card's value changes. Text only, no bitmaps; it redraws once a minute plus the flip's few frames.
 */
public final class FlipClockView extends View {
    private static final long FLIP_MS = 520;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hinge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    /** "system", "12" or "24" (Config.clockFormat). */
    private String format = "system";
    private boolean animate = true;
    private final String[] shown = {"", ""};
    private final String[] previous = {"", ""};
    /** 0..1 per card while it flips; 1 at rest. */
    private final float[] progress = {1f, 1f};
    private final ValueAnimator[] flips = new ValueAnimator[2];

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            long now = System.currentTimeMillis();
            postDelayed(this, 60_000L - now % 60_000L + 50);
        }
    };

    public FlipClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        card.setColor(0xFF1B1F27);
        digits.setColor(0xF2FFFFFF);
        digits.setTextAlign(Paint.Align.CENTER);
        digits.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        hinge.setColor(0xFF07090C);
        shade.setColor(Color.BLACK);
    }

    void setFormat(String format, boolean animate) {
        this.format = format == null ? "system" : format;
        this.animate = animate;
        shown[0] = shown[1] = "";
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        for (ValueAnimator a : flips) if (a != null) a.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.round(190 * density);
        int w = Math.round(2 * 170 * density + 28 * density);
        setMeasuredDimension(w, h);
    }

    private boolean is24() {
        if ("24".equals(format)) return true;
        if ("12".equals(format)) return false;
        return DateFormat.is24HourFormat(getContext());
    }

    private void update() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (!is24()) hour = hour % 12 == 0 ? 12 : hour % 12;
        String[] next = {String.format(Locale.ROOT, is24() ? "%02d" : "%d", hour),
                String.format(Locale.ROOT, "%02d", now.get(Calendar.MINUTE))};
        for (int i = 0; i < 2; i++) {
            if (next[i].equals(shown[i])) continue;
            boolean first = shown[i].isEmpty();
            previous[i] = first ? next[i] : shown[i];
            shown[i] = next[i];
            if (first || !animate || !isAttachedToWindow()) {
                progress[i] = 1f;
                continue;
            }
            int index = i;
            if (flips[index] != null) flips[index].cancel();
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(FLIP_MS);
            a.setStartDelay(index == 0 ? 120 : 0);
            a.addUpdateListener(v -> {
                progress[index] = (float) v.getAnimatedValue();
                invalidate();
            });
            flips[index] = a;
            progress[index] = 0f;
            a.start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float gap = 28 * density;
        float w = (getWidth() - gap) / 2f;
        float h = getHeight();
        digits.setTextSize(h * 0.74f);
        for (int i = 0; i < 2; i++) drawCard(canvas, i * (w + gap), w, h, i);
    }

    /**
     * A card mid-flip: the new value's top half and the old value's bottom half are still; the old top half folds
     * down over the hinge (first half of the flip), then the new bottom half unfolds (second half).
     */
    private void drawCard(Canvas c, float left, float w, float h, int i) {
        float radius = 14 * density;
        float mid = h / 2f;
        rect.set(left, 0, left + w, h);
        c.drawRoundRect(rect, radius, radius, card);
        float t = progress[i];
        String now = shown[i], old = previous[i];
        // Still halves.
        drawHalf(c, left, w, h, now, true, 1f);
        drawHalf(c, left, w, h, t < 1f ? old : now, false, 1f);
        if (t < 1f) {
            if (t < 0.5f) {
                float f = new AccelerateInterpolator().getInterpolation(t * 2f);
                drawHalf(c, left, w, h, old, true, 1f - f);
            } else {
                float f = new DecelerateInterpolator().getInterpolation((t - 0.5f) * 2f);
                drawHalf(c, left, w, h, now, false, f);
            }
        }
        c.drawRect(left, mid - density, left + w, mid + density, hinge);
    }

    /** One half of a card, squashed towards the hinge by scale (1 is flat on the card, 0 edge-on). */
    private void drawHalf(Canvas c, float left, float w, float h, String text, boolean top, float scale) {
        float mid = h / 2f;
        int save = c.save();
        if (top) c.clipRect(left, 0, left + w, mid);
        else c.clipRect(left, mid, left + w, h);
        c.scale(1f, Math.max(0.001f, scale), left + w / 2f, mid);
        if (scale < 1f) {
            rect.set(left, top ? 0 : mid, left + w, top ? mid : h);
            c.drawRoundRect(rect, 14 * density, 14 * density, card);
        }
        Paint.FontMetrics fm = digits.getFontMetrics();
        c.drawText(text, left + w / 2f, mid - (fm.ascent + fm.descent) / 2f, digits);
        if (scale < 1f) {
            shade.setAlpha(Math.round((1f - scale) * 110));
            c.drawRect(left, top ? 0 : mid, left + w, top ? mid : h, shade);
        }
        c.restoreToCount(save);
    }
}
