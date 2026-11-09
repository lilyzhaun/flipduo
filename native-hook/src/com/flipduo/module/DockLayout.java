package com.flipduo.module;

import java.util.LinkedHashSet;

/** Four draggable units: two single buttons and two inseparable vertical pairs. */
public final class DockLayout {
    public static final int COUNT = 6, CELL = 156, WIDTH = 398, MIN_Y = 156, MAX_Y = 494, MAX_X = WIDTH - CELL;
    public final int[] x = new int[COUNT], y = new int[COUNT];
    public static final int[] UNITS = {0, 1, 3, 5};
    public static int unit(int slot) { return slot == 2 ? 1 : slot == 4 ? 3 : slot; }
    public static int members(int unit) { return unit == 1 || unit == 3 ? 2 : 1; }
    public DockLayout() {
        for (int i = 0; i < COUNT; i++) { x[i] = DockSpec.left(i); y[i] = DockSpec.top(i); }
    }
    public DockLayout(DockLayout other) {
        System.arraycopy(other.x, 0, x, 0, COUNT); System.arraycopy(other.y, 0, y, 0, COUNT);
    }
    public static DockLayout decode(String positions) {
        DockLayout result = new DockLayout();
        if (positions == null || positions.isEmpty()) return result;
        try {
            String[] entries = positions.split(";", -1); if (entries.length != COUNT) return result;
            DockLayout parsed = new DockLayout();
            for (int i = 0; i < COUNT; i++) {
                String[] point = entries[i].split(":", -1); if (point.length != 2) return result;
                parsed.x[i] = Integer.parseInt(point[0]); parsed.y[i] = Integer.parseInt(point[1]);
            }
            if (!parsed.geometryValid()) return result;
            if (parsed.valid()) return parsed;
            // Migrate previously separated pairs as whole units, retaining the nearest legal saved anchors.
            for (int anchor : UNITS) result = result.place(anchor, parsed.x[anchor], parsed.y[anchor]);
            return result;
        } catch (NumberFormatException invalid) { return result; }
    }
    public String encode() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < COUNT; i++) { if (i != 0) value.append(';'); value.append(x[i]).append(':').append(y[i]); }
        return value.toString();
    }
    public boolean valid() {
        for (int anchor : new int[]{1, 3})
            if (x[anchor] != x[anchor + 1] || y[anchor + 1] != y[anchor] + CELL) return false;
        return geometryValid();
    }
    private boolean geometryValid() {
        for (int i = 0; i < COUNT; i++) {
            if (x[i] < 0 || x[i] > MAX_X || y[i] < MIN_Y || y[i] > MAX_Y) return false;
            for (int j = 0; j < i; j++) if (overlap(x[i], y[i], x[j], y[j])) return false;
        }
        return true;
    }
    private static boolean overlap(int x, int y, int ox, int oy) {
        return x < ox + CELL && x + CELL > ox && y < oy + CELL && y + CELL > oy;
    }
    public DockLayout dragged(int slot, int px, int py) {
        DockLayout result = new DockLayout(this);
        int anchor = unit(slot), count = members(anchor);
        int nx = clamp(px, 0, MAX_X), ny = clamp(py - (slot - anchor) * CELL, MIN_Y, MAX_Y - (count - 1) * CELL);
        for (int i = 0; i < count; i++) { result.x[anchor + i] = nx; result.y[anchor + i] = ny + i * CELL; }
        return result;
    }
    /** Equal-sized units swap; other overlaps settle as whole units at the nearest legal edge. */
    public DockLayout place(int slot, int px, int py) {
        int anchor = unit(slot), count = members(anchor);
        DockLayout moved = dragged(slot, px, py);
        if (moved.valid()) return moved;
        int hit = -1;
        for (int i = 0; i < COUNT; i++) if (unit(i) != anchor && members(unit(i)) == count && moved.x[slot] + CELL/2 >= x[i] && moved.x[slot] + CELL/2 < x[i] + CELL
                && moved.y[slot] + CELL/2 >= y[i] && moved.y[slot] + CELL/2 < y[i] + CELL) { hit = i; break; }
        if (hit >= 0) {
            int other = unit(hit);
            DockLayout swap = dragged(anchor, x[other], y[other]).dragged(other, x[anchor], y[anchor]);
            if (swap.valid()) return swap;
        }
        LinkedHashSet<Integer> xs = new LinkedHashSet<>(), ys = new LinkedHashSet<>();
        xs.add(moved.x[slot]); xs.add(0); xs.add(MAX_X);
        ys.add(moved.y[slot]); ys.add(MIN_Y); ys.add(MAX_Y);
        for (int i = 0; i < COUNT; i++) if (unit(i) != anchor) {
            xs.add(clamp(x[i] - CELL, 0, MAX_X)); xs.add(clamp(x[i] + CELL, 0, MAX_X));
            ys.add(clamp(y[i] - CELL, MIN_Y, MAX_Y)); ys.add(clamp(y[i] + CELL, MIN_Y, MAX_Y));
        }
        DockLayout best = new DockLayout(this); long distance = Long.MAX_VALUE;
        for (int nx : xs) for (int ny : ys) {
            DockLayout candidate = dragged(slot, nx, ny);
            long d = (long)(nx - px)*(nx - px) + (long)(ny - py)*(ny - py);
            if (d < distance && candidate.valid()) { best = candidate; distance = d; }
        }
        return best;
    }
    private static int clamp(int v, int low, int high) { return Math.max(low, Math.min(high, v)); }
}
