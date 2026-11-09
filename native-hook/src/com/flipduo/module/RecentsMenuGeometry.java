// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from parallelcc/MixFlipMod, FlipHomeHook.kt, commit 6b02e8cec6497efd2a07b9ba1307fb9c2f979867.
// https://github.com/parallelcc/MixFlipMod — original author: parallelcc.
package com.flipduo.module;

/** Direct Java port of calculateRecentsMenuPositions and the three upstream position helpers.
 * Coordinates and pivots deliberately retain the upstream truncation and clamping order. */
final class RecentsMenuGeometry {
    record Position(int x, int y, int pivotX, int pivotY) { }
    record Anchor(int taskLeft, int taskTop, int taskRight, int taskBottom, int taskCenterY,
                  int screenWidth, int screenHeight, int itemSize, int minMargin, boolean showAtRight) {
        Position nearRight(int y, float offset, int pivotY) {
            int x = (int) (taskRight + itemSize * offset);
            return new Position(x, y, taskRight - itemSize * 2 - x, pivotY);
        }
        Position nearLeft(int y, float offset, int pivotY) {
            int x = (int) (taskLeft + itemSize * offset);
            return new Position(x, y, taskLeft + itemSize * 2 - x, pivotY);
        }
        Position centered(int x, int y) {
            return new Position(x, y, showAtRight ? taskRight - itemSize - x : taskLeft + itemSize - x, taskCenterY - y);
        }
    }

    static Position[] calculate(Anchor a) {
        int centerBaseY = (int) (a.taskCenterY + a.itemSize * .14f);
        int centerFirstY = (int) (centerBaseY - a.itemSize * 1.2f);
        Position[] positions;
        if (centerFirstY < a.minMargin) {
            int y1 = (int) (a.taskBottom - a.itemSize * .3f);
            int y2 = (int) (y1 + a.itemSize * .8f);
            positions = a.showAtRight ? new Position[]{
                    a.nearRight(y1, .5f, a.taskBottom - y1 - a.itemSize * 2),
                    a.nearRight(y2, -.5f, a.taskBottom - y2 - a.itemSize * 2)} : new Position[]{
                    a.nearLeft(y1, -1.5f, a.taskBottom - y1 - a.itemSize * 2),
                    a.nearLeft(y2, -.5f, a.taskBottom - y2 - a.itemSize * 2)};
        } else if (centerBaseY + a.itemSize > a.screenHeight - a.minMargin) {
            int y2 = (int) (a.taskTop - a.itemSize * .7f);
            int y1 = (int) (y2 - a.itemSize * .8f);
            positions = a.showAtRight ? new Position[]{
                    a.nearRight(y1, -.5f, a.taskTop - y1 + a.itemSize * 2),
                    a.nearRight(y2, .5f, a.taskTop - y2 + a.itemSize * 2)} : new Position[]{
                    a.nearLeft(y1, -.5f, a.taskTop - y1 + a.itemSize * 2),
                    a.nearLeft(y2, -1.5f, a.taskTop - y2 + a.itemSize * 2)};
        } else {
            int x = a.showAtRight ? (int) (a.taskRight + a.itemSize * .8f) : (int) (a.taskLeft - a.itemSize * 1.8f);
            positions = new Position[]{a.centered(x, centerFirstY), a.centered(x, centerBaseY)};
        }
        int maxX = Math.max(a.screenWidth - a.itemSize - a.minMargin, a.minMargin);
        int maxY = Math.max(a.screenHeight - a.itemSize - a.minMargin, a.minMargin);
        for (int i = 0; i < positions.length; i++) {
            Position p = positions[i];
            positions[i] = new Position(Math.max(a.minMargin, Math.min(p.x, maxX)),
                    Math.max(a.minMargin, Math.min(p.y, maxY)), p.pivotX, p.pivotY);
        }
        return positions;
    }
    private RecentsMenuGeometry() { }
}
