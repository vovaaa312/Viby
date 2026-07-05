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

/**
 * Прогресс трека в виде волны, как в AIMP: серые столбики амплитуды,
 * проигранная часть подсвечена оранжевым. Тап/драг — перемотка.
 */
public class WaveformView extends View {

    public interface Listener {
        /** Пользователь тянет ползунок (для обновления таймкода). */
        void onSeekPreview(float fraction);

        /** Пользователь отпустил — перемотать. */
        void onSeek(float fraction);
    }

    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable
    private float[] amps;
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
        cursorPaint.setColor(ContextCompat.getColor(context, R.color.viby_text));
        cursorPaint.setStrokeWidth(dp(2));
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** null → «волна ещё считается», рисуем плоскую линию. */
    public void setWaveform(@Nullable float[] amps) {
        this.amps = amps;
        invalidate();
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

        float barWidth = dp(3);
        float gap = dp(1.5f);
        int bars = Math.max(1, (int) ((w + gap) / (barWidth + gap)));
        float minBar = dp(2);

        for (int i = 0; i < bars; i++) {
            float amp = 0.06f;
            if (amps != null && amps.length > 0) {
                int idx = (int) ((long) i * amps.length / bars);
                amp = Math.max(amps[Math.min(idx, amps.length - 1)], 0.04f);
            }
            float barH = Math.max(minBar, amp * h);
            float left = getPaddingLeft() + i * (barWidth + gap);
            boolean played = (i + 0.5f) / bars <= shown;
            canvas.drawRect(left, centerY - barH / 2f,
                    left + barWidth, centerY + barH / 2f,
                    played ? playedPaint : unplayedPaint);
        }

        float cursorX = getPaddingLeft() + shown * w;
        canvas.drawLine(cursorX, getPaddingTop(), cursorX,
                getPaddingTop() + h, cursorPaint);
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
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        dragFraction = clamp((x - getPaddingLeft()) / Math.max(1, w));
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
