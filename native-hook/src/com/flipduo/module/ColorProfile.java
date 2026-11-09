package com.flipduo.module;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Only 72 opaque colour values, never an image or a screenshot retained in the UI. */
final class ColorProfile extends Drawable {
    static final int BANDS = 72;
    private final Paint paint = new Paint();
    private final int[] colors;
    private final int originY;
    ColorProfile(int[] colors, int originY) { this.colors = colors.clone(); this.originY = originY; }

    int at(float y) {
        float index = Math.max(0, Math.min(BANDS - 1, (y + originY) * (BANDS - 1) / 1392f));
        int a = (int) index, b = Math.min(BANDS - 1, a + 1);
        return blend(colors[a], colors[b], index - a);
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
    static boolean dark(int c) { return .2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c) < 135; }
    @Override protected void onBoundsChange(Rect bounds) {
        paint.setShader(new LinearGradient(0, -originY, 0, 1392 - originY, colors, null, Shader.TileMode.CLAMP));
    }
    @Override public void draw(Canvas c) { c.drawRect(getBounds(), paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }

    static int[] from(Bitmap strip) {
        int[] result = new int[BANDS];
        int[] count = new int[4096], red = new int[4096], green = new int[4096], blue = new int[4096];
        for (int band = 0; band < BANDS; band++) {
            java.util.Arrays.fill(count, 0); java.util.Arrays.fill(red, 0); java.util.Arrays.fill(green, 0); java.util.Arrays.fill(blue, 0);
            int winner = 0;
            int start = band * strip.getHeight() / BANDS, end = Math.max(start + 1, (band + 1) * strip.getHeight() / BANDS);
            for (int y = start; y < end && y < strip.getHeight(); y++) for (int x = 0; x < strip.getWidth(); x++) {
                int c = strip.getPixel(x, y), r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int bucket = (r >> 4) << 8 | (g >> 4) << 4 | b >> 4;
                count[bucket]++; red[bucket] += r; green[bucket] += g; blue[bucket] += b;
                if (count[bucket] > count[winner]) winner = bucket;
            }
            int n = Math.max(1, count[winner]);
            result[band] = Color.rgb(red[winner] / n, green[winner] / n, blue[winner] / n);
        }
        return result;
    }
}
