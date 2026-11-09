package com.flipduo.module;

/** Display-only fit for a stale cover snapshot. Output pixel count never exceeds its input buffer. */
final class InnerSnapshotLayout {
    final int width, height;
    final float left, top, contentWidth, contentHeight;

    private InnerSnapshotLayout(int width, int height, float left, float top, float contentWidth, float contentHeight) {
        this.width = width; this.height = height; this.left = left; this.top = top;
        this.contentWidth = contentWidth; this.contentHeight = contentHeight;
    }
    static boolean eligible(int snapshotState, int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        return snapshotState == 0 && CutoutAppConfig.isCoverDisplay(sourceWidth, sourceHeight)
                && ((targetWidth == 1224 && targetHeight == 2912) || (targetWidth == 2912 && targetHeight == 1224));
    }
    static InnerSnapshotLayout fit(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        double budget = Math.min(1, Math.sqrt((double) sourceWidth * sourceHeight / ((double) targetWidth * targetHeight)));
        int width = Math.max(1, (int) (targetWidth * budget)), height = Math.max(1, (int) (targetHeight * budget));
        float scale = Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
        float contentWidth = sourceWidth * scale, contentHeight = sourceHeight * scale;
        return new InnerSnapshotLayout(width, height, (width - contentWidth) / 2, (height - contentHeight) / 2,
                contentWidth, contentHeight);
    }
}
