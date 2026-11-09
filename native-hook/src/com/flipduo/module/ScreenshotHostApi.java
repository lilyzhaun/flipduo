package com.flipduo.module;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact capture/export contracts verified from both supplied ROMs, resolved before registering hooks. */
final class ScreenshotHostApi {
    final Method setBitmap, getType, getDisplayId, legacyCapture, legacyDisplay;
    final Constructor<?> save;

    private ScreenshotHostApi(Method bitmap, Method type, Method display, Method capture, Method nativeDisplay, Constructor<?> save) {
        setBitmap = bitmap; getType = type; getDisplayId = display;
        legacyCapture = capture; legacyDisplay = nativeDisplay; this.save = save;
    }

    static ScreenshotHostApi resolve(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> data;
        try { data = loader.loadClass("com.miui.screenshot.core.domain.ScreenshotData"); }
        catch (ClassNotFoundException legacyPipeline) {
            // OS3 ruyi ROM: Q2.i.e(Context) is the completed default-display full capture;
            // n.F0 passes that exact bitmap to SaveImageData (R8 L). Do not hook rear/edited exports.
            Class<?> capture = loader.loadClass("Q2.i");
            Method completed = capture.getDeclaredMethod("e", Context.class);
            Method display = capture.getDeclaredMethod("c", Context.class);
            if (completed.getReturnType() != Bitmap.class || !Modifier.isStatic(completed.getModifiers())
                    || display.getReturnType() != Display.class || !Modifier.isStatic(display.getModifiers()))
                throw new NoSuchMethodException("Unrecognized legacy screenshot capture contract");
            Constructor<?> save = loader.loadClass("com.miui.screenshot.L").getDeclaredConstructor(Context.class,
                    Bitmap.class, int.class, Bitmap.CompressFormat.class, loader.loadClass("L2.a"), loader.loadClass("L2.b"));
            return new ScreenshotHostApi(null, null, null, completed, display, save);
        }
        Method type = data.getDeclaredMethod("getType"), display = data.getDeclaredMethod("getDisplayId");
        Method bitmap = data.getDeclaredMethod("setBitmap", Bitmap.class);
        Constructor<?> save = loader.loadClass("com.miui.screenshot.Z").getDeclaredConstructor(Context.class,
                Bitmap.class, int.class, Bitmap.CompressFormat.class,
                loader.loadClass("com.miui.screenshot.interfaces.ISaveCallback"),
                loader.loadClass("com.miui.screenshot.interfaces.ITotalFinishCallback"));
        return new ScreenshotHostApi(bitmap, type, display, null, null, save);
    }

    boolean modern() { return setBitmap != null; }
}
