package com.flipduo.module;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;

/** Complete framed cover preview. Mock app and lens artwork; controls use the actual renderer. */
public final class DockPreview extends FrameLayout {
    public static final int FRAME = CoverFrame.FRAME, MODEL_WIDTH = CoverFrame.WIDTH, MODEL_HEIGHT = CoverFrame.HEIGHT;
    public interface LayoutListener { void onPositionsChanged(String value); }
    private final NativeDock dock;
    private final CoverGeometry geometry;
    private final CoverFrame deviceFrame;
    private final CameraStatusView cameraPainter;
    private final CameraStatusState cameraSample = new CameraStatusState();
    private boolean cameraPreview;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path screen = new Path(), screenInView = new Path();
    private final Matrix transform = new Matrix();
    private Drawable column;
    private DockConfig config = new DockConfig();
    private DockLayout layout = new DockLayout(), dragOrigin;
    private LayoutListener listener;
    private boolean rotated, editable, dragging;
    private boolean editorHaptics = true;
    private int selected = -1, pointerId = -1;
    private float modelScale = 1, dx, dy, downX, downY, grabX, grabY;
    private final int touchSlop;
    private final Runnable minute = new Runnable() {
        @Override public void run() { dock.refreshPreviewClock(); postDelayed(this, 60000 - System.currentTimeMillis() % 60000); }
    };

    public DockPreview(Context context) {
        super(context); setWillNotDraw(false); setClipChildren(false);
        geometry = CoverGeometry.resolve(context); touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        deviceFrame = new CoverFrame(geometry);
        cameraPainter = new CameraStatusView(context); // Detached painter only; no listeners, broadcasts or timer actions.
        cameraSample.total = 60000; cameraSample.paused = true; cameraSample.pausedRemaining = 45000;
        dock = new NativeDock(context, true);
        dock.cameraStatus(cameraPainter);
        dock.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(dock, new FrameLayout.LayoutParams(DockSpec.WIDTH, DockSpec.HEIGHT));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void update(DockConfig value, SharedPreferences runtime) { update(value, runtime, false, false, null); }
    public void setEditorHaptics(boolean enabled) { editorHaptics = enabled; }
    public void setCameraPreview(boolean enabled) { cameraPreview = enabled; invalidate(); }
    public void update(DockConfig value, SharedPreferences runtime, boolean reverse, boolean edit, LayoutListener changed) {
        listener = changed;
        Intent status = new Intent();
        for (String key : new String[]{"battery", "wl", "ml"}) status.putExtra(key, runtime.getInt("status_" + key, -1));
        for (String key : new String[]{"charging", "saver", "wk", "we", "wc", "mk", "sim", "service", "air"})
            status.putExtra(key, runtime.getBoolean("status_" + key, false));
        status.putExtra("rat", runtime.getString("status_rat", ""));
        dock.previewState(StatusWire.decode(status));
        if (dragging && rotated == reverse && edit) return;
        cancelDrag(); rotated = reverse; editable = edit && !value.eventIsland; config = value;
        layout = DockLayout.decode(value.positions); dock.applyConfig(value);
        cameraPainter.configure(reverse, value);
        deviceFrame.configure(value);
        dock.applyAppearance(reverse);
        dock.applyNativeTone(value.background != CoverBackground.BLACK, value.background == CoverBackground.BLACK, false);
        column = new DockAppearance().column(reverse, geometry, value.systemCorners, value.symmetricCorners);
        setFocusable(edit); setContentDescription("完整外屏预览，" + (reverse ? "镜头左上、控制区左下" : "镜头右下、控制区右上")
                + "。应用内容和镜头为示意，控制区为实际绘制。"
                + (edit ? "双按钮组整体移动，等高控件可交换；方向键微调选中组。" : ""));
        rebuildPaths(); requestLayout(); invalidate();
    }

    private void rebuildPaths() {
        screen.set(deviceFrame.screenPath(rotated));
    }
    @Override protected void onMeasure(int ws, int hs) {
        int width = MeasureSpec.getSize(ws), height = MeasureSpec.getSize(hs);
        dock.measure(MeasureSpec.makeMeasureSpec(398, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(664, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        modelScale = Math.min(getWidth() / (float)MODEL_WIDTH, getHeight() / (float)MODEL_HEIGHT);
        dx = (getWidth() - MODEL_WIDTH * modelScale) / 2; dy = (getHeight() - MODEL_HEIGHT * modelScale) / 2;
        transform.reset(); transform.setScale(modelScale, modelScale); transform.postTranslate(dx, dy);
        screenInView.set(screen); screenInView.transform(transform);
        dock.layout(0, 0, 398, 664); dock.setPivotX(0); dock.setPivotY(0);
        dock.setScaleX(modelScale); dock.setScaleY(modelScale);
        dock.setTranslationX(dx + (FRAME + geometry.dockX(rotated)) * modelScale);
        dock.setTranslationY(dy + (FRAME + geometry.dockY(rotated)) * modelScale);
    }
    private void toModel(Canvas c) { c.translate(dx, dy); c.scale(modelScale, modelScale); }
    private void fill(int color) { paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.FILL); }
    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        fill(color); paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL)); paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT); c.drawText(value, x, y, paint);
    }
    @Override protected void onDraw(Canvas c) {
        if (column == null) return;
        c.save(); toModel(c);
        deviceFrame.drawShell(c, rotated);
        c.clipPath(screen); c.translate(FRAME, FRAME);
        fill(0xfff7f8fa); c.drawRect(0, 0, 1208, 1392, paint);
        mockApplication(c);
        if (config.background == CoverBackground.BLACK) {
            int width = 401 + geometry.appExtension(config.systemCorners, config.symmetricCorners);
            c.save(); c.translate(rotated ? 0 : 1208 - width, 0);
            column.setBounds(0, 0, width, 1392); column.draw(c); c.restore();
        }
        if (cameraPreview) {
            c.save(); c.translate(rotated ? 0 : 810 - NativeCameraGeometry.windowInset(config), 0);
            cameraPainter.render(c, cameraSample, rotated, 72, true, 0, 0, false); c.restore();
        }
        deviceFrame.drawCameras(c, rotated);
        c.restore();
    }
    private void mockApplication(Canvas c) {
        // The add button ends with ROUND caps; do not let that leak into the next preview's search icon.
        paint.setStrokeCap(Paint.Cap.BUTT);
        c.save(); int left = rotated ? 401 : 0; c.clipRect(left, 0, left + 807, 1392); c.translate(left, 0);
        text(c, "备忘录", 58, 173, 72, 0xff20252e, true);
        text(c, "模拟应用", 60, 239, 34, 0xff727987, false);
        fill(0xffe9ecf1); c.drawRoundRect(48, 285, 759, 377, 28, 28, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setColor(0xff7b8390);
        c.drawCircle(87, 326, 13, paint); c.drawLine(97, 336, 108, 347, paint);
        text(c, "搜索备忘录", 132, 344, 34, 0xff727987, false);
        String[] names = {"周末出行", "今天要做的事", "随手记"};
        String[] details = {"带上耳机、相机和充电器", "整理书桌，给植物浇水", "把突然想到的点子写下来"};
        for (int i = 0; i < 3; i++) {
            float y = 441 + i * 254;
            fill(0xffffffff); c.drawRoundRect(48, y, 759, y + 224, 30, 30, paint);
            text(c, names[i], 82, y + 66, 43, 0xff272c35, true);
            text(c, details[i], 82, y + 125, 31, 0xff7a818c, false);
            text(c, i == 0 ? "刚刚" : "昨天", 82, y + 184, 28, 0xff999faa, false);
        }
        fill(0xfff3c451); c.drawCircle(700, 1284, 49, paint);
        paint.setStrokeWidth(7); paint.setColor(0xff4f431b); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(680,1284,720,1284,paint); c.drawLine(700,1264,700,1304,paint);
        c.restore();
    }
    @Override protected void dispatchDraw(Canvas c) {
        c.save(); c.clipPath(screenInView); super.dispatchDraw(c); c.restore();
        if (editable && selected >= 0) {
            c.save(); toModel(c); c.translate(FRAME + geometry.dockX(rotated),
                    FRAME + geometry.dockY(rotated) + DockSpec.edgeOffset(config.offsetY, rotated));
            fill(0xff0878eb); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5);
            int anchor = DockLayout.unit(selected);
            c.drawRoundRect(layout.x[anchor] + 3, layout.y[anchor] + 3, layout.x[anchor] + 153,
                    layout.y[anchor] + DockLayout.members(anchor) * 156 - 3, 75, 75, paint);
            c.restore();
        }
    }
    private float localX(float x) { return (x - dx)/modelScale - FRAME - geometry.dockX(rotated); }
    private float localY(float y) { return (y - dy)/modelScale - FRAME - geometry.dockY(rotated) - DockSpec.edgeOffset(config.offsetY, rotated); }
    private int hit(float x, float y) {
        if (x < 0 || x > 398 || y < DockLayout.MIN_Y || y > 650) return -1;
        float limit = Math.max(78, 24 * getResources().getDisplayMetrics().density / modelScale);
        int result = -1; float distance = Float.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            float a = x - layout.x[i] - 78, b = y - layout.y[i] - 78, d = a*a + b*b;
            if (Math.abs(a) <= limit && Math.abs(b) <= limit && d < distance) { result = i; distance = d; }
        }
        return result;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!editable || listener == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selected = hit(localX(e.getX()), localY(e.getY())); if (selected < 0) return false;
                pointerId = e.getPointerId(0); downX = e.getX(); downY = e.getY(); dragOrigin = new DockLayout(layout);
                grabX = localX(downX) - layout.x[selected]; grabY = localY(downY) - layout.y[selected];
                getParent().requestDisallowInterceptTouchEvent(true); requestFocus(); invalidate(); return true;
            case MotionEvent.ACTION_POINTER_DOWN: cancelDrag(); return true;
            case MotionEvent.ACTION_MOVE:
                int pointer = e.findPointerIndex(pointerId); if (pointer < 0 || dragOrigin == null) return false;
                float x = e.getX(pointer), y = e.getY(pointer);
                if (!dragging && Math.hypot(x - downX, y - downY) < touchSlop) return true;
                if (!dragging && editorHaptics) performHapticFeedback(HapticFeedbackConstants.DRAG_START);
                dragging = true;
                layout = dragOrigin.dragged(selected, Math.round(localX(x) - grabX), Math.round(localY(y) - grabY));
                dock.previewLayout(layout); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if (dragOrigin == null) return false;
                if (dragging) {
                    DockLayout next = dragOrigin.place(selected, layout.x[selected], layout.y[selected]);
                    dragging = false; dragOrigin = null; layout = next; dock.previewLayout(next);
                    getParent().requestDisallowInterceptTouchEvent(false); listener.onPositionsChanged(next.encode());
                    if (editorHaptics) performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
                } else dragOrigin = null;
                pointerId = -1; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            case MotionEvent.ACTION_CANCEL: cancelDrag(); return true;
            default: return dragOrigin != null;
        }
    }
    private void cancelDrag() {
        if (dragOrigin != null) { layout = dragOrigin; dock.previewLayout(layout); }
        dragOrigin = null; dragging = false; pointerId = -1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (!editable || selected < 0 || listener == null) return super.onKeyDown(keyCode, event);
        int x = layout.x[selected], y = layout.y[selected];
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: x -= 4; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: x += 4; break;
            case KeyEvent.KEYCODE_DPAD_UP: y -= 4; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: y += 4; break;
            default: return super.onKeyDown(keyCode, event);
        }
        layout = layout.place(selected,x,y); dock.previewLayout(layout); listener.onPositionsChanged(layout.encode()); invalidate(); return true;
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(minute); }
    @Override protected void onDetachedFromWindow() { cancelDrag(); removeCallbacks(minute); super.onDetachedFromWindow(); }
}
