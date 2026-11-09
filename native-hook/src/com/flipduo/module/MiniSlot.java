package com.flipduo.module;

/** The native widget slot reused by HyperOS's mini window. Pure geometry, shared by FlipHome and SystemUI. */
final class MiniSlot {
    static final int TOLERANCE = 4;

    /** Pager bounds in the 398x664 control canvas, exactly as StockWidgetPanel.open() lays them out. */
    static int[] dockRect(boolean bottom, int pagerWidth, int nativeHeight, int paddingTop, int paddingBottom) {
        int margin = (DockSpec.WIDTH - pagerWidth) / 2;
        int left = bottom ? margin : DockSpec.WIDTH - margin - pagerWidth;
        return new int[]{left, paddingTop, left + pagerWidth, nativeHeight - paddingBottom};
    }

    /** Keep a phone-width logical canvas. Freeform buffers may be taller than the physical display. */
    static int[] freeformSize(int logicalWidth, int slotWidth, int slotHeight) {
        return new int[]{logicalWidth, Math.round(logicalWidth * (float) slotHeight / slotWidth)};
    }

    /** Surface crops are local to the task, never the screen-space widget rectangle or old task size. */
    static int[] localCrop(int[] size) { return new int[]{0, 0, size[0], size[1]}; }

    static float scale(int freeformWidth, int slotWidth) { return slotWidth / (float) freeformWidth; }

    /** Whether a scaled freeform image still occupies the slot (native avoid/drag moves it away). */
    static boolean fills(int[] slot, int left, int top, float width, float height) {
        return Math.abs(left - slot[0]) <= TOLERANCE && Math.abs(top - slot[1]) <= TOLERANCE
                && Math.abs(left + width - slot[2]) <= TOLERANCE && Math.abs(top + height - slot[3]) <= TOLERANCE;
    }
}
