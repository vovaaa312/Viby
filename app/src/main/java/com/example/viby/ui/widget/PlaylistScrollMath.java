package com.example.viby.ui.widget;

/** Pure geometry used by the draggable playlist scrollbar. */
final class PlaylistScrollMath {

    private PlaylistScrollMath() {
    }

    static float thumbHeight(int viewHeight, int visibleItems, int itemCount,
                             float minimumThumbHeight) {
        if (viewHeight <= 0 || visibleItems <= 0 || itemCount <= visibleItems) {
            return 0f;
        }
        float proportional = viewHeight * (visibleItems / (float) itemCount);
        return Math.min(viewHeight, Math.max(minimumThumbHeight, proportional));
    }

    static float thumbTop(int viewHeight, float thumbHeight,
                          int firstVisiblePosition, int maximumFirstPosition) {
        if (viewHeight <= 0 || maximumFirstPosition <= 0) {
            return 0f;
        }
        float fraction = clamp(firstVisiblePosition / (float) maximumFirstPosition,
                0f, 1f);
        return fraction * Math.max(0f, viewHeight - thumbHeight);
    }

    static int targetPosition(float touchY, float dragOffset, int viewHeight,
                              float thumbHeight, int maximumFirstPosition) {
        if (maximumFirstPosition <= 0) {
            return 0;
        }
        float available = Math.max(1f, viewHeight - thumbHeight);
        float top = clamp(touchY - dragOffset, 0f, available);
        return Math.round(top / available * maximumFirstPosition);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
