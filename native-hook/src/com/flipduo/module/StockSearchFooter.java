// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TouchDelegate;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Wraps only the native edit TextView; its ID and click listener are never replaced. */
final class StockSearchFooter {
    private final WeakReference<TextView> edit;
    private final FrameLayout.LayoutParams original;
    private final int originalDescendantFocus;
    private WeakReference<LinearLayout> row = new WeakReference<>(null);

    StockSearchFooter(TextView edit) {
        this.edit = new WeakReference<>(edit);
        original = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) edit.getLayoutParams());
        originalDescendantFocus = ((FrameLayout) edit.getParent()).getDescendantFocusability();
    }

    void apply(boolean enabled, Runnable open) {
        TextView nativeEdit = edit.get(); if (nativeEdit == null) return;
        LinearLayout group = row.get();
        if (!enabled) {
            if (group == null || !(group.getParent() instanceof FrameLayout parent)) return;
            ((FrameLayout) nativeEdit.getParent()).removeView(nativeEdit); parent.removeView(group);
            parent.setDescendantFocusability(originalDescendantFocus);
            parent.addView(nativeEdit, new FrameLayout.LayoutParams(original));
            row.clear(); return;
        }
        if (group == null) {
            if (!(nativeEdit.getParent() instanceof FrameLayout parent)) return;
            parent.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            group = new LinearLayout(nativeEdit.getContext()); group.setGravity(Gravity.CENTER);
            group.setOrientation(LinearLayout.HORIZONTAL);
            TextView search = new TextView(nativeEdit.getContext());
            search.setText("搜索"); search.setContentDescription("搜索并打开本机应用");
            search.setGravity(nativeEdit.getGravity()); search.setIncludeFontPadding(nativeEdit.getIncludeFontPadding());
            search.setFocusable(true); search.setClickable(true); search.setOnClickListener(v -> open.run());
            parent.removeView(nativeEdit);
            addSlot(group, search, 0); addSlot(group, nativeEdit, dp(nativeEdit, 8));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            params.setMarginStart(dp(nativeEdit, 12)); params.setMarginEnd(dp(nativeEdit, 12));
            parent.addView(group, params); row = new WeakReference<>(group);
        }
        TextView search = (TextView) ((FrameLayout) group.getChildAt(0)).getChildAt(0);
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, nativeEdit.getTextSize()); search.setTypeface(nativeEdit.getTypeface());
        search.setTextColor(nativeEdit.getTextColors());
        Drawable background = nativeEdit.getBackground();
        if (background != null && background.getConstantState() != null)
            search.setBackground(background.getConstantState().newDrawable(nativeEdit.getResources(), nativeEdit.getContext().getTheme()).mutate());
        search.setPaddingRelative(nativeEdit.getPaddingStart(), nativeEdit.getPaddingTop(), nativeEdit.getPaddingEnd(), nativeEdit.getPaddingBottom());
        search.setElevation(nativeEdit.getElevation());
    }

    /** Expand only the hit area. Stretching the ROM's vector from 36dp to 48dp distorts its semicircular ends. */
    private void addSlot(LinearLayout group, TextView button, int gap) {
        FrameLayout slot = new FrameLayout(button.getContext());
        slot.setMinimumHeight(dp(button, 48)); slot.setClipChildren(false);
        slot.addView(button, new FrameLayout.LayoutParams(original.width, original.height, Gravity.CENTER));
        slot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                slot.setTouchDelegate(new TouchDelegate(new Rect(0, 0, r - l, b - t), button)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(gap); group.addView(slot, params);
    }

    private static int dp(TextView view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
}
