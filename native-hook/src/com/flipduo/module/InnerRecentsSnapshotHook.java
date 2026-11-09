package com.flipduo.module;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.*;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Compatibility for native MiuiHome RELEASE-8.01.02.8593-260918-10241550-R retaining cover snapshots on the inner screen.
 * Remove when the native launcher preserves a cross-display snapshot's aspect itself. No upstream issue number is known.
 * Fits only at Home's inner-screen IPC boundary; does not replace the source snapshot or application configuration. */
final class InnerRecentsSnapshotHook {
    private static final int WRITE_REFERENCE = 16;
    private final InnerSnapshotPresentation presentation;
    private final Field owner, service, context, globalLock, root, windowConfig;
    private final Method findTask, getDisplay, getConfig, bounds, mode, rotation, taskDescription;
    private final Method addReference, closeBuffer;
    private final Method listenerProxy, listenerReleased;
    private final Map<IBinder, IBinder.DeathRecipient> homeListeners = new ConcurrentHashMap<>();
    private final Set<Integer> oldPreviewTasks = ConcurrentHashMap.newKeySet();
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private volatile boolean enabled;
    private volatile Object wms;

    InnerRecentsSnapshotHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        presentation = new InnerSnapshotPresentation(loader);
        Class<?> controller = loader.loadClass("com.android.server.wm.SnapshotController");
        Class<?> endpoint = loader.loadClass("com.android.server.wm.SnapshotController$SnapshotManagerService");
        Class<?> listener = loader.loadClass("android.window.ITaskSnapshotListener");
        Class<?> snapshot = loader.loadClass("android.window.TaskSnapshot");
        Class<?> wm = loader.loadClass("com.android.server.wm.WindowManagerService");
        owner = field(endpoint, "this$0"); service = field(controller, "mService");
        context = field(wm, "mContext"); globalLock = field(wm, "mGlobalLock"); root = field(wm, "mRoot");
        findTask = method(root.getType(), "anyTaskForId", int.class, int.class);
        getDisplay = method(loader.loadClass("com.android.server.wm.WindowContainer"), "getDisplayContent");
        getConfig = method(loader.loadClass("com.android.server.wm.ConfigurationContainer"), "getConfiguration");
        windowConfig = field(Configuration.class, "windowConfiguration");
        Class<?> wc = windowConfig.getType(); bounds = method(wc, "getBounds");
        mode = method(wc, "getWindowingMode"); rotation = method(wc, "getDisplayRotation");
        taskDescription = method(loader.loadClass("com.android.server.wm.Task"), "getTaskDescription");
        addReference = method(snapshot, "addReference", int.class);
        closeBuffer = method(snapshot, "closeBuffer");
        Method query = method(endpoint, "getTaskSnapshot", int.class, long.class, int.class);
        Method register = method(endpoint, "registerTaskSnapshotListener", listener);
        Method unregister = method(endpoint, "unregisterTaskSnapshotListener", listener);
        Method notify = method(endpoint, "lambda$notifySnapshotChanged$0", int.class, snapshot, listener);
        listenerProxy = method(loader.loadClass("android.window.ITaskSnapshotListener$Stub"), "asInterface", IBinder.class);
        listenerReleased = method(listener, "onTaskSnapshotReleased", int.class);
        preferences = module.getRemotePreferences(DockConfig.GROUP);
        enabled = InnerRecentsConfig.read(preferences);
        preferenceListener = (prefs, key) -> {
            if (key == null || InnerRecentsConfig.KEY.equals(key)) refreshPreference();
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        hook(module, query).intercept(chain -> {
            bind(chain.getThisObject());
            Target target = isHome(Binder.getCallingUid()) ? target((int) chain.getArg(0)) : null;
            if (target == null) return chain.proceed();
            int taskId = (int) chain.getArg(0);
            boolean apply = enabled;
            if (!apply && !oldPreviewTasks.contains(taskId)) {
                Object original = chain.proceed();
                rememberPreview(taskId, original, target);
                return original;
            }
            long latest = (long) chain.getArg(1);
            Object[] args = chain.getArgs().toArray(); args[1] = 0L;
            Object original = chain.proceed(args);
            if (original == null) return null;
            // Ask for the cached source even if Home knows its old capture time, but retain the OEM's no-change semantics.
            boolean adapt;
            try { adapt = presentation.eligible(original, target.width, target.height); }
            catch (ReflectiveOperationException error) { NativeLog.warning("Inner snapshot eligibility failed", error); return original; }
            if (adapt) oldPreviewTasks.add(taskId); else oldPreviewTasks.remove(taskId);
            // OFF restores the original image even if Home still has the presentation's later capture time cached.
            if (!apply || !enabled) return original;
            long sourceTime = presentation.captureTime(original);
            if (latest > 0 && latest >= sourceTime + (adapt ? 1 : 0)) {
                presentation.discardQueryReference(original); return null;
            }
            if (!adapt) return original;
            Object copy;
            try { copy = presentation.create(original, target.width, target.height, target.rotation, target.density, target.background); }
            catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException error) {
                NativeLog.warning("Cannot fit stale cover snapshot; original IPC retained", error); return original;
            }
            if (!enabled) { closeBuffer.invoke(copy); return original; }
            // SnapshotController supplied one WRITE_TO_PARCEL reference. Transfer exactly that reference to the copy.
            addReference.invoke(copy, WRITE_REFERENCE); presentation.discardQueryReference(original);
            NativeLog.info("Inner recents fitted cached cover snapshot task=" + chain.getArg(0) + " target=" + target.width + "x" + target.height);
            return copy;
        });
        hook(module, register).intercept(chain -> {
            bind(chain.getThisObject());
            boolean home = isHome(Binder.getCallingUid());
            Object result = chain.proceed();
            if (home) {
                IBinder binder = ((IInterface) chain.getArg(0)).asBinder();
                IBinder.DeathRecipient death = () -> homeListeners.remove(binder);
                IBinder.DeathRecipient previous = homeListeners.putIfAbsent(binder, death);
                if (previous == null) try { binder.linkToDeath(death, 0); }
                catch (RemoteException gone) { homeListeners.remove(binder, death); }
            }
            return result;
        });
        hook(module, unregister).intercept(chain -> {
            Object result = chain.proceed();
            IBinder binder = ((IInterface) chain.getArg(0)).asBinder();
            IBinder.DeathRecipient death = homeListeners.remove(binder);
            if (death != null) binder.unlinkToDeath(death, 0);
            return result;
        });
        hook(module, notify).intercept(chain -> {
            if (!homeListeners.containsKey(((IInterface) chain.getArg(2)).asBinder())) return chain.proceed();
            Target target = target((int) chain.getArg(0));
            Object original = chain.getArg(1);
            if (target == null) return chain.proceed();
            if (!presentation.eligible(original, target.width, target.height)) {
                if (original != null) oldPreviewTasks.remove((int) chain.getArg(0));
                return chain.proceed();
            }
            oldPreviewTasks.add((int) chain.getArg(0));
            if (!enabled) return chain.proceed();
            Object copy;
            try { copy = presentation.create(original, target.width, target.height, target.rotation, target.density, target.background); }
            catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException error) {
                NativeLog.warning("Cannot fit inner snapshot notification; original retained", error); return chain.proceed();
            }
            if (!enabled) { closeBuffer.invoke(copy); return chain.proceed(); }
            Object[] args = chain.getArgs().toArray(); args[1] = copy;
            // This copy has no cache/broadcast owner. Binder duplicates its buffer before the callback returns.
            try { return chain.proceed(args); }
            finally { closeBuffer.invoke(copy); }
        });
        NativeLog.info("Inner recents snapshot v6.23 hooks installed; optional policy enabled=" + enabled);
    }

    private void rememberPreview(int taskId, Object snapshot, Target target) throws ReflectiveOperationException {
        if (presentation.eligible(snapshot, target.width, target.height)) oldPreviewTasks.add(taskId);
        else if (snapshot != null) oldPreviewTasks.remove(taskId);
    }
    private synchronized void refreshPreference() {
        boolean next = InnerRecentsConfig.read(preferences);
        if (next == enabled) return;
        enabled = next;
        // Invalidate only Home's presentation handles, never the system snapshot cache or task records.
        for (IBinder binder : homeListeners.keySet()) {
            try {
                Object listener = listenerProxy.invoke(null, binder);
                for (int taskId : oldPreviewTasks) {
                    Target current = target(taskId);
                    if (current != null) listenerReleased.invoke(listener, taskId);
                }
            } catch (ReflectiveOperationException error) { NativeLog.warning("Cannot invalidate Home preview after compatibility toggle", error); }
        }
        NativeLog.info("Inner recents compatibility preference applied enabled=" + enabled + " previewTasks=" + oldPreviewTasks.size());
    }

    private void bind(Object endpoint) throws ReflectiveOperationException {
        if (wms == null) wms = service.get(owner.get(endpoint));
    }
    private boolean isHome(int uid) throws ReflectiveOperationException {
        String[] packages = ((Context) context.get(wms)).getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains("com.miui.home");
    }
    private Target target(int taskId) throws ReflectiveOperationException {
        Object service = wms;
        if (service == null) return null;
        synchronized (globalLock.get(service)) {
            Object task = findTask.invoke(root.get(service), taskId, 1);
            Object display = task == null ? null : getDisplay.invoke(task);
            if (display == null) return null;
            Configuration taskConfig = (Configuration) getConfig.invoke(task);
            if ((int) mode.invoke(windowConfig.get(taskConfig)) != 1) return null;
            Configuration config = (Configuration) getConfig.invoke(display);
            Object wc = windowConfig.get(config); Rect area = (Rect) bounds.invoke(wc);
            if (!((area.width() == 1224 && area.height() == 2912) || (area.width() == 2912 && area.height() == 1224))) return null;
            ActivityManager.TaskDescription description = (ActivityManager.TaskDescription) taskDescription.invoke(task);
            return new Target(area.width(), area.height(), (int) rotation.invoke(wc), config.densityDpi,
                    description == null ? 0 : description.getBackgroundColor());
        }
    }
    private record Target(int width, int height, int rotation, int density, int background) { }
    private static XposedInterface.HookBuilder hook(NativeModule module, Executable method) {
        return module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
}
