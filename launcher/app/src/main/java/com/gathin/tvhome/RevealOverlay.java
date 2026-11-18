package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/**
 * A still picture of Home as it was, laid over Home while a circle grows out of it from one point (the button that
 * was pressed): the change underneath shows inside the circle and spreads to the edges. Only a clip changes per
 * frame, so the GPU does the work.
 */
@SuppressLint("ViewConstructor")
final class RevealOverlay extends View {
    static final long DURATION_MS = 700;

    private final Bitmap picture;
    private final float cx, cy;
    private final Path hole = new Path();
    private float radius;
    private ValueAnimator animator;

    private RevealOverlay(Context context, Bitmap picture, float cx, float cy) {
        super(context);
        this.picture = picture;
        this.cx = cx;
        this.cy = cy;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Covers root with picture, runs change underneath, then opens the circle from (cx, cy); picture is recycled after. */
    static RevealOverlay start(FrameLayout root, Bitmap picture, float cx, float cy, Runnable change) {
        RevealOverlay overlay = new RevealOverlay(root.getContext(), picture, cx, cy);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        change.run();
        int w = root.getWidth(), h = root.getHeight();
        float far = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
        overlay.animator = ValueAnimator.ofFloat(0f, far);
        overlay.animator.setDuration(DURATION_MS);
        overlay.animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        overlay.animator.addUpdateListener(a -> {
            overlay.radius = (float) a.getAnimatedValue();
            overlay.invalidate();
        });
        overlay.animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                root.removeView(overlay);
                picture.recycle();
            }
        });
        overlay.animator.start();
        return overlay;
    }

    /** Jumps to the end: the overlay goes and the change shows everywhere. */
    void finish() {
        if (animator != null) animator.end();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (picture.isRecycled()) return;
        canvas.save();
        hole.rewind();
        hole.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipOutPath(hole);
        canvas.drawBitmap(picture, 0f, 0f, null);
        canvas.restore();
    }
}
