// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.KeyguardManager;
import android.graphics.Insets;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;

/** Copies only the insets handed to a specific UI subtree. Never changes WMS sources or gesture regions. */
final class CoverPanelInsets {
    static boolean coverUnlocked(View view) {
        if (!NativeModule.config().enabled) return false;
        Display display = view.getDisplay();
        if (display == null || display.getState() != Display.STATE_ON) return false;
        DisplayMetrics metrics = new DisplayMetrics(); display.getRealMetrics(metrics);
        return CutoutAppConfig.isCoverDisplay(metrics.widthPixels, metrics.heightPixels)
                && !view.getContext().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    static WindowInsets notificationInsets(WindowInsets original) {
        int camera = WindowInsets.Type.displayCutout();
        return new WindowInsets.Builder(original).setDisplayCutout(null).setInsets(camera, Insets.NONE)
                .setInsetsIgnoringVisibility(camera, Insets.NONE).build();
    }

    private CoverPanelInsets() { }
}
