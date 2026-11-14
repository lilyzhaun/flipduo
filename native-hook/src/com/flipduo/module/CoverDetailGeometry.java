// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.graphics.Rect;

/** A rectangular reading column outside the display's actual cutout and system bars. */
final class CoverDetailGeometry {
    static Rect area(int width, int height, int left, int top, int right, int bottom, float density) {
        int gap = Math.round(12 * density);
        Rect result = new Rect(left + gap, top + gap, width - right - gap, height - bottom - gap);
        return result.width() > 0 && result.height() > 0 ? result : null;
    }
    private CoverDetailGeometry() { }
}
