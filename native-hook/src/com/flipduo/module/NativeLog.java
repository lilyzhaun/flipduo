package com.flipduo.module;

import android.util.Log;
import java.util.function.BiConsumer;

/** Shared views also run in the settings/preview process, where the Xposed API is not on the classpath. */
final class NativeLog {
    static BiConsumer<String, Throwable> writer = (message, error) -> {
        if (error == null) Log.i("FlipDuoNative", message);
        else Log.w("FlipDuoNative", message, error);
    };
    static void info(String message) { writer.accept(message, null); }
    static void warning(String message, Throwable error) { writer.accept(message, error); }
}
