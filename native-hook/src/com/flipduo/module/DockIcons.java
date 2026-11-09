package com.flipduo.module;

import android.graphics.*;
import java.util.EnumMap;

/** Original 24-unit line icons, inspired by the reference's weight and optical balance. */
final class DockIcons {
    private final EnumMap<DockSpec.Action, Path> paths = new EnumMap<>(DockSpec.Action.class);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path widgets = new Path();

    DockIcons() {
        Path p = path(DockSpec.Action.BACK);
        p.moveTo(14.5f, 5.5f); p.lineTo(8, 12); p.lineTo(14.5f, 18.5f);
        p = path(DockSpec.Action.HOME);
        p.moveTo(3.7f, 10.7f); p.lineTo(12, 3.7f); p.lineTo(20.3f, 10.7f);
        p.moveTo(6, 9.2f); p.lineTo(6, 19.7f); p.lineTo(10, 19.7f); p.lineTo(10, 14);
        p.lineTo(14, 14); p.lineTo(14, 19.7f); p.lineTo(18, 19.7f); p.lineTo(18, 9.2f);
        p = path(DockSpec.Action.RECENTS);
        p.addRoundRect(6.5f, 7.2f, 20, 20, 2, 2, Path.Direction.CW);
        p.moveTo(16.5f, 4); p.lineTo(6, 4); p.quadTo(3.8f, 4, 3.8f, 6.2f); p.lineTo(3.8f, 16);
        p = path(DockSpec.Action.NOTIFICATIONS);
        p.moveTo(6.5f, 10); p.cubicTo(6.5f, 2.8f, 17.5f, 2.8f, 17.5f, 10);
        p.lineTo(17.5f, 14.3f); p.lineTo(20, 17); p.lineTo(4, 17); p.lineTo(6.5f, 14.3f); p.close();
        p.moveTo(10, 20); p.quadTo(12, 21.7f, 14, 20);
        p = path(DockSpec.Action.CONTROL_CENTER);
        p.addRoundRect(3, 4, 21, 9.5f, 2.75f, 2.75f, Path.Direction.CW);
        p.addRoundRect(3, 14.5f, 21, 20, 2.75f, 2.75f, Path.Direction.CW);
        p.addCircle(7.2f, 6.75f, 1, Path.Direction.CW); p.addCircle(16.8f, 17.25f, 1, Path.Direction.CW);
        p = path(DockSpec.Action.LOCK);
        p.addRoundRect(5, 10.3f, 19, 20.8f, 2.3f, 2.3f, Path.Direction.CW);
        p.moveTo(8, 10.3f); p.lineTo(8, 7); p.cubicTo(8, 1.7f, 16, 1.7f, 16, 7); p.lineTo(16, 10.3f);
        p.moveTo(12, 14.6f); p.lineTo(12, 17);
        p = path(DockSpec.Action.SETTINGS);
        p.addCircle(12, 12, 6.2f, Path.Direction.CW); p.addCircle(12, 12, 2.3f, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            p.moveTo(12 + 6.5f * (float)Math.cos(a), 12 + 6.5f * (float)Math.sin(a));
            p.lineTo(12 + 9 * (float)Math.cos(a), 12 + 9 * (float)Math.sin(a));
        }
        p = path(DockSpec.Action.CLOSE);
        p.moveTo(6.5f, 6.5f); p.lineTo(17.5f, 17.5f); p.moveTo(17.5f, 6.5f); p.lineTo(6.5f, 17.5f);
        widgets.addRoundRect(4, 4, 11, 11, 1.8f, 1.8f, Path.Direction.CW);
        widgets.addRoundRect(14, 4, 21, 11, 1.8f, 1.8f, Path.Direction.CW);
        widgets.addRoundRect(4, 14, 11, 21, 1.8f, 1.8f, Path.Direction.CW);
        widgets.addRoundRect(14, 14, 21, 21, 1.8f, 1.8f, Path.Direction.CW);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private Path path(DockSpec.Action action) { Path p = new Path(); paths.put(action, p); return p; }

    void draw(Canvas canvas, DockSpec.Action action, float x, float y, float size, int color) {
        canvas.save(); canvas.translate(x - size / 2, y - size / 2); canvas.scale(size / 24, size / 24);
        paint.setColor(color); paint.setStrokeWidth(1.75f);
        if (action == DockSpec.Action.MORE) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) canvas.drawCircle(6 + i * 6, 12, 1.55f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE); canvas.drawPath(paths.get(action), paint);
        }
        canvas.restore();
    }

    void widgets(Canvas canvas, float x, float y, float size, int color) {
        canvas.save(); canvas.translate(x - size / 2, y - size / 2); canvas.scale(size / 24, size / 24);
        paint.setColor(color); paint.setStrokeWidth(1.75f); paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(widgets, paint); canvas.restore();
    }
}
