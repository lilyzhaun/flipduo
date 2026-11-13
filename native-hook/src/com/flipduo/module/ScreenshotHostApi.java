package com.flipduo.module;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Display;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Exact capture/export contracts verified from both supplied ROMs, resolved before registering hooks. */
final class ScreenshotHostApi {
    final Method setBitmap, getType, getDisplayId, legacyComplete, legacyDisplay;
    final Field legacyContext, legacyBitmap;
    final Method[] legacyCallers;
    final Constructor<?> save;
    final Method softwareBitmap, softwareCaller;

    private ScreenshotHostApi(Method bitmap, Method type, Method display, Method complete, Method nativeDisplay,
                              Field captureContext, Field captureBitmap, Method[] callers, Constructor<?> save,
                              Method softwareBitmap, Method softwareCaller) {
        setBitmap = bitmap; getType = type; getDisplayId = display;
        legacyComplete = complete; legacyDisplay = nativeDisplay; this.save = save;
        legacyContext = captureContext; legacyBitmap = captureBitmap; legacyCallers = callers;
        this.softwareBitmap = softwareBitmap; this.softwareCaller = softwareCaller;
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
            return new ScreenshotHostApi(null, null, null, completed, display, context, image, callers, save, null, null);
        }
        Method type = data.getDeclaredMethod("getType"), display = data.getDeclaredMethod("getDisplayId");
        Method bitmap = data.getDeclaredMethod("setBitmap", Bitmap.class);
        Class<?>[] parameters = {Context.class, Bitmap.class, int.class, Bitmap.CompressFormat.class,
                loader.loadClass("com.miui.screenshot.interfaces.ISaveCallback"),
                loader.loadClass("com.miui.screenshot.interfaces.ITotalFinishCallback")};
        try {
            // Preserve the verified older metadata pipeline when its exact constructor still exists.
            Constructor<?> save = loader.loadClass("com.miui.screenshot.Z").getDeclaredConstructor(parameters);
            return new ScreenshotHostApi(bitmap, type, display, null, null, null, null, new Method[0], save, null, null);
        } catch (ClassNotFoundException | NoSuchMethodException newerSaveData) {
            // Verified RELEASE-1.6.3.44: Z was reused for a Runnable; b0 owns the save data.
            // B.g converts HARDWARE input to a software copy before passing it to b0.
            Constructor<?> save = loader.loadClass("com.miui.screenshot.b0").getDeclaredConstructor(parameters);
            Class<?> bitmapUtils = loader.loadClass("com.miui.screenshot.BitmapUtils");
            Method convert;
            try { convert = bitmapUtils.getDeclaredMethod("g", Bitmap.class, boolean.class); }
            catch (NoSuchMethodException sharedSoftwarePipeline) {
                // Verified 1.6.3.47: g(Bitmap) owns asShared/recycle and falls back to h(Bitmap, true).
                // Hook its returned bitmap, not only h: successful asShared never calls h.
                convert = bitmapUtils.getDeclaredMethod("g", Bitmap.class);
            }
            Method caller = loader.loadClass("com.miui.screenshot.B").getDeclaredMethod("g", data, Consumer.class,
                    loader.loadClass("com.miui.screenshot.TakeScreenshotService$b"));
            if (convert.getReturnType() != Bitmap.class || !Modifier.isStatic(convert.getModifiers())
                    || caller.getReturnType() != void.class || Modifier.isStatic(caller.getModifiers()))
                throw new NoSuchMethodException("Unrecognized screenshot software-conversion contract");
            return new ScreenshotHostApi(bitmap, type, display, null, null, null, null, new Method[0], save, convert, caller);
        }
    }

    boolean modern() { return setBitmap != null; }
}
