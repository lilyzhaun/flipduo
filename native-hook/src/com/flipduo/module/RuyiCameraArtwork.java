package com.flipduo.module;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** First-generation MIX Flip illustration, not a physical occlusion/calibration profile.
 * Appearance: Xiaomi's original MIX Flip product image (Dynamic-widgets-new.jpg).
 * Centers: the ruyi ROM avoidance-cell baseline plus the user's existing position offsets.
 * In our natural orientation the lower housing contains the smaller lens above the flash.
 * See RUYI_SCREENSHOT.md for the source and the explicitly approximate optical proportions. */
final class RuyiCameraArtwork {
    static final float RADIUS = 152, MAIN_LENS = 54, LOWER_LENS = 31;
    static final float LOWER_LENS_OFFSET = -34, FLASH_OFFSET = 80, FLASH_RADIUS = 21;

    static void draw(Canvas c, Paint p, NativeCameraGeometry g) {
        housing(c, p, g.x, g.mainY);
        housing(c, p, g.lowerX, g.lowerY);
        lens(c, p, g.x, g.mainY, MAIN_LENS);
        lens(c, p, g.lowerX, g.lowerY + LOWER_LENS_OFFSET, LOWER_LENS);
        float x = g.lowerX, y = g.lowerY + FLASH_OFFSET;
        fill(p, 0xff696b69); c.drawCircle(x, y, FLASH_RADIUS + 3, p);
        fill(p, 0xffe3e0cb); c.drawCircle(x, y, FLASH_RADIUS, p);
        fill(p, 0xfff5f3e5); c.drawOval(x - 15, y - 16, x + 1, y + 12, p);
        fill(p, 0xffc9c4a7); c.drawOval(x + 3, y - 13, x + 15, y + 13, p);
        p.setShader(null);
    }
    private static void housing(Canvas c, Paint p, float x, float y) {
        fill(p, 0xff090a0b); c.drawCircle(x, y, RADIUS + 3, p);
        p.setShader(new LinearGradient(x - RADIUS, y - RADIUS, x + RADIUS, y + RADIUS,
                new int[]{0xff55585b, 0xffb4b8bc, 0xff53565a, 0xff94989c},
                new float[]{0, .36f, .7f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, RADIUS, p);
        fill(p, 0xff0b0c0e); c.drawCircle(x, y, RADIUS - 6, p);
        p.setShader(new LinearGradient(x + RADIUS, y + RADIUS, x - RADIUS, y - RADIUS,
                new int[]{0xff323538, 0xff16181b, 0xff08090b},
                new float[]{0, .48f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, RADIUS - 10, p);
    }
    private static void lens(Canvas c, Paint p, float x, float y, float radius) {
        fill(p, 0xff25292f); c.drawCircle(x, y, radius + 5, p);
        p.setShader(new RadialGradient(x - radius * .12f, y + radius * .2f, radius,
                new int[]{0xff334b68, 0xff102233, 0xff03070c},
                new float[]{0, .52f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, p);
        fill(p, 0xff060b12); c.drawCircle(x, y, radius * .42f, p);
        fill(p, 0xa877a7cf);
        c.drawOval(x - radius * .62f, y + radius * .12f, x - radius * .08f, y + radius * .57f, p);
        fill(p, 0x866d62b6);
        c.drawOval(x + radius * .08f, y - radius * .52f, x + radius * .4f, y - radius * .12f, p);
    }
    private static void fill(Paint p, int color) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(color);
    }
}
