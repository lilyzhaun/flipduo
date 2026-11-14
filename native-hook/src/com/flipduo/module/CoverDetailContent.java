// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import java.util.WeakHashMap;

/** Reflow native detail content; do not scale its render surface or change host density. */
final class CoverDetailContent {
    private final WeakHashMap<View, Sizes> originals = new WeakHashMap<>();

    void apply(ViewGroup container) {
        float touch = Math.round(48 * container.getResources().getDisplayMetrics().density);
        for (int i = 0; i < container.getChildCount(); i++) compact(container.getChildAt(i), touch);
    }

    private void compact(View view, float touch) {
        // SlidingButton owns its thumb geometry and native hit target.
        if (view instanceof CompoundButton) return;
        originals.computeIfAbsent(view, Sizes::new).apply(view, .8f, touch);
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) compact(group.getChildAt(i), touch);
    }

    void restore() {
        for (var entry : originals.entrySet()) entry.getValue().apply(entry.getKey(), 1, 0);
        originals.clear();
    }

    private static final class Sizes {
        final float[] original, applied;
        Sizes(View view) { original = read(view); applied = original.clone(); }

        void apply(View view, float scale, float touch) {
            float[] current = read(view), next = current.clone();
            for (int i = 0; i < current.length; i++) {
                if (current[i] != applied[i]) original[i] = current[i]; // Native rebind/configuration update.
                next[i] = original[i] > 0 ? (i == 12 ? original[i] * scale : Math.round(original[i] * scale)) : original[i];
            }
            if (current[13] != Integer.MIN_VALUE) {
                // Android resolves start/end into left/right during layout. Do not
                // rescale those derived values on the next global-layout callback.
                next[8] = current[8]; next[10] = current[10];
            }
            if (scale != 1 && view instanceof TextView)
                next[12] = Math.min(original[12], Math.max(next[12],
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, view.getResources().getDisplayMetrics())));
            if (scale != 1 && (view.isClickable() || view.isLongClickable())) {
                next[2] = Math.max(next[2], touch); next[3] = Math.max(next[3], touch);
                if (next[0] > 0) next[0] = Math.max(next[0], touch);
                if (next[1] > 0) next[1] = Math.max(next[1], touch);
            }
            write(view, current, next);
            System.arraycopy(next, 0, applied, 0, next.length);
        }

        static float[] read(View v) {
            ViewGroup.LayoutParams p = v.getLayoutParams();
            float[] values = {p == null ? -2 : p.width, p == null ? -2 : p.height,
                    v.getMinimumWidth(), v.getMinimumHeight(), v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom(), 0, 0, 0, 0,
                    v instanceof TextView text ? text.getTextSize() : 0, Integer.MIN_VALUE, Integer.MIN_VALUE};
            if (p instanceof ViewGroup.MarginLayoutParams m) {
                values[8] = m.leftMargin; values[9] = m.topMargin;
                values[10] = m.rightMargin; values[11] = m.bottomMargin;
                if (m.isMarginRelative()) { values[13] = m.getMarginStart(); values[14] = m.getMarginEnd(); }
            }
            return values;
        }

        static void write(View v, float[] old, float[] next) {
            ViewGroup.LayoutParams p = v.getLayoutParams();
            boolean layout = false;
            if (p != null && (old[0] != next[0] || old[1] != next[1])) {
                p.width = (int) next[0]; p.height = (int) next[1]; layout = true;
            }
            if (p instanceof ViewGroup.MarginLayoutParams m
                    && (old[8] != next[8] || old[9] != next[9] || old[10] != next[10] || old[11] != next[11])) {
                m.setMargins((int) next[8], (int) next[9], (int) next[10], (int) next[11]);
                layout = true;
            }
            if (p instanceof ViewGroup.MarginLayoutParams m && (old[13] != next[13] || old[14] != next[14])) {
                m.setMarginStart((int) next[13]); m.setMarginEnd((int) next[14]); layout = true;
            }
            if (layout) v.setLayoutParams(p);
            if (old[2] != next[2]) v.setMinimumWidth((int) next[2]);
            if (old[3] != next[3]) v.setMinimumHeight((int) next[3]);
            if (old[4] != next[4] || old[5] != next[5] || old[6] != next[6] || old[7] != next[7])
                v.setPadding((int) next[4], (int) next[5], (int) next[6], (int) next[7]);
            if (v instanceof TextView text && old[12] != next[12])
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, next[12]);
        }
    }
}
