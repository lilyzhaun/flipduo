package com.flipduo.module;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.*;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.Chronometer;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Keeps native event views/actions in SystemUI. Only a confirmed, live FlipHome host may compact their window. */
final class FocusEventBridge {
    private static FocusEventBridge instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable update = this::updateSafely;
    private final NativeModule module;
    private FocusLightStyle lightStyle;
    private Context context;
    private Context userContext;
    private int contextUser = -1, hostUser = -1;
    private Method getCurrentUser, createUserContext;
    private Method registerForAllUsers;
    private UserHandle allUsers;
    private Object manager, controller, collection;
    private Method currentEntry, canShow;
    private Field rootField, paramsField, wmField, sbnField, entriesField;
    private Field focusContentField;
    private Method[] decoViews;
    private View selectedContent;
    private Object selectedFocus;
    private Notification selectedNotification;
    private String selectedKey = "";
    private boolean sourceBound;
    private String confirmedKey = "", clockKey = "", clockSnapshot = "";
    private long countdownDeadline;
    private final View.OnAttachStateChangeListener contentAttachment = new View.OnAttachStateChangeListener() {
        public void onViewAttachedToWindow(View view) { requestUpdate(); }
        public void onViewDetachedFromWindow(View view) {
            // Native scene transitions reparent the same content synchronously. Recheck after that transaction,
            // rather than submitting a visible native window for one frame before it is reattached.
            watchTimer(null); requestUpdate();
        }
    };
    private final IslandSwipe swipe = new IslandSwipe(this::closeCard);
    private final AutoEventExpansion autoExpansion = new AutoEventExpansion();
    private WindowManager.LayoutParams original;
    private View nativeRoot;
    private IBinder host;
    private IBinder.DeathRecipient hostDeath;
    private boolean hostLinked;
    private int displayId = -1;
    private long generation = 1, acknowledged;
    private boolean requestedVisible, expanded, applying, started, broken;
    private String key = "", lastSent = "", lastLog = "";
    private Chronometer chronometer;
    private final TextWatcher timerWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        public void onTextChanged(CharSequence s, int start, int before, int count) { requestUpdate(); }
        public void afterTextChanged(Editable text) { }
    };

    private FocusEventBridge(NativeModule module) { this.module = module; }

    static void install(NativeModule module, ClassLoader loader) throws ReflectiveOperationException {
        FocusEventBridge b = new FocusEventBridge(module);
        Class<?> owner = loader.loadClass("com.android.notification.decor.DecorWindowManagerImpl");
        Class<?> window = loader.loadClass("com.android.notification.decor.DecorWindowController");
        Class<?> entry = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
        Class<?> notifs = loader.loadClass("com.android.systemui.statusbar.notification.collection.NotifCollection");
        b.currentEntry = method(owner, "getCurrentEntry"); b.canShow = method(owner, "decorWindowCanShown");
        b.rootField = field(window, "mWindowRootView"); b.paramsField = field(window, "mLayoutParams");
        b.wmField = field(window, "mWindowManager"); b.sbnField = field(entry, "mSbn");
        b.entriesField = field(notifs, "mReadOnlyNotificationSet");
        b.getCurrentUser = method(Class.forName("android.app.ActivityManager"), "getCurrentUser");
        b.createUserContext = method(Context.class,"createContextAsUser",UserHandle.class,int.class);
        b.registerForAllUsers = method(Context.class,"registerReceiverAsUser",BroadcastReceiver.class,UserHandle.class,
                IntentFilter.class,String.class,Handler.class,int.class);
        b.allUsers = (UserHandle) field(UserHandle.class,"ALL").get(null);
        b.focusContentField = field(loader.loadClass("com.android.systemui.statusbar.notification.ExpandedNotification"), "focusContent");
        Class<?> focus = loader.loadClass("com.android.systemui.plugins.miui.notification.FocusNotificationContent");
        b.decoViews = new Method[]{method(focus,"getDeco"),method(focus,"getDecoDark"),method(focus,"getDecoLand"),method(focus,"getDecoLandDark")};
        for (Constructor<?> c : owner.getDeclaredConstructors()) hook(module,c).intercept(chain -> {
            Object result = chain.proceed(); b.manager = chain.getThisObject(); b.requestUpdate(); return result;
        });
        for (Method m : owner.getDeclaredMethods()) {
            if (!Set.of("updateContentView", "onlyUpdateContentView", "updateView", "updateRemoteView", "emptyCurrentBean", "removeDecorWindow").contains(m.getName())) continue;
            hook(module,m).intercept(chain -> {
                Object result = chain.proceed(); b.manager = chain.getThisObject();
                if ((m.getName().equals("updateView") || m.getName().equals("updateRemoteView")) && result instanceof View content)
                    b.bindContent(content);
                b.requestUpdate(); return result;
            });
        }
        hook(module,method(window, "updateLayoutParams")).intercept(chain -> {
            b.controller = chain.getThisObject();
            if (b.original != null) b.restoreFields();
            Object result = chain.proceed();
            if (!b.applying) { b.applyWindow(); b.requestUpdate(); }
            return result;
        });
        hook(module,method(window, "refreshWindow", int.class, boolean.class, boolean.class)).intercept(chain -> {
            b.controller = chain.getThisObject();
            int operation = (int) chain.getArg(0);
            if (operation == 1) b.requestedVisible = true;
            else if (operation == 2) b.requestedVisible = false;
            b.applyWindow(); // A native addView must already carry the compact alpha, including a new event in a mini slot.
            Object result = chain.proceed(); b.requestUpdate(); return result;
        });
        hook(module,method(window, "showHideWindow", boolean.class, boolean.class)).intercept(chain -> {
            b.controller = chain.getThisObject(); b.requestedVisible = (boolean) chain.getArg(0);
            b.applyWindow();
            Object result = chain.proceed(); b.requestUpdate(); return result;
        });
        hook(module,method(window, "removeWindow")).intercept(chain -> {
            // Do not submit alpha=1 just before removeViewImmediate: it can be shown for one frame.
            // showHideWindow(true) reuses these parameters; its next addView must already receive alpha=0.
            Object result = chain.proceed(); b.requestUpdate(); return result;
        });
        hook(module,method(notifs, "dispatchEvents")).intercept(chain -> {
            Object result = chain.proceed(); b.collection = chain.getThisObject();
            CameraStatusSource.collection(b.collection); b.requestUpdate(); return result;
        });
        hook(module,ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)).intercept(chain -> {
            if (chain.getThisObject() == b.nativeRoot && b.expanded && b.ownsCurrentWindow()) {
                MotionEvent event = (MotionEvent) chain.getArg(0);
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    return true; // Application-side touches never dismiss the expanded card.
                }
                DockConfig config = NativeModule.config();
                if (b.swipe.touch(b.nativeRoot, event, config.animations, config.animationMs)) {
                    if (b.swipe.cancelChild()) {
                        int action = event.getAction(); event.setAction(MotionEvent.ACTION_CANCEL);
                        try { chain.proceed(); } finally { event.setAction(action); }
                    }
                    return true;
                }
            }
            return chain.proceed();
        });
        hook(module,View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
            Object result = chain.proceed();
            // AUTO_ALPHA can reveal the root after its 100ms delayed native show animation.
            if (chain.getThisObject() == b.nativeRoot) b.requestUpdate();
            return result;
        });
        hook(module,View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
            if (chain.getThisObject() == b.nativeRoot && !b.expanded && b.ownsCurrentWindow()) {
                // Folme's AUTO_ALPHA is asynchronous and can run after a layout refresh. Keep the already
                // confirmed compact card invisible at the property setter, not one broadcast/frame later.
                return chain.proceed(new Object[]{0f});
            }
            return chain.proceed();
        });
        // HyperOS 3 has only updateContentView(boolean), which rebuilds the entire native card.
        // Do not substitute that for OS4's per-child palette update or disable the core event bridge.
        if (FocusLightStyle.supportsContentUpdate(owner))
            b.lightStyle = new FocusLightStyle(module, loader, b::lightStyleEnabled);
        else NativeLog.info("Native event palette retained: host has no per-child content update");
        instance = b;
        NativeLog.info("Event island native focus hooks installed; native actions and alerts retained");
    }

    static void start(Context context) {
        if (instance == null || instance.started) return;
        FocusEventBridge b = instance; b.context = context; b.started = true;
        BroadcastReceiver requests = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (EventWire.from(this,c,"com.miui.fliphome")) b.receive(i,getSentFromUid());
            }
        };
        try {
            b.registerForAllUsers.invoke(context,requests,b.allUsers,new IntentFilter(EventWire.REQUEST),
                    StatusWire.PERMISSION,b.main,Context.RECEIVER_EXPORTED);
        } catch (ReflectiveOperationException error) {
            b.started = false; NativeLog.warning("Cannot register the authenticated event host channel",error); return;
        }
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF); screen.addAction(Intent.ACTION_USER_PRESENT);
        screen.addAction("android.intent.action.USER_SWITCHED");
        context.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { b.requestUpdate(); }
        }, screen, Context.RECEIVER_NOT_EXPORTED);
        context.getSystemService(DisplayManager.class).registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { b.requestUpdate(); }
            public void onDisplayRemoved(int id) { b.requestUpdate(); }
            public void onDisplayChanged(int id) { b.requestUpdate(); }
        }, b.main);
        b.requestUpdate();
        try { b.sendState(new Intent(EventWire.STATE).putExtra("resync", true)); }
        catch (ReflectiveOperationException error) { NativeLog.warning("Cannot initialize event island user channel",error); }
    }

    static void refresh() { if (instance != null) instance.requestUpdate(); }
    private void requestUpdate() { main.removeCallbacks(update); main.post(update); }

    private void receive(Intent intent, int senderUid) {
        int user;
        try { user = (int) getCurrentUser.invoke(null); }
        catch (ReflectiveOperationException error) { NativeLog.warning("Cannot authenticate event host user",error); return; }
        if (!UserHandle.getUserHandleForUid(senderUid).equals(userHandle(user))) return;
        IBinder sender = intent.getExtras() == null ? null : intent.getExtras().getBinder("host");
        String action = intent.getStringExtra("action");
        if ("host".equals(action)) {
            if (sender == null) return;
            if (!sender.equals(host)) {
                disconnect(); host = sender; hostUser = user; displayId = intent.getIntExtra("display", -1);
                IBinder watched = sender;
                hostDeath = () -> main.post(() -> { if (watched.equals(host)) { disconnect(); requestUpdate(); } });
                try { host.linkToDeath(hostDeath, 0); hostLinked = true; }
                catch (RemoteException dead) { disconnect(); return; }
                generation++; lastSent = "";
            }
            displayId = intent.getIntExtra("display", -1);
            updateButtonVisibility(intent);
            lastSent = ""; requestUpdate(); return;
        }
        if (host == null || !host.equals(sender)) return;
        if ("release".equals(action)) { disconnect(); requestUpdate(); return; }
        if (intent.getLongExtra("generation", 0) != generation || !allowed()) return;
        updateButtonVisibility(intent);
        if ("ack".equals(action)) {
            StatusBarNotification current;
            try { current = managedNotification(user); }
            catch (ReflectiveOperationException error) { requestUpdate(); return; }
            if (current != null && key.equals(current.getKey()) && !isUrgent(current.getNotification(), hasLiveTimer())) {
                acknowledged = generation; confirmedKey = key;
            }
        } else if ("toggle".equals(action)) {
            if (expanded || confirmedKey.isEmpty() || MiniWindowBridge.occupiesSlot(displayId)) return;
            swipe.reset(); expanded = true;
        } else if (!"buttons".equals(action)) return;
        requestUpdate();
    }

    private void updateButtonVisibility(Intent intent) {
        if (!intent.hasExtra("buttons_hidden")) return; // Old FlipHome hosts retain their native compact policy.
        boolean hidden = intent.getBooleanExtra("buttons_hidden", false);
        if (autoExpansion.update(hidden) && !hidden) { expanded = false; swipe.reset(); }
    }

    private void closeCard() {
        autoExpansion.dismiss(generation);
        expanded = false;
        try { applyWindow(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            confirmedKey = ""; acknowledged = 0; releaseWindow(); NativeLog.warning("Cannot compact the native event card", error);
        }
        requestUpdate();
    }

    private boolean allowed() {
        if (context == null || broken || host == null || !host.isBinderAlive()) return false;
        DockConfig config = NativeModule.config();
        if (!config.enabled || !config.eventIsland || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        Display d = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (d == null || d.getState() != Display.STATE_ON) return false;
        DisplayMetrics m = new DisplayMetrics(); d.getRealMetrics(m);
        return CutoutAppConfig.isCoverDisplay(m.widthPixels, m.heightPixels)
                && (!CoverRotation.landscape(d.getRotation()) || config.landscapeCompat);
    }

    private boolean lightStyleEnabled() {
        if (context == null || broken || !NativeModule.config().enabled || !NativeModule.config().eventIsland
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
            if (display.getState() != Display.STATE_ON) continue;
            DisplayMetrics size = new DisplayMetrics(); display.getRealMetrics(size);
            if (CutoutAppConfig.isCoverDisplay(size.widthPixels, size.heightPixels)
                    && (!CoverRotation.landscape(display.getRotation()) || NativeModule.config().landscapeCompat)) return true;
        }
        return false;
    }

    private StatusBarNotification notification(int user) throws ReflectiveOperationException {
        if (manager == null || !requestedVisible || !(boolean) canShow.invoke(manager)) return null;
        Object entry = currentEntry.invoke(manager);
        StatusBarNotification sbn = entry == null ? null : (StatusBarNotification) sbnField.get(entry);
        return sbn != null && UserHandle.getUserHandleForUid(sbn.getUid()).equals(userHandle(user)) ? sbn : null;
    }

    private void updateSafely() {
        if (!started) return;
        if (broken) { releaseWindow(); return; }
        try { updateState(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            broken = true; watchTimer(null); disconnect(); NativeLog.warning("Event island released native card after an integration error", error);
            Intent state = new Intent(EventWire.STATE).setPackage("com.miui.fliphome").putExtra("available", false);
            try { sendState(state); }
            catch (ReflectiveOperationException userError) { NativeLog.warning("Cannot report event integration state",userError); }
        }
    }

    private void updateState() throws ReflectiveOperationException {
        int user = (int) getCurrentUser.invoke(null);
        if (host != null && hostUser != user) {
            disconnect(); lastSent = "";
            sendState(new Intent(EventWire.STATE).putExtra("resync",true));
        }
        if (lightStyle != null) lightStyle.refresh(manager);
        StatusBarNotification sbn = managedNotification(user);
        // A native scene refresh can remove/reinflate its View for 150ms while the notification and rendered
        // event island are unchanged. Do not treat that View lifecycle as a different event or revoke its ACK.
        View root = controller == null ? null : (View) rootField.get(controller);
        String nextKey = sbn == null ? "" : sbn.getKey();
        if (!nextKey.equals(key)) {
            key = nextKey; generation++; acknowledged = 0; confirmedKey = ""; expanded = false;
            clockKey = ""; clockSnapshot = ""; countdownDeadline = 0;
        }
        sourceBound = sbn != null && root != null && root.isAttachedToWindow() && validContent(sbn,root);
        watchTimer(sourceBound ? findTimer(selectedContent) : null);
        if (chronometer != null) {
            clockKey = key; clockSnapshot = chronometer.getText().toString();
            countdownDeadline = chronometer.isCountDown() ? chronometer.getBase() : 0;
        }
        CameraStatusSource.timer(sbn == null ? null : sbn.getNotification(), countdownDeadline, key);
        boolean available = allowed();
        if (!available) { acknowledged = 0; confirmedKey = ""; expanded = false; }
        boolean urgent = sbn != null && isUrgent(sbn.getNotification(), hasLiveTimer());
        if (urgent) { acknowledged = 0; confirmedKey = ""; expanded = false; }
        if (MiniWindowBridge.occupiesSlot(displayId)) expanded = false;
        else if (available && !urgent && !confirmedKey.isEmpty() && autoExpansion.expand(generation)) expanded = true;
        applyWindow();
        int count = available ? notificationCount(user) : 0;
        int events = manager == null || sbn == null ? 0 : ((List<?>) field(manager.getClass(), "mChildrenViews").get(manager)).size();
        String time = available && clockKey.equals(key) ? clockSnapshot : "";
        // F01: a whole percent from the camera ring's determinate-progress contract; never text or ETA.
        int progress = available && sbn != null && !urgent ? IslandSummary.percent(CameraProgress.read(sbn.getNotification())) : -1;
        if (time.length() > 16) time = "";
        String signature = generation + ":" + available + ":" + (sbn != null) + ":" + expanded + ":" + urgent + ":" + time + ":" + progress + ":" + count + ":" + events
                + ":" + MiniWindowBridge.occupiesSlot(displayId);
        if (signature.equals(lastSent)) return;
        lastSent = signature;
        Intent state = new Intent(EventWire.STATE).setPackage("com.miui.fliphome")
                .putExtra("generation", generation).putExtra("available", available).putExtra("focus", available && sbn != null)
                .putExtra("expanded", expanded && !confirmedKey.isEmpty()).putExtra("urgent", urgent)
                .putExtra("clock", time).putExtra("progress", progress).putExtra("notifications", count).putExtra("events", events);
        if (available && sbn != null) {
            state.putExtra("icon", sbn.getNotification().getSmallIcon());
            try { state.putExtra("app", context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString()); }
            catch (android.content.pm.PackageManager.NameNotFoundException removed) { state.putExtra("app", "进行中的活动"); }
        }
        if ((int) getCurrentUser.invoke(null) != user) { disconnect(); lastSent = ""; requestUpdate(); return; }
        sendState(state,user);
        String log = "Event island state focus=" + (sbn != null) + " expanded=" + expanded + " urgent=" + urgent + " progress=" + progress
                + " available=" + available + " bound=" + sourceBound
                + " events=" + events + " notifications=" + count + " accepted=" + (acknowledged == generation)
                + " miniSlot=" + MiniWindowBridge.occupiesSlot(displayId);
        if (!log.equals(lastLog)) { lastLog = log; NativeLog.info(log); }
    }

    private int notificationCount(int user) throws ReflectiveOperationException {
        if (collection == null || context == null) return 0;
        int count = 0;
        int managerUser = manager == null ? user : (int) field(manager.getClass(), "mCurrentUserId").get(manager);
        if (user != managerUser) return 0;
        for (Object entry : (Collection<?>) entriesField.get(collection)) {
            StatusBarNotification s = (StatusBarNotification) sbnField.get(entry);
            Notification n = s.getNotification();
            if (s.getUserId() != user || s.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if ("com.android.systemui".equals(s.getPackageName()) || "com.miui.fliphome".equals(s.getPackageName())) continue;
            count++;
        }
        return count;
    }

    private StatusBarNotification retainedNotification(int user) throws ReflectiveOperationException {
        if (collection == null) return null;
        for (Object entry : (Collection<?>) entriesField.get(collection)) {
            StatusBarNotification current = (StatusBarNotification) sbnField.get(entry);
            if (current.getUserId() == user && confirmedKey.equals(current.getKey())) return current;
        }
        return null;
    }

    private StatusBarNotification managedNotification(int user) throws ReflectiveOperationException {
        StatusBarNotification current = notification(user);
        return current != null || confirmedKey.isEmpty() ? current : retainedNotification(user);
    }

    static boolean shouldOwnCard(String current, String confirmed, boolean miniSlotted, boolean urgent) {
        return !urgent && !current.isEmpty() && (miniSlotted || current.equals(confirmed));
    }

    static boolean isUrgent(Notification n, boolean hasTimer) {
        return n.fullScreenIntent != null || Notification.CATEGORY_CALL.equals(n.category)
                || (!hasTimer && Notification.CATEGORY_ALARM.equals(n.category));
    }
    private boolean hasLiveTimer() {
        return liveTimer(chronometer) || (!key.isEmpty() && clockKey.equals(key) && countdownDeadline > SystemClock.elapsedRealtime());
    }
    private static boolean liveTimer(Chronometer timer) {
        return timer != null && (!timer.isCountDown() || timer.getBase() > SystemClock.elapsedRealtime());
    }
    private void bindContent(View content) throws ReflectiveOperationException {
        Object entry = currentEntry.invoke(manager);
        if (entry == null) return;
        StatusBarNotification sbn = (StatusBarNotification) sbnField.get(entry);
        Object focus = focusContentField.get(sbn);
        if (selectedContent != content || selectedFocus != focus || !selectedKey.equals(sbn.getKey())) {
            if (selectedContent != null) selectedContent.removeOnAttachStateChangeListener(contentAttachment);
            watchTimer(null); sourceBound = false;
            if (!sbn.getKey().equals(confirmedKey)) {
                confirmedKey = ""; acknowledged = 0; expanded = false; swipe.reset();
                if (!MiniWindowBridge.occupiesSlot(displayId)) releaseWindow();
            }
            content.addOnAttachStateChangeListener(contentAttachment);
        }
        selectedContent = content; selectedFocus = focus; selectedNotification = sbn.getNotification(); selectedKey = sbn.getKey();
    }
    private boolean validContent(StatusBarNotification sbn, View root) throws ReflectiveOperationException {
        if (selectedContent == null || !selectedKey.equals(sbn.getKey())
                || (root.isAttachedToWindow() && !selectedContent.isAttachedToWindow())
                || selectedFocus != focusContentField.get(sbn)) return false;
        if (selectedFocus == null && selectedNotification != sbn.getNotification()) return false;
        if (selectedFocus != null) {
            boolean matches = false;
            for (Method getter : decoViews) if (getter.invoke(selectedFocus) == selectedContent) { matches = true; break; }
            if (!matches) return false;
        }
        View current = selectedContent;
        while (current != root && current.getParent() instanceof View parent) current = parent;
        return current == root;
    }

    private static Chronometer findTimer(View view) {
        if (view instanceof Chronometer timer) return timer;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Chronometer found = findTimer(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private void watchTimer(Chronometer next) {
        if (chronometer == next) return;
        if (chronometer != null) chronometer.removeTextChangedListener(timerWatcher);
        chronometer = next;
        if (chronometer != null) chronometer.addTextChangedListener(timerWatcher);
    }

    private void applyWindow() throws ReflectiveOperationException {
        if (applying || controller == null) return;
        View root = (View) rootField.get(controller);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) paramsField.get(controller);
        if (root == null || p == null) return;
        if (nativeRoot != root) {
            nativeRoot = root;
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { requestUpdate(); }
                public void onViewDetachedFromWindow(View v) { requestUpdate(); }
            });
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> requestUpdate());
        }
        boolean manage = ownsCurrentWindow();
        if (!manage) { releaseWindow(); return; }
        if (original == null) { original = new WindowManager.LayoutParams(); original.copyFrom(p); }
        int oldFlags = p.flags, oldX = p.x; float oldAlpha = p.alpha;
        p.flags = original.flags; p.x = original.x; p.y = original.y; p.alpha = original.alpha;
        boolean showExpanded = expanded && !MiniWindowBridge.occupiesSlot(displayId);
        if (showExpanded) { p.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH; root.setAlpha(1); }
        else { p.alpha = 0; p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; }
        if (!showExpanded) {
            // Keep the content hidden while resetting a completed drag, including when no LP update is needed.
            root.setAlpha(0); swipe.reset();
        }
        if (root.isAttachedToWindow() && (p.flags != oldFlags || p.x != oldX || p.alpha != oldAlpha)) {
            applying = true;
            try { ((WindowManager) wmField.get(controller)).updateViewLayout(root, p); }
            finally { applying = false; }
        }
    }

    private boolean ownsCurrentWindow() throws ReflectiveOperationException {
        if (!started || !allowed() || nativeRoot == null) return false;
        int user = (int) getCurrentUser.invoke(null);
        StatusBarNotification current = managedNotification(user);
        return hostUser == user && current != null && shouldOwnCard(current.getKey(), confirmedKey,
                MiniWindowBridge.occupiesSlot(displayId), isUrgent(current.getNotification(), current.getKey().equals(key) && hasLiveTimer()));
    }

    private void restoreFields() throws ReflectiveOperationException {
        if (original == null || controller == null) return;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) paramsField.get(controller);
        p.x = original.x; p.y = original.y; p.flags = original.flags; p.alpha = original.alpha;
    }
    private void releaseWindow() {
        swipe.reset();
        if (nativeRoot != null) nativeRoot.setAlpha(1);
        if (original == null || controller == null) return;
        try {
            restoreFields();
            View root = (View) rootField.get(controller);
            if (root != null && root.isAttachedToWindow()) ((WindowManager) wmField.get(controller)).updateViewLayout(root, (WindowManager.LayoutParams) paramsField.get(controller));
            original = null; // Clear only after successful submission, or after confirming the window is detached.
        } catch (ReflectiveOperationException | RuntimeException error) { NativeLog.warning("Cannot restore native focus window", error); }
    }
    private void disconnect() {
        autoExpansion.reset();
        acknowledged = 0; confirmedKey = ""; clockKey = ""; clockSnapshot = ""; countdownDeadline = 0; expanded = false; releaseWindow();
        if (host != null && hostDeath != null && hostLinked && host.isBinderAlive()) host.unlinkToDeath(hostDeath, 0);
        host = null; hostDeath = null; hostLinked = false; displayId = -1; hostUser = -1;
    }
    private static UserHandle userHandle(int user) { return UserHandle.getUserHandleForUid(user * 100000); }
    private void sendState(Intent state) throws ReflectiveOperationException {
        sendState(state,(int) getCurrentUser.invoke(null));
    }
    private void sendState(Intent state, int user) throws ReflectiveOperationException {
        if (userContext == null || contextUser != user) {
            userContext = (Context) createUserContext.invoke(context,userHandle(user),0); contextUser = user;
        }
        state.setPackage("com.miui.fliphome").putExtra("user",userHandle(user));
        EventWire.send(userContext,state);
    }
    private static Method method(Class<?> c, String name, Class<?>... types) throws ReflectiveOperationException {
        Method m = c.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    private static XposedInterface.HookBuilder hook(NativeModule module, Executable method) {
        return module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
    }
    private static Field field(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
