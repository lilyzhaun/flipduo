package com.flipduo.module;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import java.lang.reflect.*;

/** SDK-37 capture names verified on this device. Keeps the system's default secure-content policy. */
final class CaptureBackend {
    private final Class<?> api, builder, listener, bufferType, argsType;
    private final Object wm;
    private final Method capture;

    CaptureBackend() throws Exception {
        Class<?> selected;
        try { selected = Class.forName("android.window.ScreenCaptureInternal"); }
        catch (ClassNotFoundException olderAndroid) { selected = Class.forName("android.window.ScreenCapture"); }
        api = selected;
        builder = Class.forName(api.getName() + "$CaptureArgs$Builder");
        argsType = Class.forName(api.getName() + "$CaptureArgs");
        listener = Class.forName(api.getName() + "$SynchronousScreenCaptureListener");
        bufferType = Class.forName(api.getName() + "$ScreenshotHardwareBuffer");
        Class<?> listenerBase = Class.forName(api.getName() + "$ScreenCaptureListener");
        wm = Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getWindowManagerService").invoke(null);
        capture = Class.forName("android.view.IWindowManager").getMethod("captureDisplay", int.class, argsType, listenerBase);
    }

    Bitmap region(int displayId, Rect crop) throws Exception {
        Object b = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("setSourceCrop", Rect.class).invoke(b, crop);
        builder.getMethod("setFrameScale", float.class).invoke(b, 1f);
        // Never enable secure-layer capture or change the platform's secure-content policy.
        Object args = builder.getMethod("build").invoke(b);
        Object sync = api.getMethod("createSyncCaptureListener").invoke(null);
        capture.invoke(wm, displayId, args, sync);
        Object result = listener.getMethod("getBuffer").invoke(sync);
        if (result == null) throw new IllegalStateException("Native capture returned no buffer");
        HardwareBuffer hardware = (HardwareBuffer) bufferType.getMethod("getHardwareBuffer").invoke(result);
        Bitmap wrapped = null;
        try {
            if ((Boolean) bufferType.getMethod("containsSecureLayers").invoke(result))
                throw new SecurityException("Protected layer present; colour sample discarded");
            wrapped = (Bitmap) bufferType.getMethod("asBitmap").invoke(result);
            if (wrapped == null) throw new IllegalStateException("Native capture has no bitmap");
            return wrapped.copy(Bitmap.Config.ARGB_8888, false);
        } finally {
            if (wrapped != null) wrapped.recycle();
            if (hardware != null) hardware.close();
        }
    }
}
