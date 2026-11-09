package com.flipduo.module;

/** One physical geometry for the native dock, preview, and the expanded SystemUI card. */
final class IslandGeometry {
    static final int CELL = 156, GAP = 38, OUTER = 218, INNER = 24;
    static final int TOP = 156, HEIGHT = 494;
    static final int LOCK_TOP = TOP + HEIGHT - CELL;
    static final int EXPANDED_EDGE_INSET = 0;
    static int navigationX(boolean bottom) { return bottom ? INNER : OUTER; }
    static int islandX(boolean bottom) { return bottom ? OUTER : INNER; }
}
