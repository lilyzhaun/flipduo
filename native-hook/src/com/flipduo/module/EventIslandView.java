package com.flipduo.module;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;

/** Upright event content, not a rotated notification. Native buttons remain in their original SystemUI card. */
final class EventIslandView extends ViewGroup {
    private final View event, notifications, widgets;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DockIcons icons = new DockIcons();
    private EventIslandState state = new EventIslandState();
    private DockAppearance appearance = new DockAppearance();
    private Drawable icon;
    private boolean monochromeIcon;
    private Runnable openEvent, openNotifications, openWidgets;
    private final boolean previewOnly;
    private boolean widgetsAvailable = true;
    private boolean horizontal;
    private boolean controlsHidden;

    EventIslandView(Context context, boolean preview) {
        super(context); previewOnly = preview; setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),Math.min(view.getWidth(),view.getHeight())/2f); }
        });
        event = new View(context) {
            @Override protected void onDraw(Canvas c) { drawEvent(c); }
        };
        notifications = new View(context) {
            @Override protected void onDraw(Canvas c) {
                c.save(); c.scale(getWidth()/156f, getHeight()/156f);
                int shown = shownCount();
                icons.draw(c, DockSpec.Action.NOTIFICATIONS, 78, shown > 0 ? 62 : 78, 56, appearance.ink);
                if (shown > 0) text(c, shown > 99 ? "99+" : String.valueOf(shown), 78, 118, 29, appearance.secondary);
                c.restore();
            }
        };
        widgets = new View(context) {
            @Override protected void onDraw(Canvas c) {
                c.save(); c.scale(getWidth()/156f, getHeight()/156f);
                icons.widgets(c, 78, 78, 58, appearance.ink); c.restore();
            }
        };
        widgets.setContentDescription("展开系统小组件，上滑收起，下滑切换小组件");
        for (View child : new View[]{event, notifications, widgets}) {
            child.setClickable(!preview); child.setFocusable(!preview);
            child.setImportantForAccessibility(preview ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            child.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
                }
            });
            child.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x24ffffff), null,
                    new android.graphics.drawable.ColorDrawable(Color.WHITE)));
            addView(child);
        }
        if (!preview) {
            event.setOnClickListener(v -> { if (!controlsHidden && openEvent != null && !touchYield) openEvent.run(); });
            notifications.setOnClickListener(v -> { if (!controlsHidden && openNotifications != null && !touchYield) openNotifications.run(); });
            widgets.setOnClickListener(v -> { if (!controlsHidden && !touchYield && !horizontal && widgetsAvailable && !state.focus && !state.urgent && openWidgets != null) openWidgets.run(); });
        }
        update(new EventIslandState());
    }
    void actions(Runnable eventAction, Runnable notificationAction, Runnable widgetAction) {
        openEvent = eventAction; openNotifications = notificationAction; openWidgets = widgetAction;
    }
    int modelHeight() { return state.focus ? IslandGeometry.HEIGHT : state.urgent ? 156 : 312; }
    void controlsHidden(boolean hidden) {
        controlsHidden = hidden;
        applyEnabled(); applyAccessibility();
    }
    private boolean touchYield, accessibilityYield;
    /** Native content owns the slot and the island is not drawn: it yields touch and accessibility with the drawing (SlotOwnership). */
    void slotPermissions(boolean touch, boolean accessible) {
        if (touchYield == !touch && accessibilityYield == !accessible) return;
        touchYield = !touch; accessibilityYield = !accessible;
        applyEnabled(); applyAccessibility();
    }
    private void applyEnabled() {
        boolean active = !controlsHidden && !touchYield;
        setEnabled(active);
        event.setEnabled(!previewOnly && active); notifications.setEnabled(!previewOnly && active);
        widgets.setEnabled(!previewOnly && active && widgetsAvailable && !horizontal);
    }
    private void applyAccessibility() {
        setImportantForAccessibility(controlsHidden || accessibilityYield || previewOnly
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    /** F04 option: while visual focus is on, hide only the number; the entry stays visible and clickable. */
    private boolean hideCount;
    void hideNotificationCount(boolean value) {
        if (hideCount == value) return;
        hideCount = value; describeNotifications(); notifications.invalidate();
    }
    private int shownCount() { return hideCount ? 0 : state.notifications; }
    private void describeNotifications() {
        int shown = shownCount();
        notifications.setContentDescription(shown > 0 ? "通知，" + shown + "条，点按打开通知中心" : "打开通知中心");
    }
    void horizontal(boolean value) {
        if (horizontal == value) return;
        horizontal = value; widgetsAvailable(widgetsAvailable); requestLayout(); invalidate(); event.invalidate();
    }
    void widgetsAvailable(boolean available) {
        widgetsAvailable = available;
        boolean enabled = available && !horizontal;
        widgets.setEnabled(!previewOnly && !controlsHidden && !touchYield && enabled); widgets.setAlpha(enabled ? 1 : .38f);
        widgets.setContentDescription(horizontal ? "横屏暂不支持系统小组件" : available ? "展开系统小组件，上滑收起，下滑切换小组件" : "暂无系统小组件");
    }
    void addTouchableRegion(Region region, int dockLeft, int dockTop) {
        if (controlsHidden) return;
        if (touchYield) return;
        for (View child : new View[]{event, notifications, widgets}) {
            if (child.getVisibility() != VISIBLE || child == widgets && (!widgetsAvailable || horizontal)) continue;
            Rect bounds = new Rect(); child.getHitRect(bounds);
            bounds.offset(dockLeft + getLeft(), dockTop + getTop()); region.op(bounds, Region.Op.UNION);
        }
    }
    void tone(DockAppearance value) {
        appearance = value; applyIconTone(); invalidate(); event.invalidate(); notifications.invalidate(); widgets.invalidate();
    }
    void update(EventIslandState value) {
        boolean iconChanged = state.generation != value.generation;
        state = value;
        if (iconChanged || (icon == null && value.icon != null)) {
            icon = value.icon == null ? null : value.icon.loadDrawable(getContext());
            if (icon != null) icon = icon.mutate();
            monochromeIcon = icon != null && monochrome(icon);
            applyIconTone();
        }
        event.setVisibility(value.focus ? VISIBLE : GONE);
        widgets.setVisibility(value.focus || value.urgent ? GONE : VISIBLE);
        event.setContentDescription(value.app + (!value.clock.isEmpty() ? "，" + value.clock
                : value.progress >= 0 ? "，进度" + value.progress + "%" : "，进行中的活动")
                + (value.events > 1 ? "，共" + value.events + "个活动" : "") + "，点按展开原生操作");
        describeNotifications();
        requestLayout(); invalidate(); event.invalidate(); notifications.invalidate(); widgets.invalidate();
    }
    private void applyIconTone() {
        // Notification masks (including the native alarm/timer glyph) follow the foreground. Colored app marks do not.
        if (icon != null && monochromeIcon) icon.setTint(appearance.ink);
    }
    private static boolean monochrome(Drawable drawable) {
        Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        Rect original = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, 32, 32); drawable.draw(new Canvas(image));
            boolean visible = false, transparent = false;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int color = image.getPixel(x, y);
                if (Color.alpha(color) < 32) { transparent = true; continue; }
                visible = true;
                int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 12) return false;
            }
            return visible && transparent;
        } finally { drawable.setBounds(original); image.recycle(); }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        if (horizontal) {
            int footer = Math.round(w * 156f / modelHeight());
            event.measure(MeasureSpec.makeMeasureSpec(state.focus ? Math.max(0, w - footer) : 0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            notifications.measure(MeasureSpec.makeMeasureSpec(footer, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            widgets.measure(MeasureSpec.makeMeasureSpec(state.focus ? 0 : Math.max(0, w - footer), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            setMeasuredDimension(w, h); return;
        }
        int footer = Math.round(h * 156f / modelHeight());
        event.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(state.focus ? Math.max(0,h-footer) : 0, MeasureSpec.EXACTLY));
        notifications.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(footer, MeasureSpec.EXACTLY));
        widgets.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(state.focus ? 0 : h-footer, MeasureSpec.EXACTLY));
        setMeasuredDimension(w,h);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (horizontal) {
            int x = state.focus ? event.getMeasuredWidth() : 0;
            event.layout(0, 0, x, getHeight());
            notifications.layout(x, 0, x + notifications.getMeasuredWidth(), getHeight());
            widgets.layout(x + notifications.getMeasuredWidth(), 0, getWidth(), getHeight()); return;
        }
        int y = state.focus ? event.getMeasuredHeight() : 0;
        event.layout(0,0,getWidth(),y);
        notifications.layout(0,y,getWidth(),y+notifications.getMeasuredHeight());
        widgets.layout(0,y+notifications.getMeasuredHeight(),getWidth(),getHeight());
    }
    @Override protected void onDraw(Canvas c) {
        if (state.urgent && !state.focus) return;
        paint.setStyle(Paint.Style.STROKE); paint.setColor(appearance.ink); paint.setAlpha(28); paint.setStrokeWidth(1.2f);
        if (horizontal) {
            int divider = state.focus ? event.getMeasuredWidth() : notifications.getMeasuredWidth();
            c.drawLine(divider, getHeight() * .22f, divider, getHeight() * .78f, paint); return;
        }
        int divider = state.focus ? event.getMeasuredHeight() : notifications.getMeasuredHeight();
        c.drawLine(getWidth()*.22f, divider,getWidth()*.78f,divider,paint);
    }
    private void drawEvent(Canvas c) {
        if (horizontal) { drawHorizontalEvent(c); return; }
        c.save(); c.scale(event.getWidth()/156f, event.getHeight()/338f);
        if (icon != null) { icon.setBounds(45,42,111,108); icon.draw(c); }
        else { // A clock silhouette, never invented activity text.
            paint.setColor(appearance.ink); paint.setAlpha(255); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
            c.drawCircle(78,75,25,paint); c.drawLine(78,75,78,59,paint); c.drawLine(78,75,91,82,paint); paint.setStyle(Paint.Style.FILL);
        }
        IslandSummary summary = IslandSummary.choose(state.clock, state.progress, state.app);
        if (summary.kind == IslandSummary.Kind.APP) text(c,fit(summary.text,29,134),78,181,29,appearance.ink);
        else text(c,summary.text,78,181,41,appearance.ink);
        if (state.events > 1) text(c,"+" + (state.events-1),78,231,27,appearance.secondary);
        paint.setColor(appearance.secondary); paint.setAlpha(255); paint.setStrokeWidth(3.5f); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); c.drawLine(69,282,78,291,paint); c.drawLine(78,291,87,282,paint);
        paint.setStyle(Paint.Style.FILL); c.restore();
    }
    private void drawHorizontalEvent(Canvas c) {
        c.save(); c.scale(event.getWidth() / 338f, event.getHeight() / 156f);
        if (icon != null) { icon.setBounds(32, 45, 98, 111); icon.draw(c); }
        else {
            paint.setColor(appearance.ink); paint.setAlpha(255); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
            c.drawCircle(65, 78, 25, paint); c.drawLine(65, 78, 65, 62, paint); c.drawLine(65, 78, 78, 85, paint);
        }
        IslandSummary summary = IslandSummary.choose(state.clock, state.progress, state.app);
        boolean app = summary.kind == IslandSummary.Kind.APP;
        text(c, app ? fit(summary.text, 29, 134) : summary.text, 206, state.events > 1 ? 76 : 92, app ? 29 : 41, appearance.ink);
        if (state.events > 1) text(c, "+" + (state.events - 1), 206, 119, 27, appearance.secondary);
        c.restore();
    }
    /** App name ellipsized by measured width at grapheme boundaries; very long names never split a character. */
    private String fit(String value, float size, float max) {
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF,600,false)); paint.setTextSize(size);
        if (paint.measureText(value) <= max) return value;
        for (int n = IslandSummary.graphemes(value) - 1; n > 0; n--) {
            String candidate = IslandSummary.prefix(value, n) + "…";
            if (paint.measureText(candidate) <= max) return candidate;
        }
        return IslandSummary.prefix(value, 1);
    }
    private void text(Canvas c, String value, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setAlpha(Color.alpha(color));
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF,600,false)); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size); float width = paint.measureText(value);
        if (width > 134) paint.setTextSize(size*134/width);
        c.drawText(value,x,y,paint);
    }
}
