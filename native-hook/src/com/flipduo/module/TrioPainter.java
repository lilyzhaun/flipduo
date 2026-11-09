package com.flipduo.module;

import android.graphics.*;

/** 120-unit optical grid; dynamically fits the header rather than painting over a fixed arc. */
final class TrioPainter {
    private static final float[] DOT_X = {34, 51, 69, 86};
    private final Paint p = new Paint(3), text = new Paint(3);
    private final Rect bounds = new Rect();
    private final RectF ring = new RectF(10, 8, 110, 108);
    private final Path[] wifi = new Path[3];
    private DockConfig style = new DockConfig();
    private Typeface batteryTypeface = Typeface.create(Typeface.SANS_SERIF, 650, false);
    private Typeface networkTypeface = batteryTypeface;
    private int wifiStroke = 100, batteryWeight = 100, networkWeight = 100;

    TrioPainter() {
        text.setTypeface(batteryTypeface);
        text.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < 3; i++) wifi[i] = new Path();
        buildWifi(100);
    }

    void configure(DockConfig config) {
        style = config;
        if (wifiStroke != config.trioWifiStroke) { wifiStroke = config.trioWifiStroke; buildWifi(wifiStroke); }
        if (batteryWeight != config.trioBatteryWeight) {
            batteryWeight = config.trioBatteryWeight;
            batteryTypeface = Typeface.create(Typeface.SANS_SERIF, Math.round(650 * batteryWeight / 100f), false);
        }
        if (networkWeight != config.trioNetworkWeight) {
            networkWeight = config.trioNetworkWeight;
            networkTypeface = Typeface.create(Typeface.SANS_SERIF, Math.round(650 * networkWeight / 100f), false);
        }
    }

    private void buildWifi(int weight) {
        // Three filled, rounded fan segments; never four concentric strokes plus an extra dot.
        for (int i = 0; i < 3; i++) {
            wifi[i].reset();
            float outer = i == 0 ? 11 : i == 1 ? 21 : 33;
            float inner = i == 0 ? 0 : i == 1 ? 14 : 26;
            if (i != 0) {
                float mid = (outer + inner) / 2, half = (outer - inner) * weight / 200f;
                outer = mid + half; inner = mid - half;
            }
            RectF a = new RectF(60 - outer, 72 - outer, 60 + outer, 72 + outer);
            wifi[i].arcTo(a, 228, 84);
            if (inner == 0) { wifi[i].lineTo(60, 72); }
            else wifi[i].arcTo(new RectF(60 - inner, 72 - inner, 60 + inner, 72 + inner), 312, -84);
            wifi[i].close();
        }
    }

    void draw(Canvas c, TrioState state, int ink) {
        int batteryColor = state.batteryTint(ink);
        String number = !style.trioBatteryNumber ? "" : state.battery < 0 ? "–" : Integer.toString(Math.min(100, state.battery));
        text.setTypeface(batteryTypeface);
        text.setTextSize(33.8f * style.trioBatteryScale / 100f);
        float boltWidth = state.charging ? 19 : 0;
        float maxWidth = 82 - boltWidth;
        float fit = Math.min(1, maxWidth / Math.max(1, text.measureText(number)));
        text.setTextSize(text.getTextSize() * fit);
        float width = text.measureText(number) + boltWidth;
        float halfGap = TrioState.gap(width);
        float segment = 120 - halfGap;
        float stroke = 7.5f * style.trioStroke / 100f;
        stroke(c, ink, 48, stroke); c.drawArc(ring, 150, segment, false, p); c.drawArc(ring, 270 + halfGap, segment, false, p);
        if (state.battery > 0) {
            float sweep = segment * 2 * Math.min(100, state.battery) / 100f;
            stroke(c, batteryColor, 255, stroke); c.drawArc(ring, 150, Math.min(segment, sweep), false, p);
            if (sweep > segment) c.drawArc(ring, 270 + halfGap, sweep - segment, false, p);
        }
        float x = 60 - width / 2;
        if (state.charging) {
            Path bolt = new Path(); bolt.moveTo(x + 9, 0); bolt.lineTo(x, 13); bolt.lineTo(x + 6, 13);
            bolt.lineTo(x + 3, 23); bolt.lineTo(x + 15, 8); bolt.lineTo(x + 8, 8); bolt.close();
            fill(-14895277, 255); c.drawPath(bolt, p); x += 19;
        }
        if (style.trioBatteryNumber) {
            text.setColor(batteryColor);
            text.getTextBounds(number, 0, number.length(), bounds);
            float centreY = Math.max(10, bounds.height() / 2f + 2);
            c.drawText(number, x, centreY - (bounds.top + bounds.bottom) / 2f, text);
        }
        if (state.showWifi()) {
            c.save(); c.scale(style.trioWifiScale / 100f, style.trioWifiScale / 100f, 60, 57);
            for (int i = 0; i < 3; i++) {
                fill(ink, state.wifiSegments() > i ? 255 : 102);
                c.drawPath(wifi[i], p);
            }
            c.restore();
        } else {
            String label = state.centre();
            text.setTypeface(networkTypeface);
            text.setTextSize(27 * style.trioNetworkScale / 100f); text.setColor(ink); text.setAlpha(label.equals("–") ? 90 : 255);
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, 60 - text.measureText(label) / 2, 57 - (fm.ascent + fm.descent) / 2, text);
            text.setAlpha(255);
        }
        for (int i = 0; i < 4; i++) {
            fill(ink, state.litCell(i) ? 255 : 48);
            c.drawCircle(DOT_X[i], i == 0 || i == 3 ? 102 : 109, 4.9f * style.trioStroke / 100f, p);
        }
        if (state.showCross()) {
            stroke(c, ink, 210, 4); c.drawLine(56, 90, 64, 98, p); c.drawLine(64, 90, 56, 98, p);
        }
    }

    private void fill(int color, int alpha) { p.setColor(color); p.setAlpha(alpha); p.setStyle(Paint.Style.FILL); }
    private void stroke(Canvas c, int color, int alpha, float width) {
        p.setColor(color); p.setAlpha(alpha); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
    }
}
