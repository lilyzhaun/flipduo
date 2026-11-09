package com.flipduo.module;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Uses the running framework's region serializer, not an OS-version-dependent float layout. */
final class BlurFillRegion {
    static void apply(SurfaceControl.Transaction tx, SurfaceControl plane, Rect bounds) throws ReflectiveOperationException {
        // The two-argument JNI entry drops the MIUI crop extension (observed cropLTRB=0 on OS4).
        // Use the system drawable's extended entry, with one region and zero color blend operations.
        SurfaceControl.Transaction.class.getMethod("setBlurRegions", SurfaceControl.class, float[][].class, List.class, List.class)
                .invoke(tx, plane, rectangle(bounds, 110), Collections.singletonList(0), Collections.emptyList());
    }

    static float[][] rectangle(Rect bounds, int radius) throws ReflectiveOperationException {
        return rectangle(Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable"), bounds, radius);
    }

    static float[][] rectangle(Class<?> type, Rect bounds, int radius) throws ReflectiveOperationException {
        Class<?> aggregator = Class.forName(type.getName() + "$Aggregator");
        Constructor<?> drawableConstructor = type.getDeclaredConstructor(aggregator);
        drawableConstructor.setAccessible(true);
        // Detached encoding object only: never draw it, attach it to a ViewRoot or call setters
        // that notify its aggregator. The audited constructors accept null and initialize full alpha.
        Object drawable = drawableConstructor.newInstance((Object) null);
        field(type, "mBlurRadius").setInt(drawable, radius);
        ((Rect) field(type, "mRect").get(drawable)).set(bounds);
        ((Rect) field(type, "mCropRect").get(drawable)).set(bounds);
        Class<?> region = Class.forName(type.getName() + "$BlurRegion");
        Constructor<?> regionConstructor = region.getDeclaredConstructor(type);
        regionConstructor.setAccessible(true);
        Method encode = region.getDeclaredMethod("toFloatArray");
        encode.setAccessible(true);
        return new float[][] { (float[]) encode.invoke(regionConstructor.newInstance(drawable)) };
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
