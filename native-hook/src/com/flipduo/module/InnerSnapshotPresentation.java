package com.flipduo.module;

import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import java.lang.reflect.*;

/** Ephemeral IPC presentation only. Does not capture, overwrite or persist a snapshot. */
final class InnerSnapshotPresentation {
    private static final long INNER_PRESENTATION_ID = 1L << 62;
    private final Class<?> snapshotType;
    private final Constructor<?> builder;
    private final Method createBitmap;
    private final java.util.Map<String, Method> getters = new java.util.HashMap<>(), setters = new java.util.HashMap<>();
    private final Method build, setIgnoreAlign, writeToParcel;
    private final Field lowResolution;

    InnerSnapshotPresentation(ClassLoader loader) throws ReflectiveOperationException {
        snapshotType = loader.loadClass("android.window.TaskSnapshot");
        Class<?> type = loader.loadClass("android.window.TaskSnapshot$Builder");
        builder = type.getDeclaredConstructor(); builder.setAccessible(true);
        for (String name : new String[]{"getId", "getCaptureTime", "getTaskSize", "wrapToBitmap",
                "getDeviceState", "getWindowingMode", "isRealSnapshot", "hasProtectedContent", "isBufferValid",
                "getTopActivityComponent", "getAppearance", "isTranslucent", "hasImeSurface", "getUiMode", "getIgnoreAlignPosition", "isLowResolution"})
            getters.put(name, method(snapshotType, name));
        for (Method method : type.getDeclaredMethods()) if (method.getName().startsWith("set")) {
            method.setAccessible(true); setters.put(method.getName(), method);
        }
        for (String name : new String[]{"setId", "setCaptureTime", "setTaskSize", "setSnapshot", "setColorSpace", "setContentInsets",
                "setLetterboxInsets", "setOrientation", "setRotation", "setDeviceState", "setDensityDpi", "setWindowingMode",
                "setTopActivityComponent", "setAppearance", "setIsRealSnapshot", "setIsTranslucent", "setHasImeSurface", "setUiMode"})
            if (!setters.containsKey(name)) throw new NoSuchMethodException(name);
        build = method(type, "build"); setIgnoreAlign = method(snapshotType, "setIgnoreAlignPosition", boolean.class);
        writeToParcel = method(snapshotType, "writeToParcel", Parcel.class, int.class);
        lowResolution = snapshotType.getDeclaredField("mIsLowResolution"); lowResolution.setAccessible(true);
        createBitmap = method(HardwareRenderer.class, "createHardwareBitmap", RenderNode.class, int.class, int.class);
    }

    boolean eligible(Object source, int width, int height) throws ReflectiveOperationException {
        if (source == null || !snapshotType.isInstance(source)) return false;
        Point size = (Point) get(source, "getTaskSize");
        return size != null && InnerSnapshotLayout.eligible((int) get(source, "getDeviceState"), size.x, size.y, width, height)
                && (int) get(source, "getWindowingMode") == 1 && (boolean) get(source, "isRealSnapshot")
                && (boolean) get(source, "isBufferValid") && !(boolean) get(source, "hasProtectedContent");
    }
    long captureTime(Object source) throws ReflectiveOperationException { return (long) get(source, "getCaptureTime"); }

    void discardQueryReference(Object source) throws ReflectiveOperationException {
        // WRITE_TO_PARCEL has a per-write count, not just a reference bit. Let the platform consume exactly one
        // write into a discarded parcel; removeReference(16) would incorrectly clear other concurrent callers' writes.
        Parcel discarded = Parcel.obtain();
        try { writeToParcel.invoke(source, discarded, 0); }
        finally { discarded.recycle(); }
    }

    synchronized Object create(Object source, int width, int height, int rotation, int density, int background) throws ReflectiveOperationException {
        // This firmware leaves getHardwareBuffer() present but deliberately returns null. Use its real wrapper API.
        Bitmap wrapped = (Bitmap) get(source, "wrapToBitmap");
        if (wrapped == null) throw new IllegalArgumentException("Snapshot buffer cannot be wrapped");
        InnerSnapshotLayout fit = InnerSnapshotLayout.fit(wrapped.getWidth(), wrapped.getHeight(), width, height);
        RenderNode node = new RenderNode("FlipDuo inner recents snapshot fit");
        Bitmap output = null; HardwareBuffer buffer = null; boolean transferred = false;
        try {
            node.setPosition(0, 0, fit.width, fit.height);
            RecordingCanvas canvas = node.beginRecording(fit.width, fit.height);
            try {
                canvas.drawColor((background & 0xff000000) == 0 ? Color.BLACK : background);
                canvas.drawBitmap(wrapped, null,
                        new RectF(fit.left, fit.top, fit.left + fit.contentWidth, fit.top + fit.contentHeight),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
            } finally { node.endRecording(); }
            output = (Bitmap) createBitmap.invoke(null, node, fit.width, fit.height);
            if (output == null) throw new IllegalStateException("Snapshot fit renderer returned no buffer");
            buffer = output.getHardwareBuffer();
            Object copy = builder.newInstance();
            set(copy, "setId", (long) get(source, "getId") ^ INNER_PRESENTATION_ID);
            set(copy, "setCaptureTime", captureTime(source) + 1);
            set(copy, "setSnapshot", buffer); set(copy, "setColorSpace", output.getColorSpace());
            set(copy, "setTaskSize", new Point(width, height));
            set(copy, "setContentInsets", new Rect()); set(copy, "setLetterboxInsets", new Rect());
            set(copy, "setOrientation", width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT);
            set(copy, "setRotation", rotation); set(copy, "setDensityDpi", density); set(copy, "setDeviceState", 3);
            set(copy, "setWindowingMode", 1);
            set(copy, "setTopActivityComponent", get(source, "getTopActivityComponent"));
            set(copy, "setAppearance", get(source, "getAppearance")); set(copy, "setIsRealSnapshot", true);
            set(copy, "setIsTranslucent", get(source, "isTranslucent"));
            set(copy, "setHasImeSurface", get(source, "hasImeSurface")); set(copy, "setUiMode", get(source, "getUiMode"));
            Object result = build.invoke(copy);
            lowResolution.setBoolean(result, (boolean) get(source, "isLowResolution"));
            setIgnoreAlign.invoke(result, get(source, "getIgnoreAlignPosition"));
            transferred = true;
            return result;
        } finally {
            node.discardDisplayList(); wrapped.recycle();
            if (output != null) output.recycle();
            if (!transferred && buffer != null) buffer.close();
        }
    }
    private Object get(Object source, String name) throws ReflectiveOperationException { return getters.get(name).invoke(source); }
    private void set(Object builder, String name, Object value) throws ReflectiveOperationException { setters.get(name).invoke(builder, value); }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
}
