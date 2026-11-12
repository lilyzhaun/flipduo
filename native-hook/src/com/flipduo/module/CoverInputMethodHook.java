// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Chooses the OEM cover IME identity; Xiaomi still owns fold/unfold, passwords, binding and layout.
 * Independently implemented against the ruyi OS3 / bixi OS4 ROMs; feature reference: MixFlipMod. */
final class CoverInputMethodHook {
    private static final String PREFIX = "com.android.server.inputmethod.";
    // Keep the ID and both identity predicates under the same owner when explicitly selected.
    // MixFlipMod 6b02e8c hooks only the predicates at the framework default priority (50).
    private static final int IDENTITY_PRIORITY = 10000;
    private final Field switcherService, currentUser;
    private final Method settingsForUser, enabledMethods;
    private final Class<?> imfLock;
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile CoverInputMethodConfig choice;
    private volatile boolean ready;

    CoverInputMethodHook(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> switcher = loader.loadClass(PREFIX + "SogouInputMethodSwitcher");
        Class<?> service = loader.loadClass(PREFIX + "InputMethodManagerService");
        Class<?> impl = loader.loadClass(PREFIX + "InputMethodManagerServiceImpl");
        imfLock = loader.loadClass(PREFIX + "ImfLock");
        switcherService = loader.loadClass(PREFIX + "BaseInputMethodSwitcher").getDeclaredField("mService");
        currentUser = service.getDeclaredField("mCurrentImeUserId");
        switcherService.setAccessible(true); currentUser.setAccessible(true);
        settingsForUser = loader.loadClass(PREFIX + "InputMethodSettingsRepository").getDeclaredMethod("get", int.class);
        enabledMethods = loader.loadClass(PREFIX + "InputMethodSettings").getDeclaredMethod("getEnabledInputMethodList");
        settingsForUser.setAccessible(true); enabledMethods.setAccessible(true);
        Method switcherId = switcher.getDeclaredMethod("getSogouMethodIdLocked");
        Method switcherMatch = switcher.getDeclaredMethod("isSogouMethodLocked", String.class);
        Method implId = impl.getDeclaredMethod("getSogouMethodIdLocked", service);
        Method implMatch = impl.getDeclaredMethod("isSogouMethodLocked", service, String.class);
        if (switcherId.getReturnType() != String.class || implId.getReturnType() != String.class
                || switcherMatch.getReturnType() != boolean.class || implMatch.getReturnType() != boolean.class)
            throw new NoSuchMethodException("Unexpected OEM cover IME query result type");
        // Small helpers may be inlined; resolve their verified callers before installing any interception.
        List<Method> callers = new ArrayList<>();
        callers.add(switcher.getDeclaredMethod("mayChangeInputMethodLocked", EditorInfo.class));
        callers.add(switcher.getDeclaredMethod("shouldHideImeSwitcherLocked"));
        callers.add(switcherId); callers.add(implId);
        Method countdown = impl.getDeclaredMethod("showCountdownAlertDialog", int.class,
                loader.loadClass(PREFIX + "InputMethodMenuController"), service, InputMethodInfo.class,
                int.class, Context.class, String.class, List.class);
        Method folded = null;
        for (Method method : impl.getDeclaredMethods()) {
            if (method.getName().startsWith("lambda$onDisplayDeviceStateChanged$")
                    && method.getParameterCount() == 0 && method.getReturnType() == void.class) {
                if (folded != null) throw new NoSuchMethodException("Ambiguous OEM fold IME callback");
                folded = method;
            }
        }
        if (folded == null) throw new NoSuchMethodException("OEM fold IME callback");
        callers.add(folded); callers.add(countdown);
        preferences = module.getRemotePreferences(DockConfig.GROUP);
        choice = CoverInputMethodConfig.read(preferences);
        listener = (prefs, key) -> {
            if (key == null || "enabled".equals(key) || CoverInputMethodConfig.KEY.equals(key)
                    || CoverInputMethodConfig.USER_KEY.equals(key)) choice = CoverInputMethodConfig.read(preferences);
        };
        for (Method caller : callers) if (!module.deoptimize(caller))
            throw new IllegalStateException("Cannot deoptimize cover IME caller: " + caller.getName());
        module.hook(switcherId).setPriority(IDENTITY_PRIORITY).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            String id = selected(switcherService.get(chain.getThisObject()));
            return id == null ? chain.proceed() : id;
        });
        module.hook(switcherMatch).setPriority(IDENTITY_PRIORITY).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            String id = selected(switcherService.get(chain.getThisObject()));
            return id == null ? chain.proceed() : id.equals(chain.getArg(0));
        });
        module.hook(implId).setPriority(IDENTITY_PRIORITY).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            String id = selected(chain.getArg(0));
            return id == null ? chain.proceed() : id;
        });
        module.hook(implMatch).setPriority(IDENTITY_PRIORITY).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            String id = selected(chain.getArg(0));
            return id == null ? chain.proceed() : id.equals(chain.getArg(1));
        });
        preferences.registerOnSharedPreferenceChangeListener(listener);
        choice = CoverInputMethodConfig.read(preferences);
        ready = true; // Partial installation remains inert if a required hook/deoptimization fails.
        NativeModule.info("Cover IME v149 installed: coherent selectable identity priority=10000; unselected or withdrawn choices delegate; native transitions retained");
    }

    @SuppressWarnings("unchecked")
    private String selected(Object service) throws ReflectiveOperationException {
        CoverInputMethodConfig config = choice;
        if (!ready || !config.enabled || !CoverInputMethodConfig.isSelectable(config.id) || service == null) return null;
        synchronized (imfLock) {
            int user = currentUser.getInt(service);
            if (!config.appliesTo(user)) return null;
            Object settings = settingsForUser.invoke(null, user);
            if (settings == null) return null; // Repository may not yet have the switching/unlocking user's state.
            return config.resolve(user, (List<InputMethodInfo>) enabledMethods.invoke(settings));
        }
    }
}
