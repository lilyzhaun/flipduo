// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import static io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE;

/** Restore the OEM modal menu for a notification long-pressed in the unlocked cover shade. */
final class CoverNotificationLongPress {
    private static final String NOTIFICATION = "com.android.systemui.statusbar.notification.";
    private static final String ROW = NOTIFICATION + "row.";
    private static final String MODAL = NOTIFICATION + "modal.";
    private static final String BINDER = "com.miui.systemui.notification.view.viewbinder.NotificationRowViewBinder";
    private static final String CONFIGS = "com.miui.utils.configs.MiuiConfigs";
    private final Class<?> row, stack, menu;
    private final Field expanded, entryRow, modalWindow, modalEntry, menuParent, menuContainer;
    private final Method pinned, headsUp, showingPublic, getEntry, provider, createMenu, buildMenu;
    private final ModalLayout modalLayout;
    private final ThreadLocal<Decision> decision = new ThreadLocal<>();
    private final WeakHashMap<View, ModalMenu> windows = new WeakHashMap<>();

    static void install(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        new CoverNotificationLongPress(module, loader);
    }

    private CoverNotificationLongPress(XposedInterface module, ClassLoader loader) throws ReflectiveOperationException {
        row = loader.loadClass(ROW + "ExpandableNotificationRow");
        stack = loader.loadClass(NOTIFICATION + "stack.NotificationStackScrollLayout");
        menu = loader.loadClass(ROW + "MiuiNotificationMenuRow");
        Class<?> entry = loader.loadClass(NOTIFICATION + "collection.NotificationEntry");
        Class<?> window = loader.loadClass(MODAL + "ModalWindowView");
        Class<?> modal = loader.loadClass(MODAL + "ModalControllerImpl");
        Class<?> configs = loader.loadClass(CONFIGS);
        Method tiny;
        try { tiny = configs.getDeclaredMethod("isTinyScreen", Context.class, boolean.class); }
        catch (NoSuchMethodException older) { tiny = configs.getDeclaredMethod("isTinyScreen", Context.class); }
        boolean contextual = tiny.getParameterCount() == 2;
        Dispatch dispatch = Dispatch.resolve(loader, row, contextual);
        Method open = modal.getDeclaredMethod("tryAnimEnterModal", row);
        Method add = window.getDeclaredMethod("addMenu", entry);
        Method remove = window.getDeclaredMethod("removeMenu");
        expanded = CoverLandscapeShade.field(stack, "mIsExpanded");
        entryRow = CoverLandscapeShade.field(entry, "row");
        modalWindow = CoverLandscapeShade.field(modal, "modalWindowView");
        modalEntry = CoverLandscapeShade.field(window, "mEntry");
        menuParent = CoverLandscapeShade.field(menu, "mParent");
        menuContainer = CoverLandscapeShade.field(menu, "mMenuContainer");
        pinned = row.getMethod("isPinned");
        headsUp = row.getMethod("isHeadsUpState");
        showingPublic = row.getMethod("shouldShowPublic");
        getEntry = row.getMethod("getEntry");
        provider = row.getMethod("getProvider");
        createMenu = menu.getMethod("createMenu", ViewGroup.class);
        buildMenu = menu.getDeclaredMethod("createMenuViews", boolean.class);
        // These two callers each contain one target predicate. Do not let ART inline that predicate.
        if (!module.deoptimize(dispatch.method) || !module.deoptimize(buildMenu))
            throw new ReflectiveOperationException("Cannot isolate the cover notification menu checks");

        module.hook(tiny).setExceptionMode(PROTECTIVE).intercept(c -> {
            if (!enabled()) return c.proceed();
            Decision d = decision.get();
            if (d == null || d.used || contextual && !(boolean)c.getArg(1) || !d.directCaller()) return c.proceed();
            d.used = (boolean)c.proceed();
            return false;
        });
        module.hook(open).setExceptionMode(PROTECTIVE).intercept(c -> openModal(c, dispatch.method));
        module.hook(add).setExceptionMode(PROTECTIVE).intercept(this::addMenu);
        module.hook(remove).setExceptionMode(PROTECTIVE).intercept(c -> {
            Object result = c.proceed();
            ModalMenu state = windows.get(c.getThisObject());
            if (state != null) restoreMenu(state);
            return result;
        });
        module.hook(buildMenu).setExceptionMode(PROTECTIVE).intercept(c -> {
            Object items = c.getThisObject();
            View container = (View)menuContainer.get(items);
            if (container != null && container.getParent() instanceof View windowView) {
                ModalMenu state = windows.get(windowView);
                if (state != null && state.menu() == items && enabled() && rowReady((View)menuParent.get(items)))
                    return during(buildMenu, c::proceed);
            }
            return c.proceed();
        });
        modalLayout = installModalLayout(module, window);
        // Arm dispatch last so a partially installed hook cannot reroute a gesture.
        module.hook(dispatch.method).setExceptionMode(PROTECTIVE).intercept(c -> {
            if (!enabled()) return c.proceed();
            View view = dispatch.source(c);
            if (!rowReady(view) || !view.isShown()) return c.proceed();
            for (var parent = view.getParent(); parent != null; parent = parent.getParent()) {
                if (stack.isInstance(parent)) return expanded.getBoolean(parent) ? during(dispatch.method, c::proceed) : c.proceed();
            }
            return c.proceed();
        });
    }

    private ModalLayout installModalLayout(XposedInterface module, Class<?> window) {
        try {
            ModalLayout layout = new ModalLayout(CoverLandscapeShade.field(window, "mModalRow"),
                    CoverLandscapeShade.field(window, "mShowMenu"), CoverLandscapeShade.field(window, "mSidePaddings"));
            Method insets = window.getDeclaredMethod("onApplyWindowInsets", WindowInsets.class);
            module.hook(insets).setExceptionMode(PROTECTIVE).intercept(c -> {
                Object result = c.proceed();
                View view = (View)c.getThisObject();
                ModalMenu state = windows.computeIfAbsent(view, ignored -> new ModalMenu());
                state.systemBars = ((WindowInsets)c.getArg(0)).getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                fitModalRow(view, state, layout);
                return result;
            });
            return layout;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            NativeLog.warning("Cannot adapt cover notification card width; native layout retained", error);
            return null;
        }
    }

    private void fitModalRow(View window, ModalMenu state, ModalLayout layout) throws IllegalAccessException {
        Object entry = state.entry();
        if (entry == null || modalEntry.get(window) != entry) return;
        if (!enabled() || !NativeModule.config().notificationFullscreen || !layout.showMenu.getBoolean(window)
                || !CoverPanelInsets.coverUnlocked(window)) {
            state.restoreRowLayout();
            return;
        }
        View card = (View)layout.row.get(window);
        if (card == null || !card.isAttachedToWindow() || card.getParent() != window || card == entryRow.get(entry)) return;
        Insets bars = state.systemBars;
        if (bars == null) return;

        // The OEM modal window does not clip children to its padding. Expand only the card
        // through the extra camera space; actions still measure inside the original safe area.
        int side = layout.sidePaddings.getInt(window);
        int left = Math.max(0, window.getPaddingLeft() - side - bars.left);
        int right = Math.max(0, window.getPaddingRight() - side - bars.right);
        state.fitRowLayout(card, left, right);
    }

    private Object openModal(XposedInterface.Chain chain, Method dispatcher) throws Throwable {
        View window = (View)modalWindow.get(chain.getThisObject());
        ModalMenu state = windows.get(window);
        Decision d = decision.get();
        if (d == null || d.caller != dispatcher || !d.used) {
            // A stock modal opened after the previous one closed must not inherit its menu mode.
            if (state != null && modalEntry.get(window) != state.entry()) state.entry = null;
            return chain.proceed();
        }
        decision.remove(); // Only the one modal reached from the approved long press belongs to this dispatch.
        View source = (View)chain.getArg(0);
        // The group callback can hand the modal a different row. Respect the inner-screen restrictions.
        if (!enabled() || !rowReady(source)) return null;
        Object entry = getEntry.invoke(source);
        if (window == null || entry == null) return chain.proceed();
        state = windows.computeIfAbsent(window, ignored -> new ModalMenu());
        WeakReference<Object> previous = state.entry;
        state.entry = new WeakReference<>(entry);
        Object result;
        try { result = chain.proceed(); }
        finally {
            // mEntry is published before addMenu, including the deferred first-pre-draw path.
            if (modalEntry.get(window) != entry) state.entry = previous;
        }
        if (modalLayout != null && modalEntry.get(window) == entry) {
            CoverNotificationLayout.trackForInsetRefresh(window);
            if (state.systemBars == null) window.requestApplyInsets();
            fitModalRow(window, state, modalLayout);
        }
        return result;
    }

    private Object addMenu(XposedInterface.Chain chain) throws Throwable {
        View window = (View)chain.getThisObject();
        ModalMenu state = windows.get(window);
        Object entry = chain.getArg(0);
        if (!enabled() || state == null || entry == null || state.entry() != entry || modalEntry.get(window) != entry)
            return chain.proceed();
        View source = (View)entryRow.get(entry);
        if (!rowReady(source) || window.getDisplay() == null || window.getDisplay().getDisplayId() != source.getDisplay().getDisplayId())
            return chain.proceed();
        Object items = provider.invoke(source);
        if (!menu.isInstance(items)) return chain.proceed();
        Object currentParent = menuParent.get(items);
        if (currentParent != null && currentParent != source) return chain.proceed();
        View container = (View)menuContainer.get(items);
        if (container != null && container.getParent() != null) return chain.proceed();
        // createMenu reacquires the actual entry and flags, preserving the native item's actions and permissions.
        state.menu = new WeakReference<>(items);
        try {
            try { during(buildMenu, () -> createMenu.invoke(items, source)); }
            catch (ReflectiveOperationException | RuntimeException error) {
                restoreMenu(state);
                NativeLog.warning("Cannot build the full cover notification menu", error);
            }
            Object result = chain.proceed();
            // A configuration change can remove/re-add the card without another long press.
            if (modalLayout != null) fitModalRow(window, state, modalLayout);
            return result;
        } finally {
            View mounted = (View)menuContainer.get(items);
            if (mounted == null || mounted.getParent() != window) restoreMenu(state);
        }
    }

    private void restoreMenu(ModalMenu state) {
        Object items = state.menu();
        state.menu = null;
        if (items == null) return;
        try {
            Object parent = menuParent.get(items);
            View container = (View)menuContainer.get(items);
            if (parent != null && getEntry.invoke(parent) != null && (container == null || container.getParent() == null))
                createMenu.invoke(items, parent);
        } catch (ReflectiveOperationException | RuntimeException error) {
            NativeLog.warning("Cannot rebuild the stock notification menu", error);
        }
    }

    private static boolean enabled() {
        DockConfig config = NativeModule.config();
        return config.enabled && config.coverNotificationLongPress;
    }

    private boolean rowReady(View view) throws ReflectiveOperationException {
        return row.isInstance(view) && view.isAttachedToWindow() && CoverPanelInsets.coverUnlocked(view)
                && !(boolean)pinned.invoke(view) && !(boolean)headsUp.invoke(view) && !(boolean)showingPublic.invoke(view);
    }

    private Object during(Method caller, Call call) throws Throwable {
        Decision before = decision.get();
        decision.set(new Decision(caller));
        try { return call.run(); }
        finally { if (before == null) decision.remove(); else decision.set(before); }
    }

    private interface Call { Object run() throws Throwable; }

    private static final class Decision {
        final Method caller;
        boolean used;
        Decision(Method caller) { this.caller = caller; }
        boolean directCaller() {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName(), method = frame.getMethodName();
                if (owner.equals(CONFIGS) && method.equals("isTinyScreen")) continue;
                // Ignore the module/ART bridge. A nested OEM read (for example while expanding a group) is not ours.
                if (owner.startsWith("com.android.systemui.") || owner.startsWith("com.miui."))
                    return owner.equals(caller.getDeclaringClass().getName()) && method.equals(caller.getName());
            }
            return false;
        }
    }

    private record ModalLayout(Field row, Field showMenu, Field sidePaddings) { }

    private record RowLayout(WeakReference<View> card, FrameLayout.LayoutParams stock, FrameLayout.LayoutParams wide) { }

    private static final class ModalMenu implements View.OnAttachStateChangeListener {
        WeakReference<Object> entry, menu;
        Insets systemBars;
        RowLayout rowLayout;
        Object entry() { return entry == null ? null : entry.get(); }
        Object menu() { return menu == null ? null : menu.get(); }

        void fitRowLayout(View card, int cutoutLeft, int cutoutRight) {
            if (cutoutLeft == 0 && cutoutRight == 0) { restoreRowLayout(); return; }
            if (rowLayout != null && (rowLayout.card.get() != card || card.getLayoutParams() != rowLayout.wide))
                restoreRowLayout();
            if (!(card.getLayoutParams() instanceof FrameLayout.LayoutParams current)) return;
            FrameLayout.LayoutParams stock = rowLayout == null ? current : rowLayout.stock;
            // Across the checked ROMs the cloned row is match_parent, with default FrameLayout alignment.
            if (stock.width != ViewGroup.LayoutParams.MATCH_PARENT || stock.gravity != -1 || stock.isMarginRelative()) return;
            int left = stock.leftMargin - cutoutLeft, right = stock.rightMargin - cutoutRight;
            if (current.leftMargin == left && current.rightMargin == right) return;
            var wide = new FrameLayout.LayoutParams(stock);
            wide.leftMargin = left;
            wide.rightMargin = right;
            if (rowLayout == null) card.addOnAttachStateChangeListener(this);
            rowLayout = new RowLayout(new WeakReference<>(card), stock, wide);
            card.setLayoutParams(wide);
        }

        void restoreRowLayout() {
            if (rowLayout == null) return;
            RowLayout previous = rowLayout;
            rowLayout = null;
            View card = previous.card.get();
            if (card == null) return;
            card.removeOnAttachStateChangeListener(this);
            if (card.getLayoutParams() == previous.wide) card.setLayoutParams(previous.stock);
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            if (rowLayout != null && rowLayout.card.get() == view) restoreRowLayout();
        }
    }

    private record Dispatch(Method method, Class<?> event, Field injector, Field view) {
        static Dispatch resolve(ClassLoader loader, Class<?> row, boolean contextual) throws ReflectiveOperationException {
            if (!contextual) {
                Class<?> item = loader.loadClass("com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin$MenuItem");
                return new Dispatch(row.getDeclaredMethod("doLongClickCallback", item), null, null, null);
            }
            Class<?> binder = loader.loadClass(BINDER + "$$ExternalSyntheticLambda0");
            Class<?> click = loader.loadClass(BINDER + "$LongClickEvent");
            return new Dispatch(binder.getDeclaredMethod("invoke", Object.class), click,
                    CoverLandscapeShade.field(binder, "f$1"), CoverLandscapeShade.field(loader.loadClass(ROW + "ExpandableViewInjector"), "view"));
        }
        View source(XposedInterface.Chain chain) throws ReflectiveOperationException {
            Object arg = chain.getArg(0);
            if (event == null) return arg == null ? null : (View)chain.getThisObject();
            if (!event.isInstance(arg)) return null; // This R8 lambda also handles an unrelated integer event.
            Object owner = injector.get(chain.getThisObject());
            return view.getDeclaringClass().isInstance(owner) ? (View)view.get(owner) : null;
        }
    }
}
