package com.example.viby.ui;

import android.view.View;
import android.widget.FrameLayout;

/** Helpers for text fields placed in alert dialogs. */
final class DialogInputs {

    private DialogInputs() {
    }

    /** Wraps a field so it lines up with the dialog title instead of touching the edges. */
    static View padded(View input) {
        FrameLayout container = new FrameLayout(input.getContext());
        int horizontal = Math.round(24 * input.getResources().getDisplayMetrics().density);
        int top = Math.round(8 * input.getResources().getDisplayMetrics().density);
        container.setPadding(horizontal, top, horizontal, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return container;
    }
}
