package com.flipduo.module;

import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Per-attachment record of module-owned window fields, not of in-memory edits. */
final class NativeWindowCommit {
    private int[] submitted;

    void invalidate() { submitted = null; }

    boolean submit(WindowManager.LayoutParams params, boolean attached, Runnable update) throws ReflectiveOperationException {
        if (!attached) return false;
        int[] target = snapshot(params);
        if (Arrays.equals(submitted, target)) return false;
        update.run(); // A failed update must never become the comparison baseline.
        submitted = target;
        return true;
    }

    private static int[] snapshot(WindowManager.LayoutParams params) throws ReflectiveOperationException {
        Field rotations = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation");
        rotations.setAccessible(true);
        WindowManager.LayoutParams[] values = (WindowManager.LayoutParams[]) rotations.get(params);
        Field privateFlags = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
        privateFlags.setAccessible(true);
        int[] result = new int[10 * (1 + (values == null ? 0 : values.length))];
        capture(result, 0, params, privateFlags);
        if (values != null) for (int i = 0; i < values.length; i++) capture(result, 10 * (i + 1), values[i], privateFlags);
        return result;
    }

    private static void capture(int[] out, int at, WindowManager.LayoutParams params, Field privateFlags) throws IllegalAccessException {
        if (params == null) return;
        out[at] = 1;
        out[at + 1] = params.width; out[at + 2] = params.height;
        out[at + 3] = params.x; out[at + 4] = params.y; out[at + 5] = params.gravity;
        out[at + 6] = params.flags; out[at + 7] = privateFlags.getInt(params);
        out[at + 8] = params.layoutInDisplayCutoutMode; out[at + 9] = params.getFitInsetsTypes();
    }
}
