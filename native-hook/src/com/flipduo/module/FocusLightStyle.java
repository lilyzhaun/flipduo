package com.flipduo.module;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Pins only the native focus component to its own opaque light palette, never the FlipHome column or app theme. */
final class FocusLightStyle {
    private final BooleanSupplier enabled;
    private final Field helperField, pagerField, stateField, paramsField, helperOwner, updating;
    private final Field whiteContent, blur, firstColor, blendColors, lightBase, lightOverlay;
    private final Method update, allItems, childViews, updateContent, backgroundBlur, viewBlur, clearBlend, composite;
    private Object lastHelper;
    private boolean applied;

    static boolean supportsContentUpdate(Class<?> owner) {
        try { owner.getDeclaredMethod("onlyUpdateContentView", ViewGroup.class, boolean.class); return true; }
        catch (NoSuchMethodException initOnlyPalette) { return false; }
    }

    FocusLightStyle(NativeModule module, ClassLoader loader, BooleanSupplier enabled) throws ReflectiveOperationException {
        this.enabled = enabled;
        Class<?> owner = loader.loadClass("com.android.notification.decor.DecorWindowManagerImpl");
        Class<?> helper = loader.loadClass("com.android.notification.decor.utils.DecorWindowBgHelper");
        Class<?> pager = loader.loadClass("com.android.notification.decor.view.DecorViewPager");
        helperField = field(owner, "mDecorWindowBgHelper"); pagerField = field(helper, "mPagerView");
        stateField = field(helper, "mBackgroundState"); paramsField = field(helper, "mBgParams");
        helperOwner = field(helper, "mDecorWindowManager");
        updating = field(helper, "mIsUpdating");
        Class<?> state = stateField.getType(), params = paramsField.getType();
        whiteContent = field(state, "useWhiteContent"); blur = field(state, "isBlur");
        firstColor = field(state, "firstColor"); blendColors = field(state, "blendFirstColors");
        lightBase = field(params, "UN_BLUR_LIGHT_COLOR"); lightOverlay = field(params, "LIGHT_NO_BLUR_COLOR_TWO");
        update = method(helper, "updateDecorWindowBackground");
        allItems = method(pager, "getAllItems"); childViews = method(pager, "getChildViews");
        updateContent = method(owner, "onlyUpdateContentView", ViewGroup.class, boolean.class);
        backgroundBlur = method(View.class, "setMiBackgroundBlurMode", int.class);
        viewBlur = method(View.class, "setMiViewBlurMode", int.class);
        clearBlend = method(loader.loadClass("com.android.notification.decor.utils.BlurColorUtil"),
                "setMiBackgroundBlendColors", View.class, ArrayList.class);
        composite = method(loader.loadClass("androidx.core.graphics.ColorUtils"), "compositeColors", int.class, int.class);
        module.hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object current = chain.getThisObject();
            if (!enabled.getAsBoolean()) { applied = false; return chain.proceed(); }
            // Let the OEM initialize its state once. Subsequent scene updates never apply a dark/blurred palette.
            if (stateField.get(current) == null) chain.proceed();
            lastHelper = current; applied = render(current); return null;
        });
        for (String name : new String[]{"updateView", "updateRemoteView"}) {
            module.hook(method(owner, name, boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                return enabled.getAsBoolean() ? chain.proceed(new Object[]{false}) : chain.proceed();
            });
        }
        module.hook(method(owner, "updateContentView", boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object currentHelper = helperField.get(chain.getThisObject());
            Object currentPager = currentHelper == null ? null : pagerField.get(currentHelper);
            if (currentPager != null) cancelReplacedContent((List<?>) childViews.invoke(currentPager));
            // A full native rebuild supersedes the previous children even while our context/keyguard gate
            // is not ready. Do not let a palette swap started BEFORE activation later steal the new View.
            // Outside our light palette scope, the new content and its palette remain entirely native.
            return chain.proceed();
        });
        module.hook(updateContent).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!enabled.getAsBoolean()) return chain.proceed();
            ViewGroup parent = (ViewGroup) chain.getArg(0);
            cancelContentUpdate(parent);
            Object currentHelper = helperField.get(chain.getThisObject());
            Object currentPager = currentHelper == null ? null : pagerField.get(currentHelper);
            // An OEM doOnAttach callback may still reference a container replaced by updateContentView.
            if (currentPager == null || !((List<?>) childViews.invoke(currentPager)).contains(parent)) return null;
            // A current but not-yet-attached child keeps the OEM's doOnAttach; it re-enters this guarded hook.
            Object result = chain.proceed(new Object[]{parent, false});
            // OS4 swaps the actual View at the midpoint of a 500ms palette animator. Leaving that callback
            // alive lets it removeAllViews() from a NEW parent after a synchronous native scene rebuild.
            // Finish this palette transaction before returning; expansion/swipe animations are untouched.
            finishContentUpdate(parent);
            return result;
        });
    }

    static void cancelReplacedContent(List<?> children) {
        for (Object child : children) if (child instanceof ViewGroup group) cancelContentUpdate(group);
    }

    static void cancelContentUpdate(ViewGroup parent) {
        if (parent.getTag() instanceof ValueAnimator animator) {
            // cancel() dispatches onAnimationEnd too; remove listeners BEFORE cancelling an obsolete swap.
            animator.removeAllUpdateListeners(); animator.removeAllListeners(); animator.cancel();
            parent.setTag(null);
        }
    }

    static void finishContentUpdate(ViewGroup parent) {
        if (parent.getTag() instanceof ValueAnimator animator) {
            animator.end(); // Keep the ROM's exact content choice, layout params and old-child cleanup.
            animator.removeAllUpdateListeners(); animator.removeAllListeners();
            if (parent.getTag() == animator) parent.setTag(null);
        }
    }

    void refresh(Object manager) throws ReflectiveOperationException {
        if (manager == null) return;
        Object helper = helperField.get(manager);
        if (helper == null) return;
        boolean active = enabled.getAsBoolean();
        if (active && (helper != lastHelper || !applied)) update.invoke(helper);
        else if (!active && applied) { applied = false; update.invoke(helper); }
    }

    private boolean render(Object helper) throws ReflectiveOperationException {
        Object state = stateField.get(helper), params = paramsField.get(helper);
        View pager = (View) pagerField.get(helper);
        if (state == null || params == null || pager == null) return false;
        List<?> items = (List<?>) allItems.invoke(pager);
        if (items == null || items.isEmpty()) return false;
        int color = (int) composite.invoke(null, lightOverlay.getInt(params), lightBase.getInt(params));
        boolean needsLightContent = whiteContent.getBoolean(state);
        whiteContent.setBoolean(state, false); blur.setBoolean(state, false);
        firstColor.setInt(state, color); blendColors.set(state, null);
        backgroundBlur.invoke(pager, 0);
        for (Object item : items) {
            View view = (View) item;
            if (view.getTag() instanceof ValueAnimator animator) {
                animator.cancel(); animator.removeAllUpdateListeners(); animator.removeAllListeners();
            }
            viewBlur.invoke(view, 0); clearBlend.invoke(null, view, null);
            view.setBackgroundColor(color); // Existing native outline and clipping are retained.
        }
        if (needsLightContent) {
            for (Object child : (List<?>) childViews.invoke(pager))
                if (child instanceof ViewGroup group) updateContent.invoke(helperOwner.get(helper), group, false);
        }
        updating.setBoolean(helper, false);
        return true;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments); method.setAccessible(true); return method;
    }
}
