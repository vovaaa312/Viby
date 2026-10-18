package com.example.viby.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.viby.R;
import com.example.viby.playback.EqFx;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WidgetInteractionInstrumentedTest {

    @Test
    public void waveformDragClampsPreviewAndCommittedSeek() {
        AtomicReference<Float> preview = new AtomicReference<>();
        AtomicReference<Float> seek = new AtomicReference<>();
        runOnMain(() -> {
            FrameLayout parent = new FrameLayout(themedContext());
            WaveformView waveform = new WaveformView(parent.getContext());
            parent.addView(waveform, new FrameLayout.LayoutParams(1000, 100));
            parent.measure(exact(1000), exact(100));
            parent.layout(0, 0, 1000, 100);
            waveform.setListener(new WaveformView.Listener() {
                @Override public void onSeekPreview(float fraction) {
                    preview.set(fraction);
                }

                @Override public void onSeek(float fraction) {
                    seek.set(fraction);
                }
            });

            assertTrue(dispatch(waveform, MotionEvent.ACTION_DOWN, -200, 50));
            assertEquals(0f, preview.get(), 0.001f);
            assertTrue(dispatch(waveform, MotionEvent.ACTION_MOVE, 500, 50));
            assertTrue(preview.get() > 0.45f && preview.get() < 0.55f);
            assertTrue(dispatch(waveform, MotionEvent.ACTION_UP, 1200, 50));
            assertEquals(1f, preview.get(), 0.001f);
            assertEquals(1f, seek.get(), 0.001f);
        });
    }

    @Test
    public void equalizerLeavesLandscapeGuttersScrollableAndClampsGain() {
        AtomicInteger changedBand = new AtomicInteger(-1);
        AtomicReference<Float> changedGain = new AtomicReference<>();
        runOnMain(() -> {
            FrameLayout parent = new FrameLayout(themedContext());
            EqualizerCurveView equalizer = new EqualizerCurveView(parent.getContext());
            parent.addView(equalizer, new FrameLayout.LayoutParams(1000, 400));
            parent.measure(exact(1000), exact(400));
            parent.layout(0, 0, 1000, 400);
            Bitmap bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888);
            equalizer.draw(new Canvas(bitmap));
            equalizer.setOnBandGainChangeListener((band, gainDb) -> {
                changedBand.set(band);
                changedGain.set(gainDb);
            });

            assertFalse(dispatch(equalizer, MotionEvent.ACTION_DOWN, 20, 200));
            assertEquals(-1, changedBand.get());

            assertTrue(dispatch(equalizer, MotionEvent.ACTION_DOWN, 500, 200));
            assertTrue(dispatch(equalizer, MotionEvent.ACTION_MOVE, 2000, 200));
            assertTrue(dispatch(equalizer, MotionEvent.ACTION_UP, 2000, 200));
            assertTrue(changedBand.get() >= 9 && changedBand.get() <= 10);
            assertEquals(EqFx.MAX_GAIN_DB, changedGain.get(), 0.001f);
            bitmap.recycle();
        });
    }

    private static boolean dispatch(android.view.View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        try {
            return view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static int exact(int size) {
        return android.view.View.MeasureSpec.makeMeasureSpec(
                size, android.view.View.MeasureSpec.EXACTLY);
    }

    private static android.content.Context themedContext() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_Viby);
    }

    private static void runOnMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
