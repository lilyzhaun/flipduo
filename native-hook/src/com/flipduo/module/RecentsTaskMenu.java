// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from parallelcc/MixFlipMod, FlipHomeHook.kt, commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867.
// https://github.com/parallelcc/MixFlipMod — original author: parallelcc.
package com.flipduo.module;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;

/** Upstream full-screen PopupWindow, OEM resources, positions/pivots and 180/125 ms animations.
 * FlipDuo only supplies actions, restoration and its existing global animation preference. */
final class RecentsTaskMenu {
    private final View taskView, rootView;
    private final FrameLayout content;
    private final PopupWindow popup;
    private final Runnable restore;
    private final boolean animations;
    private boolean dismissing, finished;
    private ViewTreeObserver observer;
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { dismiss(); }
    };
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::validate;

    RecentsTaskMenu(View taskView, View stackView, boolean locked, Runnable lock, Runnable details, Runnable restore) {
        this.taskView = taskView; rootView = taskView.getRootView(); this.restore = restore;
        animations = NativeModule.config().animations;
        Context context = taskView.getContext();
        content = new FrameLayout(context); content.setClickable(true); content.setBackgroundColor(Color.TRANSPARENT);
        content.setOnClickListener(view -> dismiss());
        int size = dp(56), margin = dp(8);
        RecentsMenuGeometry.Position[] positions = RecentsMenuGeometry.calculate(anchor(stackView, size, margin));
        addItem(positions[0], size, locked ? "ic_task_unlock" : "ic_task_lock",
                locked ? android.R.drawable.ic_menu_revert : android.R.drawable.ic_lock_idle_lock,
                locked ? "解锁" : "锁定", lock);
        addItem(positions[1], size, "ic_task_setting", android.R.drawable.ic_menu_info_details, "应用信息", details);
        popup = new PopupWindow(context);
        popup.setContentView(content);
        popup.setWidth(ViewGroup.LayoutParams.MATCH_PARENT); popup.setHeight(ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setFocusable(false); popup.setOutsideTouchable(true); popup.setElevation(dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.TRANSPARENT);
        popup.setBackgroundDrawable(background);
        popup.setOnDismissListener(this::finish);
    }

    private RecentsMenuGeometry.Anchor anchor(View stackView, int size, int margin) {
        Rect task = new Rect(0, 0, taskView.getWidth(), taskView.getHeight());
        Rect stack = new Rect(0, 0, stackView.getWidth(), stackView.getHeight());
        if (rootView instanceof ViewGroup group) {
            group.offsetDescendantRectToMyCoords(taskView, task);
            group.offsetDescendantRectToMyCoords(stackView, stack);
        }
        int width = rootView.getWidth() > 0 ? rootView.getWidth() : taskView.getResources().getDisplayMetrics().widthPixels;
        int height = rootView.getHeight() > 0 ? rootView.getHeight() : taskView.getResources().getDisplayMetrics().heightPixels;
        return new RecentsMenuGeometry.Anchor(task.left, task.top, task.right, task.bottom,
                task.top + taskView.getHeight() / 2, width, height, size, margin,
                task.left + taskView.getWidth() / 2 <= stack.centerX());
    }
    private int dp(int value) { return (int) (value * taskView.getResources().getDisplayMetrics().density + .5f); }
    private int drawableId(String name, int fallback) {
        Context context = taskView.getContext();
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        return id == 0 ? fallback : id;
    }
    private Drawable background(int size) {
        int id = drawableId("recent_menu_bg", 0);
        if (id != 0) return taskView.getContext().getDrawable(id);
        GradientDrawable fallback = new GradientDrawable(); fallback.setShape(GradientDrawable.RECTANGLE);
        fallback.setCornerRadius(size / 2f); fallback.setColor(Color.argb(235, 245, 245, 245)); return fallback;
    }
    private void addItem(RecentsMenuGeometry.Position position, int size, String icon, int fallback, String label, Runnable action) {
        ImageView item = new ImageView(taskView.getContext());
        item.setClickable(true); item.setFocusable(true); item.setContentDescription(label);
        int padding = dp(14); item.setPadding(padding, padding, padding, padding);
        item.setBackground(background(size)); item.setImageResource(drawableId(icon, fallback));
        item.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        item.setOnClickListener(view -> { if (!dismissing) { action.run(); dismiss(); } });
        item.setPivotX(position.pivotX()); item.setPivotY(position.pivotY());
        item.setAlpha(0f); item.setScaleX(.6f); item.setScaleY(.6f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = position.x(); params.topMargin = position.y(); content.addView(item, params);
        item.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(animations ? 180L : 0L).start();
    }
    void show() {
        taskView.addOnAttachStateChangeListener(attachment);
        observer = rootView.getViewTreeObserver(); observer.addOnGlobalLayoutListener(layout);
        try { popup.showAtLocation(rootView, Gravity.TOP | Gravity.START, 0, 0); }
        catch (RuntimeException error) { closeImmediately(); throw error; }
    }
    boolean isShowing() { return popup.isShowing(); }
    private void validate() {
        if (!taskView.isAttachedToWindow() || !taskView.isShown() || !rootView.isShown()) dismiss();
    }
    void dismiss() {
        if (dismissing || finished) return;
        dismissing = true;
        int[] remaining = {content.getChildCount()};
        if (remaining[0] == 0) { closeImmediately(); return; }
        for (int i = 0; i < content.getChildCount(); i++) {
            View item = content.getChildAt(i); item.animate().cancel();
            item.animate().alpha(0f).scaleX(.6f).scaleY(.6f).setDuration(animations ? 125L : 0L)
                    .withEndAction(() -> { if (--remaining[0] == 0) closeImmediately(); }).start();
        }
    }
    // Finish the old state before opening a replacement or changing the feature preference.
    void closeImmediately() { popup.dismiss(); finish(); }
    private void finish() {
        if (finished) return;
        finished = true;
        taskView.removeOnAttachStateChangeListener(attachment);
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
        for (int i = 0; i < content.getChildCount(); i++) content.getChildAt(i).animate().cancel();
        restore.run();
    }
}
