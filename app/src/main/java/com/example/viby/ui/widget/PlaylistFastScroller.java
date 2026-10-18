package com.example.viby.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viby.R;

/** A desktop-style draggable scrollbar for the playlist RecyclerView. */
public final class PlaylistFastScroller extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final float normalThumbWidth;
    private final float draggedThumbWidth;
    private final float minimumThumbHeight;

    @Nullable private RecyclerView recyclerView;
    private boolean listenersAttached;
    private boolean dragging;
    private float dragOffset;

    private final RecyclerView.OnScrollListener scrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    invalidate();
                }
            };

    private final OnLayoutChangeListener layoutChangeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    postInvalidate();

    private final RecyclerView.AdapterDataObserver dataObserver =
            new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { postInvalidate(); }
                @Override public void onItemRangeChanged(int start, int count) { postInvalidate(); }
                @Override public void onItemRangeInserted(int start, int count) { postInvalidate(); }
                @Override public void onItemRangeRemoved(int start, int count) { postInvalidate(); }
                @Override public void onItemRangeMoved(int from, int to, int count) { postInvalidate(); }
            };

    public PlaylistFastScroller(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        normalThumbWidth = 6f * density;
        draggedThumbWidth = 10f * density;
        minimumThumbHeight = 52f * density;
        trackPaint.setColor(ContextCompat.getColor(context, R.color.viby_surface_high));
        thumbPaint.setColor(ContextCompat.getColor(context, R.color.viby_text_secondary));
        thumbPaint.setAlpha(210);
        setClickable(true);
    }

    public void attachTo(RecyclerView list) {
        if (recyclerView == list) {
            attachListeners();
            postInvalidate();
            return;
        }
        detachListeners();
        recyclerView = list;
        attachListeners();
        postInvalidate();
    }

    /** Fully releases the RecyclerView when the owning Fragment destroys its view. */
    public void detachFromRecyclerView() {
        detachListeners();
        recyclerView = null;
    }

    private void attachListeners() {
        if (recyclerView == null || listenersAttached) {
            return;
        }
        recyclerView.addOnScrollListener(scrollListener);
        recyclerView.addOnLayoutChangeListener(layoutChangeListener);
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter != null) {
            adapter.registerAdapterDataObserver(dataObserver);
        }
        listenersAttached = true;
    }

    private void detachListeners() {
        if (recyclerView == null || !listenersAttached) {
            return;
        }
        recyclerView.removeOnScrollListener(scrollListener);
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener);
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter != null) {
            adapter.unregisterAdapterDataObserver(dataObserver);
        }
        listenersAttached = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachListeners();
        postInvalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        // ViewPager may temporarily detach and later reuse the same Fragment view.
        // Keep the list reference so onAttachedToWindow() can subscribe again.
        detachListeners();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Metrics metrics = metrics();
        if (metrics == null) {
            return;
        }
        float centerX = getWidth() / 2f;
        float trackWidth = dp(2f);
        trackRect.set(centerX - trackWidth / 2f, 0f,
                centerX + trackWidth / 2f, getHeight());
        canvas.drawRoundRect(trackRect, trackWidth / 2f, trackWidth / 2f, trackPaint);

        float thumbWidth = dragging ? draggedThumbWidth : normalThumbWidth;
        thumbRect.set(centerX - thumbWidth / 2f, metrics.thumbTop,
                centerX + thumbWidth / 2f, metrics.thumbTop + metrics.thumbHeight);
        canvas.drawRoundRect(thumbRect, thumbWidth / 2f, thumbWidth / 2f, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Metrics metrics = metrics();
        if (metrics == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                dragOffset = event.getY() >= metrics.thumbTop
                        && event.getY() <= metrics.thumbTop + metrics.thumbHeight
                        ? event.getY() - metrics.thumbTop : metrics.thumbHeight / 2f;
                disallowParentIntercept(true);
                moveTo(event.getY(), metrics);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                moveTo(event.getY(), metrics);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                disallowParentIntercept(false);
                invalidate();
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void moveTo(float touchY, Metrics metrics) {
        if (recyclerView == null) {
            return;
        }
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return;
        }
        int target = PlaylistScrollMath.targetPosition(touchY, dragOffset, getHeight(),
                metrics.thumbHeight, metrics.maximumFirstPosition);
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(target, 0);
        invalidate();
    }

    @Nullable
    private Metrics metrics() {
        if (recyclerView == null || getHeight() <= 0) {
            return null;
        }
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        if (adapter == null || !(manager instanceof LinearLayoutManager)) {
            return null;
        }
        int itemCount = adapter.getItemCount();
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int first = linear.findFirstVisibleItemPosition();
        int last = linear.findLastVisibleItemPosition();
        if (itemCount <= 0 || first == RecyclerView.NO_POSITION
                || last == RecyclerView.NO_POSITION) {
            return null;
        }
        int visible = Math.max(1, last - first + 1);
        if (itemCount <= visible) {
            return null;
        }
        int maximumFirst = Math.max(1, itemCount - visible);
        float thumbHeight = PlaylistScrollMath.thumbHeight(
                getHeight(), visible, itemCount, minimumThumbHeight);
        float top = PlaylistScrollMath.thumbTop(
                getHeight(), thumbHeight, first, maximumFirst);
        return new Metrics(top, thumbHeight, maximumFirst);
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Metrics {
        final float thumbTop;
        final float thumbHeight;
        final int maximumFirstPosition;

        Metrics(float thumbTop, float thumbHeight, int maximumFirstPosition) {
            this.thumbTop = thumbTop;
            this.thumbHeight = thumbHeight;
            this.maximumFirstPosition = maximumFirstPosition;
        }
    }
}
