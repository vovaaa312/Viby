package com.example.viby.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.viby.R;

/** YouTube-style track progress bar. Tap/drag seeks within the current track. */
public class WaveformView extends View {

    public interface Listener {
        /** Пользователь тянет ползунок (для обновления таймкода). */
        void onSeekPreview(float fraction);

        /** Пользователь отпустил — перемотать. */
        void onSeek(float fraction);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float progress; // 0..1
    private boolean dragging;
    private float dragFraction;
    @Nullable
    private Listener listener;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        playedPaint.setColor(ContextCompat.getColor(context, R.color.viby_accent));
        unplayedPaint.setColor(ContextCompat.getColor(context, R.color.viby_wave));
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Kept for compatibility with the previous waveform-based implementation. */
    public void setWaveform(@Nullable float[] amps) {
        // The new progress bar does not use waveform amplitudes.
    }

    public void setProgress(float fraction) {
        if (!dragging) {
            progress = clamp(fraction);
            invalidate();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) {
            return;
        }
        float shown = dragging ? dragFraction : progress;
        float centerY = getPaddingTop() + h / 2f;
        float thumbRadius = dp(dragging ? 7f : 6f);
        float trackHeight = dp(3f);
        float trackStart = getPaddingLeft() + thumbRadius;
        float trackEnd = getWidth() - getPaddingRight() - thumbRadius;
        float trackWidth = Math.max(0f, trackEnd - trackStart);
        float thumbX = trackStart + shown * trackWidth;

        canvas.drawRect(trackStart, centerY - trackHeight / 2f,
                trackEnd, centerY + trackHeight / 2f, unplayedPaint);
        canvas.drawRect(trackStart, centerY - trackHeight / 2f,
                thumbX, centerY + trackHeight / 2f, playedPaint);
        canvas.drawCircle(thumbX, centerY, thumbRadius, playedPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // не отдавать жест ViewPager2, пока тянем волну
                getParent().requestDisallowInterceptTouchEvent(true);
                updateDrag(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateDrag(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                updateDrag(event.getX());
                dragging = false;
                progress = dragFraction;
                if (listener != null) {
                    listener.onSeek(dragFraction);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateDrag(float x) {
        float thumbRadius = dp(6f);
        float trackStart = getPaddingLeft() + thumbRadius;
        float trackEnd = getWidth() - getPaddingRight() - thumbRadius;
        dragFraction = clamp((x - trackStart) / Math.max(1f, trackEnd - trackStart));
        if (listener != null) {
            listener.onSeekPreview(dragFraction);
        }
        invalidate();
    }

    private static float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
