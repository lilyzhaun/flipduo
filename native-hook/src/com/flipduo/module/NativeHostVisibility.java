package com.flipduo.module;

import android.view.View;

/** Separate the OEM rotation-only hide gate from genuine lifecycle/gesture visibility requests. */
final class NativeHostVisibility {
    private int requested = View.VISIBLE;
    private boolean orientationChange, reconciling;

    static boolean hide(boolean nativeHide, boolean adapting) { return nativeHide && !adapting; }

    void beginOrientation(int previous, int next) {
        orientationChange = previous != next;
        // The native method chooses VISIBLE/GONE solely from isHide() after a direction change.
        if (orientationChange) requested = View.VISIBLE;
    }
    void endOrientation() { orientationChange = false; }
    void request(int visibility) {
        if (!orientationChange && !reconciling) requested = visibility;
    }
    int target(int rotation, boolean adapting) {
        return requested == View.VISIBLE && CoverRotation.landscape(rotation) && !adapting ? View.GONE : requested;
    }
    void reconcile(View root, int rotation, boolean adapting) {
        int target = target(rotation, adapting);
        if (root.getVisibility() == target) return;
        reconciling = true;
        try { root.setVisibility(target); }
        finally { reconciling = false; }
    }
}
