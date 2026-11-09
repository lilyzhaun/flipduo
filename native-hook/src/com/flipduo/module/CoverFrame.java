package com.flipduo.module;

import android.graphics.*;
import android.os.Build;

/** Device-selected artwork shared by the editor and exported cover screenshots. */
public final class CoverFrame {
    public static final int FRAME = 38, WIDTH = CoverGeometry.WIDTH + FRAME * 2, HEIGHT = CoverGeometry.HEIGHT + FRAME * 2;
    // Default centers share the status rings; local optical artwork/flash proportions stay unchanged.
    public static final float CAMERA_X = NativeCameraGeometry.BIXI.x, MAIN_Y = NativeCameraGeometry.BIXI.mainY,
            ULTRA_Y = NativeCameraGeometry.BIXI.lowerY, RING_RADIUS = CameraOcclusion.RADIUS;
    public static final float FLASH_Y = ULTRA_Y + 80, FLASH_RADIUS = 18;
    private final CoverGeometry geometry;
    private final NativeCameraGeometry baseCameras;
    private final boolean detailedCameras;
    private final boolean ruyiArtwork;
    private NativeCameraGeometry cameras;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public CoverFrame(CoverGeometry geometry) { this(geometry, Build.DEVICE); }
    CoverFrame(CoverGeometry geometry, String device) {
        this.geometry = geometry; baseCameras = NativeCameraGeometry.forDevice(device); cameras = baseCameras;
        detailedCameras = CameraOcclusion.hasOpticalProfile(device);
        ruyiArtwork = "ruyi".equals(device); // Export illustration is separate from physical occlusion.
    }
    public CoverFrame(CoverGeometry geometry, DockConfig config) { this(geometry); configure(config); }
    public void configure(DockConfig config) { cameras = baseCameras.adjusted(config); }

    public Path screenPath(boolean rotated) {
        Path path = new Path(); float t = geometry.top(rotated), b = geometry.bottom(rotated);
        path.addRoundRect(new RectF(FRAME, FRAME, FRAME + CoverGeometry.WIDTH, FRAME + CoverGeometry.HEIGHT),
                new float[]{t,t,t,t,b,b,b,b}, Path.Direction.CW);
        return path;
    }
    public Path screenPath(int rotation) {
        if (!CoverRotation.landscape(rotation)) return screenPath(rotation == 2);
        Path path = screenPath(false); Matrix matrix = new Matrix();
        matrix.setRotate(rotation == 1 ? -90 : 90);
        matrix.postTranslate(rotation == 1 ? 0 : HEIGHT, rotation == 1 ? WIDTH : 0);
        path.transform(matrix); return path;
    }
    private void fill(int color) { paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color); }

    public void drawShell(Canvas c, boolean rotated) {
        Path shell = new Path(); float t = geometry.top(rotated) + FRAME, b = geometry.bottom(rotated) + FRAME;
        shell.addRoundRect(new RectF(3, 3, WIDTH - 3, HEIGHT - 3), new float[]{t,t,t,t,b,b,b,b}, Path.Direction.CW);
        fill(0xff2a2c30); c.drawPath(shell, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4); paint.setColor(0xff85878c); c.drawPath(shell, paint);
    }
    public void drawShell(Canvas c, int rotation) {
        if (!CoverRotation.landscape(rotation)) { drawShell(c, rotation == 2); return; }
        c.save(); rotateCanvas(c, rotation, WIDTH, HEIGHT); drawShell(c, false); c.restore();
    }
    private static void rotateCanvas(Canvas c, int rotation, int width, int height) {
        if (rotation == 1) { c.translate(0, width); c.rotate(-90); }
        else if (rotation == 3) { c.translate(height, 0); c.rotate(90); }
    }

    /** Coordinates here are inside the display, without the outer frame. */
    public void drawCameras(Canvas c, boolean rotated) {
        // Unknown covers retain native pixels. Ruyi gets its own illustration, never BIXI optics.
        if (!detailedCameras && !ruyiArtwork) return;
        c.save();
        if (rotated) { c.translate(CoverGeometry.WIDTH, CoverGeometry.HEIGHT); c.rotate(180); }
        if (ruyiArtwork) { RuyiCameraArtwork.draw(c, paint, cameras); c.restore(); return; }
        float mainX = cameras.x, mainY = cameras.mainY, lowerX = cameras.lowerX, lowerY = cameras.lowerY;
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? mainX : lowerX, y = i == 0 ? mainY : lowerY;
            fill(0xff070809); c.drawCircle(x, y, RING_RADIUS + 3, paint);
            paint.setShader(new LinearGradient(x - 154, y - 154, x + 154, y + 154,
                    new int[]{0xffc6c7c9, 0xff6f7276, 0xffb0b2b5, 0xff66696e}, new float[]{0,.35f,.7f,1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, RING_RADIUS, paint);
            fill(0xff050608); c.drawCircle(x, y, RING_RADIUS - 9, paint);
        }
        lens(c, mainX, mainY, 66);
        lens(c, lowerX, lowerY - 24, 47);
        float flashY = lowerY + (FLASH_Y - ULTRA_Y);
        fill(0xff666560); c.drawCircle(lowerX, flashY, FLASH_RADIUS + 3, paint);
        fill(0xffe9e3c9); c.drawCircle(lowerX, flashY, FLASH_RADIUS, paint);
        fill(0xfff5f0dc); c.drawCircle(lowerX - 3, flashY - 3, FLASH_RADIUS * .58f, paint);
        c.restore();
    }
    public void drawCameras(Canvas c, int rotation) {
        if (!CoverRotation.landscape(rotation)) { drawCameras(c, rotation == 2); return; }
        c.save(); rotateCanvas(c, rotation, CoverGeometry.WIDTH, CoverGeometry.HEIGHT);
        drawCameras(c, false); c.restore();
    }
    private void lens(Canvas c, float x, float y, float radius) {
        fill(0xff171c22); c.drawCircle(x, y, radius + 6, paint);
        paint.setShader(new RadialGradient(x - radius * .18f, y - radius * .24f, radius,
                new int[]{0xff243149, 0xff0b1420, 0xff020406}, new float[]{0,.48f,1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
        fill(0xff020305); c.drawCircle(x, y, radius * .43f, paint);
        fill(0x584c6d91); c.drawOval(x - radius * .42f, y - radius * .52f, x + radius * .17f, y - radius * .17f, paint);
    }

    /** The caller keeps ownership of the native capture; this returns a distinct export bitmap. */
    public Bitmap compose(Bitmap capture, boolean rotated) {
        return compose(capture, rotated ? 2 : 0);
    }
    public Bitmap compose(Bitmap capture, int rotation) {
        Bitmap software = capture.getConfig() == Bitmap.Config.HARDWARE ? capture.copy(Bitmap.Config.ARGB_8888, false) : capture;
        boolean horizontal = CoverRotation.landscape(rotation);
        Bitmap result = Bitmap.createBitmap(horizontal ? HEIGHT : WIDTH, horizontal ? WIDTH : HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(result);
            drawShell(c, rotation);
            c.save(); c.clipPath(screenPath(rotation)); c.translate(FRAME, FRAME);
            c.drawBitmap(software, 0, 0, null);
            drawCameras(c, rotation); c.restore();
            result.setDensity(capture.getDensity());
            return result;
        } catch (RuntimeException error) { result.recycle(); throw error; }
        finally { if (software != capture) software.recycle(); }
    }
}
