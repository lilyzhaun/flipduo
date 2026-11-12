package com.flipduo.module;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact capture/export contracts verified from both supplied ROMs, resolved before registering hooks. */
final class ScreenshotHostApi {
    final Method setBitmap, getType, getDisplayId, legacyComplete, legacyDisplay;
    final Field legacyContext, legacyBitmap;
    final Method[] legacyCallers;
    final Constructor<?> save;

    private ScreenshotHostApi(Method bitmap, Method type, Method display, Method complete, Method nativeDisplay,
                              Field captureContext, Field captureBitmap, Method[] callers, Constructor<?> save) {
        setBitmap = bitmap; getType = type; getDisplayId = display;
        legacyComplete = complete; legacyDisplay = nativeDisplay; this.save = save;
        legacyContext = captureContext; legacyBitmap = captureBitmap; legacyCallers = callers;
    }

    static ScreenshotHostApi resolve(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> data;
        try { data = loader.loadClass("com.miui.screenshot.core.domain.ScreenshotData"); }
        catch (ClassNotFoundException legacyPipeline) {
            // OS3 ruyi ROM: n.c runs after the capture worker stores the completed Bitmap.
            // Its original R0 -> F0 path passes that exact object to SaveImageData (R8 L).
            Class<?> capture = loader.loadClass("Q2.i");
            Method display = capture.getDeclaredMethod("c", Context.class);
            Class<?> owner = loader.loadClass("com.miui.screenshot.n");
            Class<?> callback = loader.loadClass("L2.a"), finish = loader.loadClass("L2.b");
            Method completed = owner.getDeclaredMethod("c", owner, Runnable.class, callback, finish, boolean.class);
            Field context = owner.getDeclaredField("d"), image = owner.getDeclaredField("m");
            if (completed.getReturnType() != void.class || !Modifier.isStatic(completed.getModifiers())
                    || display.getReturnType() != Display.class || !Modifier.isStatic(display.getModifiers())
                    || context.getType() != Context.class || image.getType() != Bitmap.class)
                throw new NoSuchMethodException("Unrecognized legacy screenshot completion contract");
            context.setAccessible(true); image.setAccessible(true);
            Method[] callers = {loader.loadClass("com.miui.screenshot.f").getDeclaredMethod("run"), completed,
                    owner.getDeclaredMethod("R0", Runnable.class, callback, finish, boolean.class),
                    owner.getDeclaredMethod("F0", callback, finish)};
            for (Method caller : callers)
                if (caller.getReturnType() != void.class || Modifier.isStatic(caller.getModifiers()) != (caller == completed))
                    throw new NoSuchMethodException("Unrecognized legacy screenshot caller contract: " + caller.getName());
            Constructor<?> save = loader.loadClass("com.miui.screenshot.L").getDeclaredConstructor(Context.class,
                    Bitmap.class, int.class, Bitmap.CompressFormat.class, callback, finish);
            return new ScreenshotHostApi(null, null, null, completed, display, context, image, callers, save);
        }
        Method type = data.getDeclaredMethod("getType"), display = data.getDeclaredMethod("getDisplayId");
        Method bitmap = data.getDeclaredMethod("setBitmap", Bitmap.class);
        Constructor<?> save = loader.loadClass("com.miui.screenshot.Z").getDeclaredConstructor(Context.class,
                Bitmap.class, int.class, Bitmap.CompressFormat.class,
                loader.loadClass("com.miui.screenshot.interfaces.ISaveCallback"),
                loader.loadClass("com.miui.screenshot.interfaces.ITotalFinishCallback"));
        return new ScreenshotHostApi(bitmap, type, display, null, null, null, null, new Method[0], save);
    }

    boolean modern() { return setBitmap != null; }
}
