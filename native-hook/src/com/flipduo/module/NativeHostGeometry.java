package com.flipduo.module;

import android.view.ViewGroup;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** The two OEM sizing contracts verified in HyperOS 3 ruyi and HyperOS 4 bixi FlipHome. */
final class NativeHostGeometry {
    static Method resizeMethod(Class<?> group) {
        try { return group.getDeclaredMethod("updateWindowSize"); }
        catch (NoSuchMethodException absent) {
            // HyperOS 3 ALPHA-1.1.4.1103 creates geometry once in private init(); it has no resize entry.
            return null;
        }
    }

    static List<Method> lifecycleMethods(Class<?> group) throws NoSuchMethodException {
        List<Method> methods = new ArrayList<>();
        methods.add(group.getDeclaredMethod("onFinishInflate"));
        methods.add(group.getDeclaredMethod("onAttachedToWindow"));
        Method resize = resizeMethod(group);
        if (resize != null) methods.add(resize);
        return methods;
    }

    static WindowManager.LayoutParams snapshot(WindowManager.LayoutParams source) throws ReflectiveOperationException {
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(source);
        Field field = WindowManager.LayoutParams.class.getDeclaredField("paramsForRotation");
        field.setAccessible(true);
        WindowManager.LayoutParams[] rotations = (WindowManager.LayoutParams[]) field.get(source);
        if (rotations != null) {
            WindowManager.LayoutParams[] values = new WindowManager.LayoutParams[rotations.length];
            IdentityHashMap<WindowManager.LayoutParams, WindowManager.LayoutParams> copies = new IdentityHashMap<>();
            for (int i = 0; i < rotations.length; i++) {
                WindowManager.LayoutParams value = rotations[i];
                if (value == null) continue;
                WindowManager.LayoutParams saved = copies.get(value);
                if (saved == null) {
                    saved = new WindowManager.LayoutParams(); saved.copyFrom(value);
                    field.set(saved, null); // OEM entries have no nested rotation array.
                    copies.put(value, saved);
                }
                values[i] = saved;
            }
            field.set(copy, values);
        }
        return copy;
    }

    static void rebuild(ViewGroup root, WindowManager.LayoutParams params, WindowManager.LayoutParams original)
            throws ReflectiveOperationException {
        params.copyFrom(snapshot(original)); // Never let later edits mutate the saved per-rotation baseline.
        Method resize = resizeMethod(root.getClass());
        if (resize != null) {
            params.width = -1; // HyperOS 4 rebuilds current resource sizes and the rotation array.
            resize.invoke(root);
        }
        // HyperOS 3 has no dynamic rebuild: restore its exact init-time base and rotation entries.
        // Do not invoke private init(), which also resets unrelated native window state.
    }
}
