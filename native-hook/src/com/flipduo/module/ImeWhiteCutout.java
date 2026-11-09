// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.util.function.IntBinaryOperator;
import java.util.ArrayList;

/** Traces only the white hole in Sogou's stock light candidate bitmap. Not a camera geometry model. */
final class ImeWhiteCutout {
    static int[] rows(int width, int height, int cameraWidth, IntBinaryOperator pixel) {
        if (width <= 0 || height <= 0 || cameraWidth <= 0 || cameraWidth >= width
                || pixel.applyAsInt(cameraWidth / 2, 0) != 0xffffffff
                || pixel.applyAsInt(width - 1, height - 1) != 0xffe5e6e7) return null;
        ArrayList<Integer> spans = new ArrayList<>(); // y/left/right vector runs, not a pixel buffer.
        boolean ended = false;
        for (int y = 0; y < height; y++) {
            int left = 0;
            while (left < cameraWidth && pixel.applyAsInt(left, y) != 0xffffffff) left++;
            if (left == cameraWidth) { ended = true; continue; }
            if (ended) return null; // A different skin/image, not the top-connected stock cutout.
            while (left < cameraWidth) {
                int right = left;
                while (right < cameraWidth && pixel.applyAsInt(right, y) == 0xffffffff) right++;
                spans.add(y); spans.add(left); spans.add(right);
                left = right;
                while (left < cameraWidth && pixel.applyAsInt(left, y) != 0xffffffff) left++;
            }
        }
        return spans.stream().mapToInt(Integer::intValue).toArray();
    }
    private ImeWhiteCutout() { }
}
