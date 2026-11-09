// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** OEM light candidate_flip_bg.png has an opaque white hole; the dark asset has transparent pixels.
 * Clip that exact existing bitmap's white runs out of the entire keyboard subtree, including its
 * background drawer. No replacement bitmap/layer/window, no drawing-time IPC, no layout/input change.
 * Remove when the OEM fixes the light asset. Unknown skins/interfaces retain their original drawing.
 */
final class SogouImeCutout {
    static final String PACKAGE = "com.sohu.inputmethod.sogou.xiaomi";
    private static SogouImeCutout instance;
    private final Map<ViewGroup, Path> roots = new WeakHashMap<>();
    private final Class<?> candidate, stockDrawable;
    private final Field defaultTheme;
    private final Method bitmap;

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        instance = new SogouImeCutout(module, loader);
    }
    static void refresh() { if (instance != null) instance.refreshRoots(); }

    private SogouImeCutout(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> host = loader.loadClass("com.sogou.core.ui.window.StyleConstraintLayout");
        candidate = loader.loadClass("com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer");
        stockDrawable = loader.loadClass("com.sogou.theme.data.drawable.e");
        defaultTheme = candidate.getDeclaredField("mIsDefaultTheme"); defaultTheme.setAccessible(true);
        bitmap = stockDrawable.getDeclaredMethod("l"); bitmap.setAccessible(true); // Verified cached original bitmap getter.
        module.hook(host.getDeclaredConstructor(Context.class, AttributeSet.class, int.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); ViewGroup root = (ViewGroup) chain.getThisObject();
                    roots.put(root, null);
                    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> prepare((ViewGroup) v));
                    return result;
                });
        module.hook(host.getDeclaredMethod("draw", Canvas.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Path hole = roots.get((ViewGroup) chain.getThisObject());
                    if (!NativeModule.config().enabled || hole == null) return chain.proceed();
                    Canvas canvas = (Canvas) chain.getArg(0); int save = canvas.save();
                    try { canvas.clipOutPath(hole); return chain.proceed(); }
                    finally { canvas.restoreToCount(save); }
                });
        // Theme changes may replace the bitmap without changing the candidate view's size.
        for (Method method : candidate.getDeclaredMethods()) if (method.getName().equals("setTheme"))
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); refreshRoots(); return result;
            });
        module.hook(InputMethodService.class.getDeclaredMethod("onConfigurationChanged", Configuration.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); refreshRoots(); return result;
                });
        NativeModule.info("v6.98 stock Sogou white-cutout hook installed; native shape, keys and input retained");
    }
    private void refreshRoots() {
        for (ViewGroup root : new ArrayList<>(roots.keySet())) {
            roots.put(root, null); root.post(() -> { prepare(root); root.invalidate(); });
        }
    }
    private void prepare(ViewGroup root) {
        roots.put(root, null);
        Display display = root.getDisplay();
        if (!NativeModule.config().enabled || display == null || display.getCutout() == null) return;
        View view = findCandidate(root);
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        Drawable drawable = view.getBackground();
        if (!stockDrawable.isInstance(drawable)) return;
        try {
            if (!defaultTheme.getBoolean(view)) return;
            Bitmap source = (Bitmap) bitmap.invoke(drawable);
            // Do not rescale a guessed camera profile or interfere with floating/custom keyboards.
            if (source == null || source.isRecycled() || source.getConfig() == Bitmap.Config.HARDWARE
                    || source.getWidth() != view.getWidth() || source.getHeight() != view.getHeight()) return;
            Rect area = new Rect(0, 0, view.getWidth(), view.getHeight()); root.offsetDescendantRectToMyCoords(view, area);
            int[] screen = new int[2]; root.getLocationOnScreen(screen);
            if (screen[0] + area.left != 0 || screen[1] + area.top <= 0) return;
            int cameraWidth = 0;
            for (Rect rect : display.getCutout().getBoundingRects())
                if (rect.left == 0 && rect.top == 0 && rect.bottom > screen[1] + area.top) cameraWidth = rect.width();
            int[] spans = ImeWhiteCutout.rows(source.getWidth(), source.getHeight(), cameraWidth, source::getPixel);
            if (spans == null) return;
            Path path = new Path();
            for (int i = 0; i < spans.length; i += 3)
                path.addRect(area.left + spans[i + 1], area.top + spans[i], area.left + spans[i + 2], area.top + spans[i] + 1, Path.Direction.CW);
            roots.put(root, path);
        } catch (ReflectiveOperationException error) { NativeModule.warning("Sogou candidate cutout unavailable; original keyboard retained", error); }
    }
    private View findCandidate(View view) {
        if (candidate.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findCandidate(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
