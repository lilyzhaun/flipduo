package com.flipduo.module;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Fixed black native side region, with subtle dark glass controls. Only the application's seam corners are masked. */
final class DockAppearance {
    boolean dark;
    int ink, secondary, press, shadow;
    float immersion, darkness;
    private int toneVersion;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF[] groups;
    private Drawable[] surfaces;

    DockAppearance() {
        this(false, true);
    }
    DockAppearance(boolean immersive, boolean dark) {
        tone(immersive ? 1 : 0, dark ? 1 : 0, dark ? 0xfff3f4f6 : 0xff20252c,
                dark ? 0xffd7dde5 : 0xff454e58);
        layout(new DockLayout());
    }

    void tone(float immersion, float darkness, int ink, int secondary) {
        if (this.immersion != immersion || this.darkness != darkness) toneVersion++;
        this.immersion = immersion; this.darkness = darkness; dark = darkness > .5f;
        this.ink = ink; this.secondary = secondary;
        press = blend(0x18000000, 0x24ffffff, darkness);
        shadow = blend(0x50000000, 0x24000000, immersion);
    }
    static int blend(int from, int to, float p) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            result |= Math.round(a + (b - a) * p) << shift;
        }
        return result;
    }

    void layout(DockLayout layout) {
        java.util.ArrayList<RectF> result = new java.util.ArrayList<>();
        for (int anchor : DockLayout.UNITS) result.add(new RectF(layout.x[anchor], layout.y[anchor],
                layout.x[anchor] + 156, layout.y[anchor] + 156 * DockLayout.members(anchor)));
        groups = result.toArray(new RectF[0]); surfaces = new Drawable[groups.length];
        for (int i = 0; i < groups.length; i++) surfaces[i] = surface();
    }

    void drawGroups(Canvas canvas, float scale) {
        for (int i = 0; i < groups.length; i++) {
            RectF group = groups[i]; canvas.save(); canvas.scale(scale, scale, group.centerX(), group.centerY());
            canvas.translate(group.left, group.top);
            surfaces[i].setBounds(0, 0, Math.round(group.width()), Math.round(group.height())); surfaces[i].draw(canvas);
            canvas.restore();
        }
        paint.setShader(null);
    }

    Drawable surface() { return surface(false); }
    Drawable surface(boolean blurred) { return new GlassSurface(this, blurred); }

    private static final class GlassSurface extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DockAppearance tone;
        private final boolean blurred;
        private int version = -1, alpha = 255;
        private Shader fill, rim, insetShade, innerLight;
        GlassSurface(DockAppearance tone, boolean blurred) {
            this.tone = tone; this.blurred = blurred;
        }
        private int[] palette(int[] normal, int[] light, int[] dark) {
            int[] values = new int[normal.length];
            for (int i = 0; i < values.length; i++)
                values[i] = blend(normal[i], blend(light[i], dark[i], tone.darkness), tone.immersion);
            return values;
        }
        @Override protected void onBoundsChange(Rect b) {
            version = tone.toneVersion;
            fill = new LinearGradient(0, 0, b.width() * .75f, b.height(), palette(
                    new int[]{0xff303237,0xff202126,0xff18191d},
                    blurred ? new int[]{0xa8ffffff,0x85fafafa,0x90f5f5f5} : new int[]{0xf8ffffff,0xf4f8f8f8,0xf6f3f3f3},
                    blurred ? new int[]{0x5074777c,0x4064686e,0x48575d65} : new int[]{0xeb424953,0xe62b323c,0xee1b222b}),
                    new float[]{0,.5f,1}, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, b.width(), b.height(),
                    new int[]{blend(blend(0x66ffffff,0x26000000,tone.immersion),0x70ffffff,tone.darkness),
                        blend(blend(0x30ffffff,0x14000000,tone.immersion),0x20ffffff,tone.darkness),
                        blend(blend(0x08000000,0x10000000,tone.immersion),0x0cffffff,tone.darkness),
                        blend(blend(0x14000000,0x28000000,tone.immersion),0x30000000,tone.darkness)},
                    new float[]{0,.22f,.68f,1}, Shader.TileMode.CLAMP);
            if (insetShade == null) {
                insetShade = new LinearGradient(0, 0, b.width() * .75f, b.height(),
                        new int[]{0x10000000,0x00000000,0x08000000}, new float[]{0,.45f,1}, Shader.TileMode.CLAMP);
                innerLight = new LinearGradient(0, 0, b.width() * .7f, b.height(),
                        new int[]{0xf0ffffff,0x80ffffff,0x35ffffff}, new float[]{0,.35f,1}, Shader.TileMode.CLAMP);
            }
        }
        @Override public void draw(Canvas c) {
            if (version != tone.toneVersion) onBoundsChange(getBounds());
            Rect b = getBounds(); float r = Math.min(b.width(), b.height()) / 2f;
            p.setAlpha(alpha);
            p.setStyle(Paint.Style.FILL); p.setShader(fill); c.drawRoundRect(0,0,b.width(),b.height(),r,r,p);
            float light = tone.immersion * (1 - tone.darkness);
            if (light > 0) {
                // All depth remains inside the existing outline: no elevation/z-order or extra blur layer.
                p.setAlpha(Math.round(alpha * light));
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setShader(insetShade);
                c.drawRoundRect(4,4,b.width()-4,b.height()-4,r-4,r-4,p);
                p.setStrokeWidth(2.2f); p.setShader(innerLight);
                c.drawRoundRect(2,2,b.width()-2,b.height()-2,r-2,r-2,p);
            }
            p.setAlpha(alpha);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setShader(rim);
            c.drawRoundRect(.8f,.8f,b.width()-.8f,b.height()-.8f,r-.8f,r-.8f,p);
            p.setShader(null);
        }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    Drawable column(boolean bottomControls, CoverGeometry geometry, boolean rounded, boolean symmetric) {
        return new Column(bottomControls, geometry, rounded, symmetric);
    }

    static final class Column extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int alpha = 255;
        private final boolean bottom;
        private final int topRadius, bottomRadius, nominalExtension;
        private int extension;
        private final int physicalTop, physicalBottom;
        private final Path corners = new Path();
        private final Path outline = new Path();
        private final Matrix mirror = new Matrix();
        Column(boolean bottom, CoverGeometry geometry, boolean rounded, boolean symmetric) {
            this.bottom = bottom;
            topRadius = geometry.appTop(bottom, rounded, symmetric); bottomRadius = geometry.appBottom(bottom, rounded, symmetric);
            physicalTop = geometry.top(bottom); physicalBottom = geometry.bottom(bottom);
            nominalExtension = geometry.appExtension(rounded, symmetric); extension = nominalExtension;
        }
        @Override protected void onBoundsChange(Rect b) {
            // A large read-only ring may need transparent app-side room even with seam corners disabled.
            extension = Math.max(nominalExtension, b.width() - DockSpec.WIDTH - DockSpec.SEAM_BLEED);
            corners.reset();
            if (extension > 0) {
                // Corner-only masks over the app boundary. Middle content and all input remain untouched.
                corners.moveTo(extension - topRadius, 0); corners.lineTo(extension, 0); corners.lineTo(extension, topRadius);
                corners.arcTo(new RectF(extension - 2 * topRadius, 0, extension, 2 * topRadius), 0, -90); corners.close();
                corners.moveTo(extension - bottomRadius, b.height()); corners.lineTo(extension, b.height()); corners.lineTo(extension, b.height() - bottomRadius);
                corners.arcTo(new RectF(extension - 2 * bottomRadius, b.height() - 2 * bottomRadius, extension, b.height()), 0, 90); corners.close();
            }
            outline.set(corners);
            Path body = new Path(); body.addRect(extension, 0, b.width(), b.height(), Path.Direction.CW);
            outline.op(body, Path.Op.UNION);
            if (bottom) { mirror.setScale(-1, 1); mirror.postTranslate(b.width(), 0); outline.transform(mirror); }
            // This is the exact ink boundary, not an intersection with the physical panel mask.
            // Hardware clips the settled panel once; pre-clipping here would attenuate edge pixels twice.
        }
        Path outline() { return outline; }
        /** Concave app seam masks are correct while touching the screen edge, but become pointed
         * wedges when peeled away. Blend them into the convex sheet caps before the main movement.
         */
        void materialPoint(float x, float y, float release, float[] out) {
            float width = getBounds().width(), height = getBounds().height();
            if (bottom) { x = width - x; y = height - y; }
            float appTop = bottom ? bottomRadius : topRadius, appBottom = bottom ? topRadius : bottomRadius;
            float panelTop = bottom ? physicalBottom : physicalTop, panelBottom = bottom ? physicalTop : physicalBottom;
            float originalLeft = extension, materialLeft = extension, materialRight = width;
            if (appTop > 0 && y < appTop) originalLeft -= appTop - arc(appTop, y);
            else if (appBottom > 0 && height - y < appBottom) originalLeft -= appBottom - arc(appBottom, height - y);
            if (panelTop > 0 && y < panelTop) {
                float inset = panelTop - arc(panelTop, y); materialLeft += inset; materialRight -= inset;
            } else if (panelBottom > 0 && height - y < panelBottom) {
                float inset = panelBottom - arc(panelBottom, height - y); materialLeft += inset; materialRight -= inset;
            }
            float left = originalLeft + (materialLeft - originalLeft) * release;
            float right = width + (materialRight - width) * release;
            x = left + (x - originalLeft) / (width - originalLeft) * (right - left);
            out[0] = bottom ? width - x : x; out[1] = bottom ? height - y : y;
        }
        private static float arc(float radius, float distance) {
            return (float) Math.sqrt(Math.max(0, radius * radius - (distance - radius) * (distance - radius)));
        }
        @Override public void draw(Canvas c) {
            c.save();
            if (bottom) { c.translate(getBounds().width(), 0); c.scale(-1, 1); }
            paint.setShader(null); paint.setColor(Color.BLACK); paint.setAlpha(alpha);
            c.drawPath(corners, paint);
            c.drawRect(extension, 0, getBounds().width(), getBounds().height(), paint);
            c.restore();
        }
        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
