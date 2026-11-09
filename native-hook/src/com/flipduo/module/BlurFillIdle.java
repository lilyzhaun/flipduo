package com.flipduo.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** One event-driven Shell idle callback, not a timer or a per-frame task/transform query. */
final class BlurFillIdle {
    private final Field executorField;
    private final Method runOnIdle;
    private final Executor main;
    private final Runnable changed;
    private Object owner;
    private Executor shell;
    private final AtomicInteger generation = new AtomicInteger();
    private int settledGeneration = -1;
    private boolean pending;

    BlurFillIdle(Class<?> type, Executor main, Runnable changed) throws ReflectiveOperationException {
        executorField = type.getDeclaredField("mMainExecutor"); executorField.setAccessible(true);
        runOnIdle = type.getDeclaredMethod("runOnIdle", Runnable.class); runOnIdle.setAccessible(true);
        this.main = main; this.changed = changed;
    }
    void connect(Object next) throws IllegalAccessException {
        if (owner == next) return;
        owner = next; shell = (Executor) executorField.get(next); pending = false; invalidate(); changed.run();
    }
    // Shell dispatch and SystemUI's main handler need not be the same thread.
    void invalidate() { generation.incrementAndGet(); }
    boolean connected() { return owner != null; }
    boolean settled() { return owner != null && settledGeneration == generation.get(); }
    boolean await() {
        if (settled() || pending || owner == null) return settled();
        pending = true;
        int expected = generation.get(); Object current = owner;
        shell.execute(() -> {
            try {
                runOnIdle.invoke(current, (Runnable) () -> main.execute(() -> {
                    if (owner != current) return;
                    pending = false;
                    if (expected == generation.get()) settledGeneration = expected;
                    changed.run(); // An invalidated callback permits one fresh idle request, not a stale reveal.
                }));
            } catch (ReflectiveOperationException | RuntimeException error) {
                main.execute(() -> {
                    if (owner != current) return;
                    owner = null; pending = false;
                    NativeModule.warning("Blur-fill Shell idle unavailable; keep side-only presentation", error);
                    changed.run();
                });
            }
        });
        return false;
    }
}
