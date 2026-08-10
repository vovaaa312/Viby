package com.example.viby.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

/** Darkens pending artwork and draws clockwise track download progress. */
public class DownloadProgressOverlayView extends View {

    private final Paint ringBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringBounds = new RectF();
    private float displayedProgress;
    private ValueAnimator animator;

    public DownloadProgressOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float stroke = 3f * getResources().getDisplayMetrics().density;
        ringBackground.setStyle(Paint.Style.STROKE);
        ringBackground.setStrokeWidth(stroke);
        ringBackground.setColor(Color.argb(105, 255, 255, 255));
        ringProgress.setStyle(Paint.Style.STROKE);
        ringProgress.setStrokeCap(Paint.Cap.ROUND);
        ringProgress.setStrokeWidth(stroke);
        ringProgress.setColor(Color.WHITE);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void showProgress(boolean show, int progress) {
        if (!show) {
            if (animator != null) {
                animator.cancel();
            }
            displayedProgress = 0f;
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        float target = Math.max(0, Math.min(100, progress));
        if (animator != null) {
            animator.cancel();
        }
        if (Math.abs(target - displayedProgress) < 0.1f) {
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(displayedProgress, target);
        animator.setDuration(220L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(valueAnimator -> {
            displayedProgress = (float) valueAnimator.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // An exact 50% black veil keeps artwork visible while clearly marking it pending.
        canvas.drawColor(Color.argb(128, 0, 0, 0));

        float inset = Math.min(getWidth(), getHeight()) * 0.25f;
        ringBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawOval(ringBounds, ringBackground);
        if (displayedProgress > 0f) {
            canvas.drawArc(ringBounds, -90f, 360f * displayedProgress / 100f,
                    false, ringProgress);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
