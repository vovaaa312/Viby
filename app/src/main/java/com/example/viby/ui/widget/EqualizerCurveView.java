package com.example.viby.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.viby.R;
import com.example.viby.playback.EqFx;

import java.util.Arrays;
import java.util.Locale;

/** Compact, interactive 20-band equalizer curve inspired by AIMP's layout. */
public final class EqualizerCurveView extends View {

    public interface OnBandGainChangeListener {
        void onBandGainChanged(int band, float gainDb);
    }

    private static final float MIN_GAIN_DB = -EqFx.MAX_GAIN_DB;
    private static final float MAX_GAIN_DB = EqFx.MAX_GAIN_DB;

    private final float density;
    private final float[] gains = new float[EqFx.getBandCount()];
    private final Paint chartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final RectF chart = new RectF();

    @Nullable
    private OnBandGainChangeListener listener;
    private int activeBand;

    public EqualizerCurveView(Context context) {
        this(context, null);
    }

    public EqualizerCurveView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setContentDescription(getResources().getString(R.string.eq_curve_content_description));

        chartPaint.setStyle(Paint.Style.FILL);
        chartPaint.setColor(ContextCompat.getColor(context, R.color.viby_surface_high));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(withAlpha(
                ContextCompat.getColor(context, R.color.viby_text_secondary), 76));

        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(dp(1.5f));
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setColor(ContextCompat.getColor(context, R.color.viby_text_secondary));

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(ContextCompat.getColor(context, R.color.viby_accent));

        labelPaint.setTextSize(sp(11));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.viby_text_secondary));
    }

    public void setOnBandGainChangeListener(@Nullable OnBandGainChangeListener listener) {
        this.listener = listener;
    }

    public void setGains(float[] values) {
        Arrays.fill(gains, 0f);
        System.arraycopy(values, 0, gains, 0, Math.min(values.length, gains.length));
        invalidate();
    }

    public void setBandGain(int band, float gainDb) {
        if (band >= 0 && band < gains.length) {
            gains[band] = clamp(gainDb);
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.round(dp(360));
        int desiredHeight = Math.round(dp(20 + gains.length * 25));
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float leftLabels = dp(48);
        float rightLabels = dp(52);
        float verticalPadding = dp(10);
        chart.set(leftLabels, verticalPadding,
                getWidth() - rightLabels, getHeight() - verticalPadding);

        canvas.drawRect(chart, chartPaint);
        canvas.drawRect(chart, gridPaint);
        float zeroX = gainToX(0f);
        canvas.drawLine(zeroX, chart.top, zeroX, chart.bottom, gridPaint);

        curvePath.reset();
        for (int band = 0; band < gains.length; band++) {
            float x = gainToX(gains[band]);
            float y = bandToY(band);
            if (band == 0) {
                curvePath.moveTo(x, y);
            } else {
                curvePath.lineTo(x, y);
            }
        }
        canvas.drawPath(curvePath, curvePaint);

        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float textOffset = -(metrics.ascent + metrics.descent) / 2f;
        for (int band = 0; band < gains.length; band++) {
            float y = bandToY(band);
            float x = gainToX(gains[band]);
            canvas.drawCircle(x, y, dp(band == activeBand && isFocused() ? 4f : 3f),
                    pointPaint);

            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatFrequency(EqFx.getCenterFreqHz(band)),
                    chart.left - dp(9), y + textOffset, labelPaint);
            labelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(formatGain(gains[band]),
                    chart.right + dp(9), y + textOffset, labelPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                getParent().requestDisallowInterceptTouchEvent(true);
                activeBand = bandNearestTo(event.getY());
                updateActiveBandFromX(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateActiveBandFromX(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                updateActiveBandFromX(event.getX());
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            activeBand = Math.max(0, Math.min(gains.length - 1,
                    activeBand + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            float direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -0.5f : 0.5f;
            setGainFromUser(activeBand, gains[activeBand] + direction);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int bandNearestTo(float y) {
        return Math.max(0, Math.min(gains.length - 1,
                Math.round((y - chart.top) / Math.max(1f, chart.height())
                        * (gains.length - 1))));
    }

    private void updateActiveBandFromX(float x) {
        float normalized = Math.max(0f, Math.min(1f,
                (x - chart.left) / Math.max(1f, chart.width())));
        float gain = Math.round((MIN_GAIN_DB + normalized * (MAX_GAIN_DB - MIN_GAIN_DB)) * 10f)
                / 10f;
        setGainFromUser(activeBand, gain);
    }

    private void setGainFromUser(int band, float gainDb) {
        gains[band] = clamp(gainDb);
        invalidate();
        if (listener != null) {
            listener.onBandGainChanged(band, gains[band]);
        }
    }

    private float gainToX(float gainDb) {
        return chart.left + (clamp(gainDb) - MIN_GAIN_DB)
                / (MAX_GAIN_DB - MIN_GAIN_DB) * chart.width();
    }

    private float bandToY(int band) {
        return gains.length == 1 ? chart.centerY()
                : chart.top + chart.height() * band / (gains.length - 1f);
    }

    private static float clamp(float gainDb) {
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
    }

    private static String formatFrequency(int hz) {
        if (hz >= 1000) {
            return (hz % 1000 == 0 ? String.valueOf(hz / 1000)
                    : String.format(Locale.US, "%.1f", hz / 1000f)) + "k";
        }
        return String.valueOf(hz);
    }

    private static String formatGain(float gainDb) {
        return Math.abs(gainDb) < 0.05f ? "0.0"
                : String.format(Locale.US, "%+.1f", gainDb);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
