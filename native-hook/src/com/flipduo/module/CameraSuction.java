package com.flipduo.module;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PathIterator;
import android.graphics.Rect;
import java.util.ArrayList;

/** Cached outline deformation. No bitmaps, blur, layers, surfaces, timers or per-frame allocations. */
final class CameraSuction {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathMeasure measure = new PathMeasure();
    private final Rect bounds = new Rect(), camera = new Rect();
    private final float[] position = new float[2];
    private float[] points = new float[0];
    private float[] warped = new float[0];
    private float[] softened = new float[0];
    private float[] sourceControls = new float[0];
    private int[] contours = new int[0];
    private Path source;
    private DockAppearance.Column material;
    private boolean opticalProfile = true;
    private int count, contourCount;
    private final GenieGeometry genie = new GenieGeometry();

    void configure(DockAppearance.Column material, Rect bounds, Rect camera, int windowX, int windowY) {
        configure(material, bounds, camera, windowX, windowY, true);
    }
    void configure(DockAppearance.Column material, Rect bounds, Rect camera, int windowX, int windowY, boolean opticalProfile) {
        this.material = material;
        Path source = material.outline();
        if (this.source == source && this.bounds.equals(bounds) && this.camera.equals(camera) && this.opticalProfile == opticalProfile) return;
        this.source = source; this.bounds.set(bounds); this.camera.set(camera);
        this.opticalProfile = opticalProfile;
        genie.configure(bounds.width(), bounds.height(), camera.left - windowX, camera.top - windowY,
                camera.right - windowX, camera.bottom - windowY, camera.top == 0, opticalProfile);
        // Preserve every original verb joint. Uniform sampling of the WHOLE contour used to skip
        // app-corner tips, and its Catmull-Rom fit then thinned even the settled black mask.
        ArrayList<float[]> pieces = new ArrayList<>(); ArrayList<Integer> ends = new ArrayList<>();
        Path edge = new Path(); float firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        PathIterator iterator = source.getPathIterator();
        while (iterator.hasNext()) {
            PathIterator.Segment segment = iterator.next(); float[] p = segment.getPoints(); int verb = segment.getVerb();
            if (verb == PathIterator.VERB_MOVE) { firstX = lastX = p[0]; firstY = lastY = p[1]; continue; }
            if (verb == PathIterator.VERB_CLOSE) {
                if (lastX != firstX || lastY != firstY) {
                    edge.rewind(); edge.moveTo(lastX, lastY); edge.lineTo(firstX, firstY); cacheEdge(edge, false, false, pieces);
                }
                ends.add(pieces.size()); continue;
            }
            edge.rewind(); edge.moveTo(p[0], p[1]);
            int end;
            switch (verb) {
                case PathIterator.VERB_LINE -> { edge.lineTo(p[2], p[3]); end = 2; }
                case PathIterator.VERB_QUAD -> { edge.quadTo(p[2], p[3], p[4], p[5]); end = 4; }
                case PathIterator.VERB_CONIC -> { edge.conicTo(p[2], p[3], p[4], p[5], segment.getConicWeight()); end = 4; }
                case PathIterator.VERB_CUBIC -> { edge.cubicTo(p[2], p[3], p[4], p[5], p[6], p[7]); end = 6; }
                default -> { continue; }
            }
            boolean vertical = verb == PathIterator.VERB_LINE && Math.abs(p[end + 1] - p[1]) > Math.abs(p[end] - p[0]);
            cacheEdge(edge, vertical && (p[1] == 0 || p[1] == bounds.height()),
                    vertical && (p[end + 1] == 0 || p[end + 1] == bounds.height()), pieces);
            lastX = p[end]; lastY = p[end + 1];
        }
        int required = pieces.size(), requiredContours = ends.size();
        if (points.length < required * 2) points = new float[required * 2];
        if (warped.length < required * 2) warped = new float[required * 2];
        if (softened.length < required * 2) softened = new float[required * 2];
        if (sourceControls.length < required * 4) sourceControls = new float[required * 4];
        if (contours.length < requiredContours) contours = new int[requiredContours];
        count = required; contourCount = requiredContours;
        for (int i = 0; i < count; i++) {
            float[] piece = pieces.get(i); points[i * 2] = piece[0]; points[i * 2 + 1] = piece[1];
            System.arraycopy(piece, 2, sourceControls, i * 4, 4);
        }
        for (int i = 0; i < contourCount; i++) contours[i] = ends.get(i);
    }

    /** Cache small Hermite cubics from the actual line/conic/cubic and its exact tangent, not from
     * neighboring samples. The subpixel circular-arc approximation converges to the original mask.
     */
    private void cacheEdge(Path edge, boolean denseStart, boolean denseEnd, ArrayList<float[]> pieces) {
        measure.setPath(edge, false); float length = measure.getLength();
        if (length == 0) return;
        int steps = Math.max(1, (int) Math.ceil(length / 8));
        float[] a = new float[2], b = new float[2], ta = new float[2], tb = new float[2];
        for (int i = 0; i < steps; i++) {
            // A straight source edge becomes a physical rounded cap when detached. Refine the
            // first/last 8px so the newly curved endpoint still has a smooth sampled contour.
            int subdivisions = (i == 0 && denseStart || i == steps - 1 && denseEnd) ? 32 : 1;
            for (int j = 0; j < subdivisions; j++) {
                float begin = length * (i + j / (float) subdivisions) / steps;
                float end = length * (i + (j + 1) / (float) subdivisions) / steps;
                measure.getPosTan(begin, a, ta); measure.getPosTan(end, b, tb); float handle = (end - begin) / 3;
                pieces.add(new float[]{a[0], a[1], a[0] + ta[0] * handle, a[1] + ta[1] * handle,
                        b[0] - tb[0] * handle, b[1] - tb[1] * handle});
            }
        }
    }

    void path(float progress, Path out) {
        out.rewind();
        if (progress >= 1) return;
        genie.frame(progress);
        float release = GenieGeometry.cornerRelease(progress);
        float cameraRelease = GenieGeometry.cameraCornerRelease(progress);
        for (int i = 0; i < count; i++) {
            // Restore convex material rounding instead of faithfully preserving a sharp seam wedge.
            // The middle of the source has no corner offset, so this split introduces no seam.
            float canonicalY = camera.top == 0 ? bounds.height() - points[i * 2 + 1] : points[i * 2 + 1];
            material.materialPoint(points[i * 2], points[i * 2 + 1],
                    canonicalY > bounds.height() / 2f ? cameraRelease : release, position);
            genie.outlinePoint(position[0], position[1], position);
            warped[i * 2] = position[0]; warped[i * 2 + 1] = position[1];
        }
        int start = 0;
        // A convex-to-concave corner morph crosses a nearly square midpoint. C1 alone is not enough:
        // spread that local turn over an arc, without blurring pixels or changing either endpoint.
        float roundness = 4 * cameraRelease * (1 - cameraRelease);
        float exactness = 1 - GenieGeometry.cornerRelease(progress);
        for (int contour = 0; contour < contourCount; contour++) {
            int end = contours[contour];
            if (roundness > 0) for (int i = start; i < end; i++) {
                float canonicalY = camera.top == 0 ? bounds.height() - points[i * 2 + 1] : points[i * 2 + 1];
                float local = Math.max(0, Math.min(1, (canonicalY / bounds.height() - .5f) * 4));
                float amount = roundness * local * local * (3 - 2 * local);
                float sx = warped[i * 2], sy = warped[i * 2 + 1];
                if (amount > 0) {
                    float x = 0, y = 0;
                    for (int offset = -18; offset <= 18; offset++) {
                        int index = start + Math.floorMod(i - start + offset, end - start);
                        int weight = 19 - Math.abs(offset);
                        x += warped[index * 2] * weight; y += warped[index * 2 + 1] * weight;
                    }
                    sx += (x / 361 - sx) * amount; sy += (y / 361 - sy) * amount;
                }
                softened[i * 2] = sx; softened[i * 2 + 1] = sy;
            }
            float[] curve = roundness > 0 ? softened : warped;
            out.moveTo(curve[start * 2], curve[start * 2 + 1]);
            for (int i = start; i < end; i++) {
                int previous = i == start ? end - 1 : i - 1;
                int next = i + 1 == end ? start : i + 1;
                int after = next + 1 == end ? start : next + 1;
                // Closed Catmull-Rom -> cubic Bezier. Adjacent segments share the same tangent (C1),
                // rather than exposing hundreds of straight polygon edges during the deformation.
                // Remove the SOURCE fitting error smoothly near rest. This corrects geometry, not
                // opacity/coverage, so entry ends on the exact original mask without a final fill.
                out.cubicTo(curve[i * 2] + (curve[next * 2] - curve[previous * 2]) / 6
                                + (sourceControls[i * 4] - points[i * 2] - (points[next * 2] - points[previous * 2]) / 6) * exactness,
                        curve[i * 2 + 1] + (curve[next * 2 + 1] - curve[previous * 2 + 1]) / 6
                                + (sourceControls[i * 4 + 1] - points[i * 2 + 1] - (points[next * 2 + 1] - points[previous * 2 + 1]) / 6) * exactness,
                        curve[next * 2] - (curve[after * 2] - curve[i * 2]) / 6
                                + (sourceControls[i * 4 + 2] - points[next * 2] + (points[after * 2] - points[i * 2]) / 6) * exactness,
                        curve[next * 2 + 1] - (curve[after * 2 + 1] - curve[i * 2 + 1]) / 6
                                + (sourceControls[i * 4 + 3] - points[next * 2 + 1] + (points[after * 2 + 1] - points[i * 2 + 1]) / 6) * exactness,
                        curve[next * 2], curve[next * 2 + 1]);
            }
            out.close(); start = end;
        }
    }

    void draw(Canvas canvas, Path silhouette, int alpha) {
        ink.setColor(Color.BLACK); ink.setAlpha(alpha); canvas.drawPath(silhouette, ink);
    }
}
