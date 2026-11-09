package com.flipduo.module;

import android.content.*;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.app.ActivityOptions;
import android.graphics.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import android.widget.Toast;

/** A normal ViewGroup child of WatchOverlayGroupView, not a WindowManager overlay. */
final class NativeDock extends ViewGroup {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GlassButton[] buttons = new GlassButton[6];
    private final GlassButton[] tools = new GlassButton[2];
    private final MaterialPanel[] panels = new MaterialPanel[4];
    private final MaterialPanel toolPanel;
    private final MaterialPanel islandPanel;
    private final EventIslandView island;
    private EventIslandState eventState = new EventIslandState();
    private final Binder eventHost = new Binder();
    private boolean eventHosting;
    private int eventDisplay = -1;
    private boolean widgetsExpanded, miniSlotted;
    private final ButtonVisibility buttonVisibility = new ButtonVisibility();
    private float buttonPresence = 1;
    private ValueAnimator buttonAnimator;
    private Runnable openWidgets, closeWidgets;
    private java.util.function.BooleanSupplier miniWindow;
    private long eventAcknowledged, eventFramePending;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!EventWire.from(this, context, "com.android.systemui")
                    || !android.os.Process.myUserHandle().equals(intent.getParcelableExtra("user", android.os.UserHandle.class))) return;
            if (intent.getBooleanExtra("resync", false) && eventHosting) {
                eventAcknowledged = 0; eventFramePending = 0; eventRequest("host"); return;
            }
            applyEventState(EventIslandState.decode(intent));
        }
    };
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private final SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.CHINA);
    // Minute clock and degraded status reads are separate one-shot drivers; neither runs while hidden or screen-off.
    private final MinuteClock minuteClock = new MinuteClock(new MinuteClock.Host() {
        @Override public long now() { return System.currentTimeMillis(); }
        @Override public void post(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
        @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
        @Override public void tick() { header.updateDescription(); if (config.showClock) header.invalidate(); }
    });
    private final StatusFallback statusFallback = new StatusFallback(new StatusFallback.Host() {
        @Override public void post(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
        @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
        @Override public void read() { readFallbackStatus(); }
    });
    private boolean screenOn = true, configApplied, layoutOverridden;
    private final BroadcastReceiver timeChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                // The formatter keeps the zone it was created with; take the new zone from the broadcast itself.
                String id = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                applyTimeZone(id == null ? TimeZone.getDefault() : TimeZone.getTimeZone(id));
            }
            if (minuteClock.running()) minuteClock.timeChanged(); else header.updateDescription();
        }
    };
    private final AccessibilityManager.AccessibilityStateChangeListener accessibilityChanges = on -> updateTimers();
    private final DockIcons icons = new DockIcons();
    private final Header header;
    private TrioState state = new TrioState();
    boolean enabled = true;
    private boolean menu, attached, bottomControls;
    private boolean landscape;
    private int appliedRotation;
    private DockAppearance appearance;
    private CameraStatusView cameraStatus;
    private boolean receivedSystemState;
    private DockConfig config = new DockConfig();
    private DockLayout layout = new DockLayout();
    private final boolean previewOnly;
    private DockSpec.Action[] order = {DockSpec.Action.BACK, DockSpec.Action.HOME, DockSpec.Action.RECENTS, DockSpec.Action.NOTIFICATIONS, DockSpec.Action.CONTROL_CENTER, DockSpec.Action.MORE};
    private float menuProgress;
    private ValueAnimator menuAnimator;
    private static final AtomicLong TOOL_IDS = new AtomicLong();
    private long pendingTool;
    private final Runnable toolTimeout = () -> toolCompleted(false);
    private final BroadcastReceiver toolResults = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (pendingTool != 0 && pendingTool == intent.getLongExtra("id", 0)) toolCompleted(intent.getBooleanExtra("success", false));
        }
    };
    private final BroadcastReceiver systemStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean tintOnly = StatusWire.TINT.equals(intent.getAction());
            if (tintOnly) {
                if (!StatusWire.applyTint(state, intent)) return;
            } else {
                state = StatusWire.decode(intent, state);
            }
            // During the handover the clock IPC may still describe the outgoing app, not the recents wallpaper.
            if (awaitDesktopTint && rawStatusTintKnown()
                    && (Color.luminance(state.statusTint | 0xff000000) > .5f) == nativeDark) awaitDesktopTint = false;
            rebuildAppearance();
            if (!tintOnly) {
                if (!receivedSystemState) NativeLog.info("SystemUI status received; centre=" + state.centre() + " signal=" + state.mobileLevel);
                receivedSystemState = true; header.updateDescription(); updateTimers();
                RuntimeReport.state(getContext().getApplicationContext(), state);
            }
            header.invalidate();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int max = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            state.battery = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, max));
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            state.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            header.updateDescription(); header.invalidate();
        }
    };

    NativeDock(Context context) {
        this(context, false);
    }
    NativeDock(Context context, boolean preview) {
        super(context);
        previewOnly = preview;
        setWillNotDraw(false); setClipChildren(false);
        appearance = new DockAppearance();
        for (int i = 0; i < panels.length; i++) { panels[i] = new MaterialPanel(context); addView(panels[i]); }
        toolPanel = new MaterialPanel(context); addView(toolPanel); toolPanel.setVisibility(INVISIBLE);
        islandPanel = new MaterialPanel(context); addView(islandPanel);
        island = new EventIslandView(context, preview); addView(island);
        island.actions(() -> {
            if (config.haptics) island.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (buttonsHidden()) return;
            if (eventState.available && eventState.focus && !eventState.urgent) eventRequest("toggle");
            else openNotifications();
        }, this::openNotifications, () -> {
            if (!canOpenWidgets() || openWidgets == null) return;
            if (config.haptics) island.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openWidgets.run();
        });
        header = new Header(context); addView(header);
        header.setClickable(!preview); header.setFocusable(!preview);
        if (!preview) header.setOnClickListener(v -> openSettings());
        if (!preview) header.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!enabled || slotOwned()) return false;
                buttonVisibility.toggle(); buttonsChanged(); header.updateDescription();
                if (config.haptics) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
            @Override public boolean onLongClickUseDefaultHapticFeedback(View v) { return false; }
        });
        for (int i = 0; i < buttons.length; i++) {
            final int index = i;
            GlassButton button = new GlassButton(context, i);
            if (!preview) button.setOnClickListener(v -> {
                if (previewOnly || buttonsHidden()) return;
                if (config.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                perform(index);
            });
            if (!preview) button.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    return performLongPress(index);
                }
                @Override public boolean onLongClickUseDefaultHapticFeedback(View v) { return false; }
            });
            buttons[i] = button; addView(button);
        }
        for (int i = 0; i < tools.length; i++) {
            final int index = i;
            tools[i] = new GlassButton(context, -1, i == 0 ? DockSpec.Action.LOCK : DockSpec.Action.SETTINGS);
            tools[i].setVisibility(INVISIBLE); addView(tools[i]);
            if (!preview) tools[i].setOnClickListener(v -> {
                if (buttonsHidden()) return;
                if (config.eventIsland && (!islandLockVisible() || pendingTool != 0)) return;
                if (config.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (index == 0) requestTool("lock", tools[0]);
                else { openSettings(); setTools(false); }
            });
        }
        updateMenu();
    }

    void widgetActions(Runnable open, Runnable close) { openWidgets = open; closeWidgets = close; }
    void miniWindowAction(java.util.function.BooleanSupplier request) { miniWindow = request; }
    void applyEventState(EventIslandState value) {
        eventState = value;
        // Host availability can disappear briefly when recents hides/re-attaches the native window.
        // That is not a user dismissal; actual display/lock/user exits are handled by NativeModule.
        if (slot().closeWidgets) dismissWidgets();
        if (!value.available || value.urgent) { eventAcknowledged = 0; eventFramePending = 0; }
        island.update(value);
        updateMenu(); applyMenuMotion(); requestLayout(); confirmEventFrame();
    }
    void widgetsAvailable(boolean available) { island.widgetsAvailable(available); }
    boolean canOpenWidgets() { return slot().canOpenWidgets; }
    /** Current facts -> SlotOwnership. All slot-related draw/touch/material/accessibility decisions come from here. */
    private SlotOwnership slot() {
        SlotOwnership.Facts f = new SlotOwnership.Facts();
        f.preview = previewOnly; f.enabled = enabled; f.eventIsland = config.eventIsland; f.islandLock = config.islandLock;
        f.landscape = landscape; f.widgets = widgetsExpanded; f.mini = miniSlotted;
        f.focus = eventState.focus; f.expanded = eventState.expanded; f.urgent = eventState.urgent;
        f.buttonsHidden = buttonsHidden(); f.toolPending = pendingTool != 0;
        return SlotOwnership.decide(f);
    }
    void widgetsExpanded(boolean value) { widgetsExpanded = value; applySlotOwner(); }
    /** A native mini window occupies the widget slot below this window; hide and release it exactly like the pager. */
    void miniSlotted(boolean value) {
        miniSlotted = value; applySlotOwner();
        if (!attached && !value) suspendEventHost();
        updateEventHost(); confirmEventFrame();
    }
    private boolean slotOwned() { return !slot().dockShown; }
    private Runnable backgroundChanged;
    private boolean backgroundSuppressed;
    void backgroundChanges(Runnable changed) { backgroundChanged = changed; }
    boolean nativeContentVisible() { return slot().nativeContent; }
    // A stock widget replaces our buttons, not the independent cover material/app seam.
    boolean coverFillOccluded() { return slot().coverFillOccluded; }
    private void applySlotOwner() {
        boolean owned = !slot().dockShown; setAlpha(owned ? 0 : 1);
        setImportantForAccessibility(owned ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        applyMenuMotion(); invalidate();
    }
    /** Long-pressed recents. The widget panel and a mini window share one slot, so the panel yields first. */
    boolean requestMiniWindow() {
        if (miniWindow == null || !slot().canRequestMini) return false;
        if (widgetsExpanded) dismissWidgets();
        return miniWindow.getAsBoolean();
    }
    private boolean performLongPress(int index) {
        buttons[index].clearHoldProgress(); // Real platform callback, not the visual clock, ends the hold.
        NavigationLongPress action = config.longPress(action(index, menu));
        if (previewOnly || action == NavigationLongPress.NONE) return false;
        // A configured hold is consumed even if its destination cannot run; never fall through to short navigation.
        if (!slot().longPressAllowed || !buttons[index].isEnabled()) return true;
        boolean executed = switch (action) {
            case MINI_WINDOW -> requestMiniWindow();
            case SETTINGS -> openSettings();
            case LAUNCH_APP, CAMERA, ASSISTANT, SYSTEM_SETTINGS -> openLongPressApp(action, config.longPressApp(action(index, menu)));
            default -> requestTool(action.tool, buttons[index]);
        };
        if (executed && config.haptics) buttons[index].performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }
    private void dismissWidgets() { if (closeWidgets != null) closeWidgets.run(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return !slotOwned() && super.dispatchTouchEvent(event); }

    private void openNotifications() {
        if (buttonsHidden()) return;
        if (config.haptics) island.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (eventState.expanded) return;
        requestTool("notifications", buttons[0]);
    }
    private void eventRequest(String action) {
        if (previewOnly) return;
        if (getDisplay() != null) eventDisplay = getDisplay().getDisplayId();
        if (eventDisplay < 0) return;
        EventWire.send(getContext(), EventWire.request(action, eventHost, eventDisplay, eventState.generation)
                .putExtra("buttons_hidden", buttonsHidden()));
    }
    private void updateEventHost() {
        if (previewOnly || !attached) return;
        boolean active = eventHostEligible() && getDisplay() != null;
        if (active == eventHosting) return;
        eventHosting = active; eventAcknowledged = 0; eventFramePending = 0; eventRequest(active ? "host" : "release");
    }
    boolean eventHostEligible() { return config.eventIsland && enabled && (miniSlotted || isShown()); }
    void suspendEventHost() {
        // The same native window may reattach during recents while the task still occupies our slot.
        if (eventHosting && !miniSlotted) { eventRequest("release"); eventHosting = false; }
        eventFramePending = 0;
    }
    private void confirmEventFrame() {
        long generation = eventState.generation;
        View frame = getRootView();
        if (!eventState.available || eventState.urgent || !eventHosting || !eventHostEligible() || !frame.isHardwareAccelerated()
                || eventAcknowledged == generation || eventFramePending == generation) return;
        eventFramePending = generation;
        // Do not suppress the system card merely because an IPC update arrived: wait for the island's frame to commit.
        frame.getViewTreeObserver().registerFrameCommitCallback(() -> handler.post(() -> commitEventFrame(generation)));
        frame.invalidate(); // The alpha-zero dock itself may not submit a frame while the mini window owns the slot.
    }
    void commitEventFrame(long generation) {
        if (eventFramePending == generation) eventFramePending = 0;
        if (eventHosting && eventHostEligible() && eventState.available && !eventState.urgent && eventState.generation == generation) {
            eventAcknowledged = generation; eventRequest("ack");
        }
    }

    void applyConfig(DockConfig value) {
        // Scene refreshes re-send the same config many times; only changed fields re-parse, re-layout or relabel.
        int delta = DockConfigDelta.between(configApplied ? config : null, value);
        configApplied = true;
        boolean hidden = buttonsHidden();
        config = value;
        buttonVisibility.configure(value.enabled, value.hideFullscreenButtons);
        boolean hiddenChanged = hidden != buttonsHidden();
        if (hiddenChanged) buttonsChanged();
        else if (!value.animations && buttonAnimator != null) settleButtonPresence();
        header.trio.configure(value);
        island.hideNotificationCount(value.visualFocus && value.visualFocusHideCount);
        if (widgetsExpanded && !canOpenWidgets()) dismissWidgets();
        if ((delta & DockConfigDelta.ACTIONS) != 0) {
            String[] names = value.order.split(",");
            for (int i = 0; i < 6; i++) order[i] = DockSpec.Action.valueOf(names[i]);
        }
        if (config.eventIsland) {
            if ((delta & DockConfigDelta.ACTIONS) != 0) order = new DockSpec.Action[]{DockSpec.Action.BACK, DockSpec.Action.HOME,
                    DockSpec.Action.RECENTS, DockSpec.Action.NOTIFICATIONS, DockSpec.Action.CONTROL_CENTER, DockSpec.Action.MORE};
            menu = false; menuProgress = 0; if (menuAnimator != null) menuAnimator.cancel();
        }
        boolean relayout = (delta & DockConfigDelta.LAYOUT) != 0 || layoutOverridden;
        if (relayout) {
            // A preview drag may have replaced layout without saving; re-applying the config restores the saved one.
            layout = DockLayout.decode(value.positions); layoutOverridden = false;
            appearance.layout(layout);
        }
        rebuildAppearance();
        applyAppearance(bottomControls);
        if (relayout) requestLayout();
        else applyMenuMotion(); // keep re-applying scene-dependent visibility/alpha without a measure/layout pass
        if ((delta & DockConfigDelta.ACTIONS) != 0 || hiddenChanged) updateMenu();
        if ((delta & DockConfigDelta.REDRAW) != 0) { header.invalidate(); for (View button : buttons) button.invalidate(); }
        updateEventHost();
        updateTimers();
        if (!value.animations && menuAnimator != null) { menuAnimator.cancel(); menuProgress = menu ? 1 : 0; applyMenuMotion(); }
    }

    private boolean immersive, nativeDark = true, nativeBlur;
    private boolean clockTone = true;
    private boolean nativeToneHeld;
    private boolean nativeDesktop, awaitDesktopTint;
    private int heldHeaderInk, heldHeaderSecondary;
    private ValueAnimator toneAnimator;
    private boolean toneTargetKnown;
    private float targetImmersion, targetDarkness;
    private int targetInk, targetSecondary;
    void holdNativeTone(boolean hold) {
        if (nativeToneHeld == hold) return;
        if (hold) {
            heldHeaderInk = headerInk(); heldHeaderSecondary = headerSecondary(heldHeaderInk);
            if (toneAnimator != null) { toneAnimator.cancel(); toneAnimator = null; }
            toneTargetKnown = false;
        }
        nativeToneHeld = hold;
        if (!hold) {
            awaitDesktopTint = nativeDesktop;
            rebuildAppearance(); applyAppearance(bottomControls);
        }
    }
    void applyNativeTone(boolean fullScreen, boolean dark, boolean blur) {
        applyNativeTone(fullScreen, dark, blur, false);
    }
    void applyNativeTone(boolean fullScreen, boolean dark, boolean blur, boolean desktop) {
        applyNativeTone(fullScreen, dark, blur, desktop, true);
    }
    void applyNativeTone(boolean fullScreen, boolean dark, boolean blur, boolean desktop, boolean clockTone) {
        this.clockTone = clockTone;
        nativeDesktop = desktop;
        if (!desktop) awaitDesktopTint = false;
        immersive = fullScreen; nativeDark = dark; nativeBlur = blur;
        rebuildAppearance();
        applyAppearance(bottomControls);
    }

    private boolean hasStatusTint() {
        return clockTone && rawStatusTintKnown() && !(nativeDesktop && awaitDesktopTint);
    }
    private boolean rawStatusTintKnown() {
        return state.statusTintKnown && getDisplay() != null && state.statusTintDisplay == getDisplay().getDisplayId();
    }
    private boolean materialDark() {
        return immersive && hasStatusTint() ? Color.luminance(state.statusTint | 0xff000000) > .5f : nativeDark;
    }
    private void rebuildAppearance() {
        if (nativeToneHeld) return;
        boolean clock = immersive && hasStatusTint();
        float immersion = immersive ? 1 : 0;
        float darkness = clock ? (Color.red(state.statusTint) + Color.green(state.statusTint) + Color.blue(state.statusTint)) / 765f : nativeDark ? 1 : 0;
        int ink = clock ? state.statusTint : nativeDark ? 0xfff3f4f6 : 0xff20252c;
        int secondary = clock ? Color.argb(Math.round(Color.alpha(ink) * .84f), Color.red(ink), Color.green(ink), Color.blue(ink))
                : nativeDark ? 0xffd7dde5 : 0xff454e58;
        boolean animate = !clock && attached && isShown() && !previewOnly && config.animations && ValueAnimator.areAnimatorsEnabled();
        targetTone(immersion, darkness, ink, secondary, animate);
    }
    private void targetTone(float immersion, float darkness, int ink, int secondary, boolean animate) {
        if (toneTargetKnown && targetImmersion == immersion && targetDarkness == darkness && targetInk == ink && targetSecondary == secondary) {
            if (!animate && toneAnimator != null) {
                toneAnimator.cancel(); toneAnimator = null; appearance.tone(immersion, darkness, ink, secondary); invalidateTone();
            }
            return;
        }
        if (toneAnimator != null) { toneAnimator.cancel(); toneAnimator = null; }
        toneTargetKnown = true; targetImmersion = immersion; targetDarkness = darkness; targetInk = ink; targetSecondary = secondary;
        if (!animate) {
            // SystemUI already supplies intermediate colors. Follow them, never start a second delayed animation.
            appearance.tone(immersion, darkness, ink, secondary); invalidateTone(); return;
        }
        float fromImmersion = appearance.immersion, fromDarkness = appearance.darkness;
        int fromInk = appearance.ink, fromSecondary = appearance.secondary;
        toneAnimator = ValueAnimator.ofFloat(0, 1);
        toneAnimator.setDuration(config.animationMs);
        toneAnimator.setInterpolator(new PathInterpolator(.32f, .72f, 0, 1));
        toneAnimator.addUpdateListener(animation -> {
            float p = (float) animation.getAnimatedValue();
            appearance.tone(fromImmersion + (immersion - fromImmersion) * p, fromDarkness + (darkness - fromDarkness) * p,
                    DockAppearance.blend(fromInk, ink, p), DockAppearance.blend(fromSecondary, secondary, p));
            invalidateTone();
        });
        toneAnimator.start();
    }
    private void invalidateTone() {
        for (MaterialPanel panel : panels) panel.invalidate();
        toolPanel.invalidate(); islandPanel.invalidate(); island.tone(appearance);
        if (cameraStatus != null) cameraStatus.foreground(appearance.ink);
        for (GlassButton button : buttons) button.invalidate();
        for (GlassButton button : tools) button.invalidate();
        header.invalidate();
    }
    private int headerInk() {
        return nativeToneHeld ? heldHeaderInk : appearance.ink;
    }
    private int headerSecondary(int ink) {
        if (nativeToneHeld) return heldHeaderSecondary;
        return appearance.secondary;
    }

    private DockSpec.Action action(int slot, boolean tools) {
        DockSpec.Action normal = order[slot];
        if (!tools) return normal;
        if (normal == DockSpec.Action.MORE) return DockSpec.Action.CLOSE;
        return normal;
    }

    private void setTools(boolean open) {
        if (!previewOnly) NativeLog.info("Tools transition open=" + open + " from=" + menuProgress + " anchor=" + moreSlot());
        menu = open; updateMenu();
        if (menuAnimator != null) menuAnimator.cancel();
        float target = open ? 1 : 0;
        if (!config.animations || !ValueAnimator.areAnimatorsEnabled() || !attached) {
            menuProgress = target; applyMenuMotion(); return;
        }
        menuAnimator = ValueAnimator.ofFloat(menuProgress, target);
        menuAnimator.setDuration(Math.max(80, Math.round(config.animationMs * Math.abs(target - menuProgress))));
        menuAnimator.setInterpolator(new PathInterpolator(.32f, .72f, 0, 1));
        menuAnimator.addUpdateListener(a -> { menuProgress = (Float)a.getAnimatedValue(); applyMenuMotion(); });
        menuAnimator.start();
    }

    private int moreSlot() { for (int i = 0; i < 6; i++) if (order[i] == DockSpec.Action.MORE) return i; return 5; }
    void fullscreenScene(boolean value) {
        boolean hidden = buttonsHidden(); buttonVisibility.scene(value);
        if (hidden != buttonsHidden()) buttonsChanged();
    }
    private boolean buttonsHidden() { return !previewOnly && buttonVisibility.hidden(); }
    private void buttonsChanged() {
        cancelButtonPresentation();
        if (buttonsHidden() && widgetsExpanded) dismissWidgets();
        island.controlsHidden(buttonsHidden());
        if (eventHosting) eventRequest("buttons");
        header.updateDescription(); updateMenu();
        float target = buttonsHidden() ? 0 : 1;
        ValueAnimator previous = buttonAnimator; buttonAnimator = null;
        if (previous != null) previous.cancel(); // Keep the sampled presence when reversing midway.
        if (!attached || !isShown() || !config.animations || !ValueAnimator.areAnimatorsEnabled()) {
            buttonPresence = target; applyMenuMotion(); return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(buttonPresence, target);
        buttonAnimator = animation;
        animation.setDuration(Math.max(80, Math.round(Math.min(250, config.animationMs) * Math.abs(target - buttonPresence))));
        animation.setInterpolator(new PathInterpolator(.32f, .72f, 0, 1));
        animation.addUpdateListener(a -> { buttonPresence = (float) a.getAnimatedValue(); applyMenuMotion(); });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (buttonAnimator == a) { buttonAnimator = null; buttonPresence = target; applyMenuMotion(); }
            }
        });
        applyMenuMotion(); animation.start();
    }
    private void settleButtonPresence() {
        ValueAnimator previous = buttonAnimator; buttonAnimator = null;
        if (previous != null) previous.cancel();
        buttonPresence = buttonsHidden() ? 0 : 1; applyMenuMotion();
    }
    private void cancelButtonPresentation() {
        if (menuAnimator != null) menuAnimator.cancel();
        menu = false; menuProgress = 0;
        for (GlassButton button : buttons) button.clearHoldProgress();
    }
    private void applyButtonPresence() {
        // Base menu/slot presentation is assigned afresh before this multiplier, never accumulated.
        for (GlassButton button : buttons) presentButton(button);
        for (GlassButton button : tools) presentButton(button);
        for (MaterialPanel panel : panels) presentButton(panel);
        presentButton(toolPanel); presentButton(island); presentButton(islandPanel);
        island.controlsHidden(buttonsHidden());
    }
    private void presentButton(View view) {
        view.setAlpha(view.getAlpha() * buttonPresence);
        if (buttonPresence == 0 && view.getVisibility() == VISIBLE) view.setVisibility(INVISIBLE);
        if (buttonsHidden() && !(view instanceof MaterialPanel)) view.setEnabled(false);
    }
    private int toolsPair() { return DockLayout.unit(moreSlot()) == 3 ? 1 : 3; }
    private boolean islandLockVisible() { return slot().lock.draw; }
    private void applyMenuMotion() {
        SlotOwnership slot = slot();
        boolean nativeContent = slot.nativeContent;
        if (backgroundSuppressed != nativeContent) {
            backgroundSuppressed = nativeContent;
            if (backgroundChanged != null) backgroundChanged.run();
        }
        for (MaterialPanel panel : panels) panel.suppress(!slot.navigation.material || buttonPresence == 0);
        // The existing tool material is a single circular lock key in the idle island's vacant cell.
        // Remove its independent compositor material too when an activity fills that cell.
        toolPanel.suppress(buttonPresence == 0 || (config.eventIsland ? !slot.lock.material : nativeContent));
        islandPanel.suppress(!slot.island.material || buttonPresence == 0);
        header.setAlpha(slot.headerShown ? 1 : 0);
        islandPanel.setAlpha(slot.island.draw ? 1 : 0); island.setAlpha(slot.island.draw ? 1 : 0);
        if (!previewOnly) island.slotPermissions(slot.island.touch, slot.island.accessible);
        if (getWidth() == 0) return;
        if (config.eventIsland) {
            float alpha = slot.navigation.draw ? 1 : 0;
            for (int i = 0; i < panels.length; i++) { panels[i].setVisibility(i < 2 ? VISIBLE : GONE); panels[i].setAlpha(alpha); }
            for (int i = 0; i < buttons.length; i++) {
                buttons[i].setVisibility(i < 3 ? VISIBLE : GONE); buttons[i].setAlpha(alpha); buttons[i].setTranslationY(0);
                buttons[i].setEnabled(slot.navigation.touch);
                // Transparent, disabled keys under a native card must not stay in the accessibility tree.
                if (!previewOnly) buttons[i].setImportantForAccessibility(slot.navigation.accessible
                        ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            boolean lock = slot.lock.draw;
            tools[0].setVisibility(lock ? VISIBLE : GONE); tools[0].setAlpha(lock ? 1 : 0);
            tools[0].setEnabled(slot.lock.touch);
            tools[0].setTranslationX(0); tools[0].setTranslationY(0);
            tools[0].setScaleX(1); tools[0].setScaleY(1);
            tools[1].setVisibility(GONE); tools[1].setEnabled(false);
            toolPanel.setVisibility(lock ? VISIBLE : GONE); toolPanel.setAlpha(lock ? 1 : 0);
            toolPanel.setScaleX(config.scale / 100f); toolPanel.setScaleY(config.scale / 100f);
            toolPanel.setTranslationX(0); toolPanel.setTranslationY(0);
            islandPanel.setVisibility(VISIBLE); island.setVisibility(VISIBLE);
            applyButtonPresence();
            return;
        }
        islandPanel.setVisibility(GONE); island.setVisibility(GONE); header.setAlpha(1);
        for (MaterialPanel panel : panels) { panel.setVisibility(VISIBLE); panel.setAlpha(1); }
        for (GlassButton button : buttons) {
            button.setVisibility(VISIBLE); button.setAlpha(1); button.setTranslationY(0);
            button.setEnabled(!previewOnly && pendingTool == 0);
            if (!previewOnly) button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        int anchor = moreSlot(), pair = toolsPair(), panelIndex = pair == 1 ? 1 : 2;
        float p = menuProgress, scale = config.scale / 100f;
        float cx = buttons[anchor].getLeft() + buttons[anchor].getWidth() / 2f;
        float cy = buttons[anchor].getTop() + buttons[anchor].getHeight() / 2f;
        float outgoing = Math.max(0, 1 - p / .7f);
        panels[panelIndex].setAlpha(outgoing);
        panels[panelIndex].setScaleX(scale * (1 - .04f * p)); panels[panelIndex].setScaleY(scale * (1 - .04f * p));
        for (int i = pair; i < pair + 2; i++) {
            buttons[i].setAlpha(outgoing); buttons[i].setTranslationY(-8 * p);
            buttons[i].setVisibility(p == 1 ? INVISIBLE : VISIBLE);
            buttons[i].setEnabled(!previewOnly && !menu && p == 0 && pendingTool == 0);
        }
        toolPanel.setVisibility(p > 0 ? VISIBLE : INVISIBLE); toolPanel.setAlpha(p);
        toolPanel.setScaleX(scale * (.94f + .06f * p)); toolPanel.setScaleY(scale * (.5f + .5f * p));
        toolPanel.setTranslationX((cx - toolPanel.getLeft() - toolPanel.getWidth()/2f) * (1-p));
        toolPanel.setTranslationY((cy - toolPanel.getTop() - toolPanel.getHeight()/2f) * (1-p));
        for (int i = 0; i < tools.length; i++) {
            GlassButton button = tools[i];
            button.setVisibility(p > 0 ? VISIBLE : INVISIBLE);
            button.setEnabled(!previewOnly && menu && p > .9f && pendingTool == 0);
            button.setAlpha(Math.max(0, Math.min(1, (p - .1f * (1-i)) / .9f)));
            button.setTranslationX((cx - button.getLeft() - button.getWidth()/2f) * (1-p));
            button.setTranslationY((cy - button.getTop() - button.getHeight()/2f) * (1-p));
            button.setScaleX(.94f + .06f * p); button.setScaleY(.94f + .06f * p);
        }
        buttons[anchor].invalidate();
        applyButtonPresence();
    }

    void applyAppearance(boolean bottom) {
        if (!enabled) return;
        if (bottomControls != bottom) { bottomControls = bottom; requestLayout(); }
        // Window backgrounds belong to NativeModule's scene policy; config/reattach must not repaint a native full-screen page.
        for (MaterialPanel panel : panels) panel.appearance(appearance, !previewOnly && nativeBlur);
        toolPanel.appearance(appearance, !previewOnly && nativeBlur);
        islandPanel.appearance(appearance, !previewOnly && nativeBlur && config.eventIsland);
        island.tone(appearance);
        if (cameraStatus != null) cameraStatus.foreground(appearance.ink);
        for (GlassButton button : buttons) button.invalidate();
        for (GlassButton button : tools) button.invalidate();
        header.invalidate(); invalidate();
    }
    void applyRotation(int rotation) {
        boolean next = CoverRotation.landscape(rotation);
        if (appliedRotation != rotation) {
            MotionEvent cancel = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.dispatchTouchEvent(cancel); cancel.recycle();
            if (menuAnimator != null) menuAnimator.cancel();
            menu = false; menuProgress = 0;
            appliedRotation = rotation; landscape = next; island.horizontal(next); requestLayout(); updateMenu();
            if (next && widgetsExpanded) dismissWidgets();
        }
        applyAppearance(rotation == 2 || rotation == 3);
    }
    void cameraStatus(CameraStatusView view) {
        cameraStatus = view;
        if (view != null) view.foreground(appearance.ink);
    }

    void previewState(TrioState value) { state = value; rebuildAppearance(); header.updateDescription(); header.invalidate(); invalidate(); }
    void previewLayout(DockLayout value) {
        if (!previewOnly) return;
        layout = value; layoutOverridden = true; appearance.layout(layout); requestLayout(); invalidate();
    }
    void refreshPreviewClock() { header.updateDescription(); header.invalidate(); }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); applyAppearance(bottomControls);
    }

    private void perform(int slot) {
        if (previewOnly || buttonsHidden()) return;
        DockSpec.Action action = action(slot, menu);
        if (slot().activityExpanded) return;
        switch (action) {
            case MORE: setTools(true); return;
            case CLOSE: setTools(false); return;
            case SETTINGS:
                openSettings();
                setTools(false); return;
            case NOTIFICATIONS: requestTool("notifications", buttons[slot]); return;
            case CONTROL_CENTER: requestTool("quick_settings", buttons[slot]); return;
            case LOCK: requestTool("lock", buttons[slot]); return;
            case BACK:
                if (menu) { setTools(false); return; }
                break;
            default: if (menu) setTools(false);
        }
        Display display = getDisplay();
        if (display == null) return;
        if (action == DockSpec.Action.RECENTS) NativeNavigation.recents(getContext(), display.getDisplayId());
        else NativeNavigation.key(getContext(), display.getDisplayId(), action == DockSpec.Action.BACK ? KeyEvent.KEYCODE_BACK : KeyEvent.KEYCODE_HOME);
    }

    private boolean openSettings() {
        if (previewOnly || getDisplay() == null) return false;
        try {
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(getDisplay().getDisplayId());
            getContext().startActivity(new Intent().setClassName("com.flipduo.module", "com.flipduo.module.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle());
            return true;
        } catch (Exception error) { NativeLog.warning("Could not open settings", error); return false; }
    }

    private boolean openLongPressApp(NavigationLongPress action, String app) {
        if (previewOnly || getDisplay() == null) return false;
        try {
            Intent intent = action == NavigationLongPress.LAUNCH_APP
                    ? LongPressLaunch.appIntent(getContext().getPackageManager(), app) : LongPressLaunch.systemIntent(action);
            if (intent == null || intent.resolveActivity(getContext().getPackageManager()) == null) {
                Toast.makeText(getContext(), "应用未安装或没有可打开的入口，请重新选择", Toast.LENGTH_SHORT).show();
                return false;
            }
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(getDisplay().getDisplayId());
            getContext().startActivity(intent, options.toBundle()); return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException error) {
            NativeLog.warning("Long-press application was not opened", error);
            Toast.makeText(getContext(), "无法打开，请重新选择应用", Toast.LENGTH_SHORT).show(); return false;
        }
    }

    private boolean requestTool(String action, GlassButton source) {
        if (previewOnly || pendingTool != 0 || getDisplay() == null) return false;
        pendingTool = TOOL_IDS.incrementAndGet();
        for (int i = 0; i < 6; i++) {
            DockSpec.Action a = action(i, menu);
            if (a == DockSpec.Action.NOTIFICATIONS || a == DockSpec.Action.CONTROL_CENTER || a == DockSpec.Action.LOCK) buttons[i].setEnabled(false);
        }
        source.setBusy(true); source.setEnabled(false);
        for (GlassButton button : tools) button.setEnabled(false);
        getContext().sendBroadcast(new Intent(SystemTools.REQUEST).setPackage("com.android.systemui")
                .putExtra("action", action).putExtra("id", pendingTool).putExtra("displayId", getDisplay().getDisplayId()), StatusWire.PERMISSION);
        handler.postDelayed(toolTimeout, 2200);
        return true;
    }

    private void toolCompleted(boolean success) {
        if (pendingTool == 0) return;
        clearToolWait();
        if (!success && attached) Toast.makeText(getContext(), "工具未执行：请确认 SystemUI 已加载本模块", Toast.LENGTH_SHORT).show();
    }

    private void clearToolWait() {
        pendingTool = 0; handler.removeCallbacks(toolTimeout);
        for (GlassButton button : buttons) { button.setEnabled(!previewOnly); button.setBusy(false); }
        for (GlassButton button : tools) { button.setEnabled(false); button.setBusy(false); }
        applyMenuMotion();
    }

    private void updateMenu() {
        for (int i = 0; i < buttons.length; i++) {
            String title = order[i] == DockSpec.Action.BACK && menu ? "返回，收起工具" : action(i, menu).title;
            if (config.eventIsland && i == 0 && eventState.expanded) title = "返回，收起活动";
            buttons[i].setContentDescription(title);
            buttons[i].setLongClickable(!previewOnly && !buttonsHidden() && config.longPress(action(i, menu)) != NavigationLongPress.NONE);
            buttons[i].refreshHoldProgress();
            buttons[i].invalidate();
        }
        for (int i = 0; i < buttons.length; i++) buttons[i].setSelected(order[i] == DockSpec.Action.MORE && menu);
        for (GlassButton button : tools) button.setContentDescription(button.fixedAction.title);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); rebuildAppearance(); attached = true;
        clearToolWait(); menu = false; menuProgress = 0; updateMenu(); applyAppearance(bottomControls);
        applyMenuMotion();
        if (previewOnly) return;
        getContext().registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter statusFilter = new IntentFilter(StatusWire.ACTION); statusFilter.addAction(StatusWire.TINT);
        getContext().registerReceiver(systemStatus, statusFilter, StatusWire.PERMISSION, handler, Context.RECEIVER_EXPORTED);
        getContext().registerReceiver(toolResults, new IntentFilter(SystemTools.RESULT), StatusWire.PERMISSION, handler, Context.RECEIVER_EXPORTED);
        getContext().registerReceiver(events, new IntentFilter(EventWire.STATE), StatusWire.PERMISSION, handler, Context.RECEIVER_EXPORTED);
        getContext().sendBroadcast(new Intent(StatusWire.REQUEST).setPackage("com.android.systemui"), StatusWire.PERMISSION);
        if (eventHosting) {
            eventAcknowledged = 0; eventFramePending = 0; eventRequest("host");
        } else updateEventHost();
        settleButtonPresence();
        applyTimeZone(TimeZone.getDefault()); // a re-attached instance may have missed a zone change
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_CHANGED); time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(timeChanges, time, null, handler, Context.RECEIVER_NOT_EXPORTED);
        AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
        if (accessibility != null) accessibility.addAccessibilityStateChangeListener(accessibilityChanges, handler);
        Display display = getDisplay();
        screenOn = display == null || display.getState() != Display.STATE_OFF;
        updateTimers();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        settleButtonPresence();
        if (toneAnimator != null) { toneAnimator.cancel(); toneAnimator = null; }
        holdNativeTone(false);
        suspendEventHost();
        attached = false; clearToolWait(); handler.removeCallbacksAndMessages(null);
        updateTimers(); // detached: both drivers stop and forget their (already removed) callbacks
        if (menuAnimator != null) menuAnimator.cancel();
        if (!previewOnly) { getContext().unregisterReceiver(batteryReceiver); getContext().unregisterReceiver(systemStatus); getContext().unregisterReceiver(toolResults); getContext().unregisterReceiver(events);
            getContext().unregisterReceiver(timeChanges);
            AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
            if (accessibility != null) accessibility.removeAccessibilityStateChangeListener(accessibilityChanges); }
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (eventHost != null) updateEventHost();
        updateTimers();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateTimers();
    }

    @Override public void onScreenStateChanged(int screenState) {
        super.onScreenStateChanged(screenState);
        screenOn = screenState == SCREEN_STATE_ON;
        updateTimers();
    }

    private boolean accessibilityOn() {
        AccessibilityManager accessibility = getContext().getSystemService(AccessibilityManager.class);
        return accessibility != null && accessibility.isEnabled();
    }

    /** Lifecycle, visibility, screen, config and accessibility changes all land here; cheap and idempotent. */
    private void updateTimers() {
        if (minuteClock == null || statusFallback == null) return; // a framework callback during super construction
        boolean live = attached && enabled && !previewOnly && screenOn && isShown() && getWindowVisibility() == VISIBLE;
        // Accessibility text includes the time, so it is kept current even when the clock is not drawn.
        minuteClock.update(live && (config.showClock || accessibilityOn()));
        statusFallback.update(live && !receivedSystemState);
    }

    private void applyTimeZone(TimeZone zone) { clock.setTimeZone(zone); day.setTimeZone(zone); }

    /** Degraded reading until SystemUI status arrives; never once it has. Scheduling lives in StatusFallback. */
    private void readFallbackStatus() {
        if (receivedSystemState) return;
        try {
            WifiManager wm = getContext().getSystemService(WifiManager.class);
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            state.wifiKnown = true; state.wifiEnabled = wm.isWifiEnabled();
            state.wifiConnected = state.wifiEnabled && info != null && info.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED;
            state.wifiLevel = state.wifiConnected && info.getRssi() > -127 && info.getRssi() < 0 ? Math.min(3, WifiManager.calculateSignalLevel(info.getRssi(), 5)) : -1;
            state.airplane = android.provider.Settings.Global.getInt(getContext().getContentResolver(), "airplane_mode_on", 0) != 0;
            state.saver = getContext().getSystemService(PowerManager.class).isPowerSaveMode();
        } catch (SecurityException error) { state.wifiKnown = false; }
        header.updateDescription(); header.invalidate();
    }

    @Override protected void onMeasure(int ws, int hs) {
        int width = MeasureSpec.getSize(ws), height = MeasureSpec.getSize(hs);
        if (landscape) {
            int cw = Math.round(width * DockSpec.CELL / (float) DockSpec.HEIGHT);
            int ch = Math.round(height * DockSpec.CELL / (float) DockSpec.WIDTH);
            measureExact(header, Math.round(width * (DockSpec.HEADER + DockSpec.edgeOffset(config.offsetY, bottomControls)) / (float) DockSpec.HEIGHT), height);
            for (View button : buttons) measureExact(button, cw, ch);
            for (View button : tools) measureExact(button, cw, ch);
            for (int i = 0; i < panels.length; i++) measureExact(panels[i], cw * DockLayout.members(DockLayout.UNITS[i]), ch);
            measureExact(toolPanel, cw * (config.eventIsland ? 1 : 2), ch);
            int iw = Math.round(width * island.modelHeight() / (float) DockSpec.HEIGHT);
            measureExact(island, iw, ch); measureExact(islandPanel, iw, ch);
            setMeasuredDimension(width, height); return;
        }
        int cellWidth = Math.round(width * DockSpec.CELL / (float) DockSpec.WIDTH);
        int cellHeight = Math.round(height * DockSpec.CELL / (float) DockSpec.HEIGHT);
        header.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(height * (DockSpec.HEADER + DockSpec.edgeOffset(config.offsetY, bottomControls))
                        / (float) DockSpec.HEIGHT), MeasureSpec.EXACTLY));
        for (View button : buttons) button.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        for (View button : tools) button.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        for (int i = 0; i < panels.length; i++) panels[i].measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight * DockLayout.members(DockLayout.UNITS[i]), MeasureSpec.EXACTLY));
        toolPanel.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight * (config.eventIsland ? 1 : 2), MeasureSpec.EXACTLY));
        int islandHeight = Math.round(height * island.modelHeight() / (float) DockSpec.HEIGHT);
        island.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(islandHeight, MeasureSpec.EXACTLY));
        islandPanel.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(islandHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (landscape) { layoutLandscape(); return; }
        int offset = Math.round(DockSpec.edgeOffset(config.offsetY, bottomControls) * getHeight() / (float)DockSpec.HEIGHT);
        header.layout(0, 0, getWidth(), header.getMeasuredHeight());
        float sx = getWidth() / (float) DockSpec.WIDTH, sy = getHeight() / (float) DockSpec.HEIGHT;
        for (int i = 0; i < buttons.length; i++) {
            int modelX = config.eventIsland && i < 3 ? IslandGeometry.navigationX(bottomControls) : layout.x[i];
            int modelY = config.eventIsland && i < 3 ? DockSpec.top(i) : layout.y[i];
            int x = Math.round(modelX * sx), y = Math.round(modelY * sy) + offset;
            buttons[i].layout(x, y, x + buttons[i].getMeasuredWidth(), y + buttons[i].getMeasuredHeight());
        }
        float scale = config.scale / 100f;
        for (int i = 0; i < panels.length; i++) {
            int anchor = DockLayout.UNITS[i], x = buttons[anchor].getLeft(), y = buttons[anchor].getTop();
            panels[i].layout(x,y,x+panels[i].getMeasuredWidth(),y+panels[i].getMeasuredHeight());
            panels[i].setScaleX(scale); panels[i].setScaleY(scale); panels[i].setAlpha(1);
        }
        int pair = toolsPair();
        if (config.eventIsland) {
            int x = Math.round(IslandGeometry.islandX(bottomControls) * sx), y = Math.round(IslandGeometry.LOCK_TOP * sy) + offset;
            tools[0].layout(x, y, x + tools[0].getMeasuredWidth(), y + tools[0].getMeasuredHeight());
            toolPanel.layout(x, y, x + toolPanel.getMeasuredWidth(), y + toolPanel.getMeasuredHeight());
        } else {
            toolPanel.layout(buttons[pair].getLeft(), buttons[pair].getTop(), buttons[pair].getRight(), buttons[pair + 1].getBottom());
            for (int i = 0; i < tools.length; i++) tools[i].layout(buttons[pair+i].getLeft(), buttons[pair+i].getTop(), buttons[pair+i].getRight(), buttons[pair+i].getBottom());
        }
        int ix = Math.round(IslandGeometry.islandX(bottomControls)*sx), iy = Math.round(IslandGeometry.TOP*sy)+offset;
        island.layout(ix,iy,ix+island.getMeasuredWidth(),iy+island.getMeasuredHeight());
        islandPanel.layout(ix,iy,ix+islandPanel.getMeasuredWidth(),iy+islandPanel.getMeasuredHeight());
        islandPanel.setScaleX(scale); islandPanel.setScaleY(scale);
        applyMenuMotion();
    }

    private static void measureExact(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    private void layoutLandscape() {
        float sx = getWidth() / (float) DockSpec.HEIGHT, sy = getHeight() / (float) DockSpec.WIDTH;
        int offset = Math.round(DockSpec.edgeOffset(config.offsetY, bottomControls) * sx);
        header.layout(0, 0, header.getMeasuredWidth(), getHeight());
        for (int i = 0; i < buttons.length; i++) {
            int mx = config.eventIsland && i < 3 ? IslandGeometry.navigationX(bottomControls) : layout.x[i];
            int my = config.eventIsland && i < 3 ? DockSpec.top(i) : layout.y[i];
            int x = Math.round(my * sx) + offset, y = Math.round((DockSpec.WIDTH - mx - DockSpec.CELL) * sy);
            buttons[i].layout(x, y, x + buttons[i].getMeasuredWidth(), y + buttons[i].getMeasuredHeight());
        }
        float scale = config.scale / 100f;
        for (int i = 0; i < panels.length; i++) {
            View anchor = buttons[DockLayout.UNITS[i]];
            panels[i].layout(anchor.getLeft(), anchor.getTop(), anchor.getLeft() + panels[i].getMeasuredWidth(), anchor.getTop() + panels[i].getMeasuredHeight());
            panels[i].setScaleX(scale); panels[i].setScaleY(scale); panels[i].setAlpha(1);
        }
        int iy = Math.round((DockSpec.WIDTH - IslandGeometry.islandX(bottomControls) - DockSpec.CELL) * sy);
        int ix = Math.round(IslandGeometry.TOP * sx) + offset;
        if (config.eventIsland) {
            int x = Math.round(IslandGeometry.LOCK_TOP * sx) + offset;
            tools[0].layout(x, iy, x + tools[0].getMeasuredWidth(), iy + tools[0].getMeasuredHeight());
            toolPanel.layout(x, iy, x + toolPanel.getMeasuredWidth(), iy + toolPanel.getMeasuredHeight());
        } else {
            int pair = toolsPair();
            toolPanel.layout(buttons[pair].getLeft(), buttons[pair].getTop(), buttons[pair + 1].getRight(), buttons[pair].getBottom());
            for (int i = 0; i < tools.length; i++) {
                View anchor = buttons[pair + i]; tools[i].layout(anchor.getLeft(), anchor.getTop(), anchor.getRight(), anchor.getBottom());
            }
        }
        island.layout(ix, iy, ix + island.getMeasuredWidth(), iy + island.getMeasuredHeight());
        islandPanel.layout(ix, iy, ix + islandPanel.getMeasuredWidth(), iy + islandPanel.getMeasuredHeight());
        islandPanel.setScaleX(scale); islandPanel.setScaleY(scale); applyMenuMotion();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
    }

    void dumpButtonBounds() {
        NativeLog.info("Dock diagnostic: shown=" + isShown() + " attached=" + attached + " material=independent dark=" + appearance.dark + " tools=" + menu + " status=" + state.centre());
        NativeLog.info("Header tint known=" + hasStatusTint() + " color=#" + Integer.toHexString(state.statusTint) + " display=" + state.statusTintDisplay
                + " source=" + (clockTone ? "clock" : "native-strip") + " nativeDark=" + nativeDark);
        NativeLog.info("Event island host=" + eventHosting + " focus=" + eventState.focus + " expanded=" + eventState.expanded
                + " notifications=" + eventState.notifications + " widgets=" + widgetsExpanded + " mini=" + miniSlotted + " height=" + island.getHeight());
        for (int i = 0; i < panels.length; i++) NativeLog.info("Material group=" + i + " " + panels[i].describeBlur());
        NativeLog.info("Material tools " + toolPanel.describeBlur());
        for (GlassButton button : buttons) {
            Rect rect = new Rect(); boolean visible = button.getGlobalVisibleRect(rect);
            int[] location = new int[2]; button.getLocationOnScreen(location);
            NativeLog.info("Button " + button.getContentDescription() + " visible=" + visible + " rect=" + rect.toShortString()
                    + " screen=" + location[0] + "," + location[1] + " size=" + button.getWidth() + "x" + button.getHeight());
        }
    }

    void fillTouchableRegion(Region region) {
        region.setEmpty();
        if (getVisibility() != VISIBLE || !enabled) return;
        if (slotOwned()) return; // The native pager/mini window owns its touches.
        ArrayList<View> targets = new ArrayList<>();
        targets.add(header);
        if (!buttonsHidden() && !slot().activityExpanded) {
            targets.addAll(Arrays.asList(buttons)); targets.addAll(Arrays.asList(tools));
        }
        for (View view : targets) {
            if (view.getVisibility() != VISIBLE || view.getAlpha() <= .01f) continue;
            Rect rect = new Rect(); view.getHitRect(rect); rect.offset(getLeft(),getTop());
            region.op(rect, Region.Op.UNION);
        }
        if (!buttonsHidden() && config.eventIsland && island.getVisibility() == VISIBLE && island.getAlpha() > .01f)
            island.addTouchableRegion(region, getLeft(), getTop());
    }

    private final class Header extends View {
        private final TrioPainter trio = new TrioPainter();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        Header(Context context) {
            super(context); setImportantForAccessibility(previewOnly ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            text.setTextAlign(Paint.Align.CENTER); text.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
            text.setFontFeatureSettings("tnum");
        }
        void updateDescription() {
            setContentDescription("时间 " + clock.format(new Date()) + "，" + day.format(new Date()) + "，"
                    + (state.battery < 0 ? "电量未知" : "电量 " + state.battery + "%") + "，"
                    + (state.showWifi() ? "Wi-Fi" : state.centre()) + "，点按打开外屏设置，长按临时"
                    + (buttonsHidden() ? "显示" : "隐藏") + "所有按钮");
        }
        @Override protected void onDraw(Canvas c) {
            if (landscape) { drawLandscape(c); return; }
            int offset = DockSpec.edgeOffset(config.offsetY, bottomControls);
            c.save(); c.scale(getWidth() / (float) DockSpec.WIDTH, getHeight() / (float)(DockSpec.HEADER + offset));
            c.translate(0, offset);
            int ink = headerInk();
            if (config.showTrio) {
                c.save(); c.translate(112, 78); float s = config.statusScale / 100f;
                c.scale(s, s); c.translate(-60, -60); trio.draw(c, state, ink); c.restore();
            }
            if (!config.showClock) { c.restore(); return; }
            float fontScale = getResources().getConfiguration().fontScale;
            text.setColor(ink); text.setTextSize(52 * fontScale);
            String time = clock.format(new Date());
            float width = text.measureText(time); if (width > 156) text.setTextSize(text.getTextSize() * 156 / width);
            c.drawText(time, 270, 78, text);
            text.setColor(headerSecondary(ink));
            text.setTextSize(36 * fontScale);
            String weekday = day.format(new Date()); float weekdayWidth = text.measureText(weekday);
            if (weekdayWidth > 166) text.setTextSize(text.getTextSize() * 166 / weekdayWidth);
            c.drawText(weekday, 270, 124, text); c.restore();
        }
        private void drawLandscape(Canvas c) {
            c.save();
            float fit = landscapeHeaderScale(getWidth(), getHeight());
            c.translate((getWidth() - 156 * fit) / 2f, (getHeight() - 398 * fit) / 2f);
            c.scale(fit, fit);
            int ink = headerInk();
            if (config.showTrio) {
                c.save(); c.translate(78, 104); float s = config.statusScale / 100f;
                c.scale(s, s); c.translate(-60, -60); trio.draw(c, state, ink); c.restore();
            }
            if (config.showClock) {
                float fs = getResources().getConfiguration().fontScale;
                text.setColor(ink); text.setTextSize(44 * fs);
                String time = clock.format(new Date()); float width = text.measureText(time);
                if (width > 140) text.setTextSize(text.getTextSize() * 140 / width);
                c.drawText(time, 78, 234, text);
                text.setColor(headerSecondary(ink)); text.setTextSize(32 * fs);
                String weekday = day.format(new Date()); width = text.measureText(weekday);
                if (width > 140) text.setTextSize(text.getTextSize() * 140 / width);
                c.drawText(weekday, 78, 282, text);
            }
            c.restore();
        }
    }

    static float landscapeHeaderScale(int width, int height) { return Math.min(width / 156f, height / 398f); }

    private final class GlassButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LongPressProgress hold = new LongPressProgress();
        private final int touchSlop;
        private NavigationLongPress heldAction = NavigationLongPress.NONE;
        private DockSpec.Action heldKey;
        private String heldApp = "";
        private boolean holdWasPressed;
        private final Runnable holdFrame = new Runnable() {
            @Override public void run() {
                if (!hold.active()) return;
                if (!holdAllowed()) { clearHoldProgress(); return; }
                invalidate();
                if (animateHold() && hold.needsFrame(SystemClock.uptimeMillis())) postOnAnimation(this);
            }
        };
        private final int slot;
        private final DockSpec.Action fixedAction;
        private boolean busy;
        GlassButton(Context context, int slot) {
            this(context, slot, null);
        }
        GlassButton(Context context, int slot, DockSpec.Action fixedAction) {
            super(context); this.slot = slot; setClickable(!previewOnly); setFocusable(!previewOnly); setEnabled(!previewOnly);
            this.fixedAction = fixedAction;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setImportantForAccessibility(previewOnly ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
                    if (host.isLongClickable() && slot >= 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK, config.longPress(action(slot, menu)).title));
                }
            });
        }
        private boolean animateHold() { return config.animations && ValueAnimator.areAnimatorsEnabled(); }
        private boolean holdAllowed() {
            return !previewOnly && slot >= 0 && enabled && isEnabled() && isLongClickable()
                    && getVisibility() == VISIBLE && getAlpha() > .01f
                    && NativeDock.this.getVisibility() == VISIBLE && NativeDock.this.getAlpha() > .01f
                    && slot().longPressAllowed
                    && heldKey == action(slot, menu) && heldAction != NavigationLongPress.NONE
                    && heldAction == config.longPress(heldKey) && heldApp.equals(config.longPressApp(heldKey));
        }
        private void startHoldProgress() {
            if (slot < 0) return;
            heldKey = action(slot, menu); heldAction = config.longPress(heldKey); heldApp = config.longPressApp(heldKey);
            if (!holdAllowed()) return;
            int delay = ViewConfiguration.getTapTimeout();
            hold.start(SystemClock.uptimeMillis(), delay, ViewConfiguration.getLongPressTimeout());
            holdWasPressed = isPressed();
            // Short taps get only the existing press feedback. No second long-click timer or action.
            postDelayed(holdFrame, delay);
        }
        void clearHoldProgress() {
            // View state callbacks can run from its constructor, before our fields are initialized.
            if (hold == null || !hold.active()) return;
            hold.clear(); removeCallbacks(holdFrame); invalidate();
        }
        void refreshHoldProgress() {
            if (hold.active() && !holdAllowed()) clearHoldProgress();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_MOVE && (event.getX() < -touchSlop || event.getY() < -touchSlop
                    || event.getX() >= getWidth() + touchSlop || event.getY() >= getHeight() + touchSlop)) clearHoldProgress();
            boolean handled = super.onTouchEvent(event); // Preserve the platform's short/long-click arbitration.
            if (action == MotionEvent.ACTION_DOWN && handled) startHoldProgress();
            return handled;
        }
        @Override public void cancelLongPress() {
            super.cancelLongPress(); clearHoldProgress();
        }
        @Override public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible);
            if (!visible) clearHoldProgress();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != VISIBLE) clearHoldProgress();
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) clearHoldProgress();
        }
        void setBusy(boolean value) { busy = value; setStateDescription(value ? "等待系统执行" : null); invalidate(); }
        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (!isEnabled() || holdWasPressed && !isPressed()) clearHoldProgress();
            if (isPressed()) holdWasPressed = true;
            float target = isPressed() && isEnabled() ? .95f : 1f;
            animate().cancel();
            if (isAttachedToWindow() && config.animations && ValueAnimator.areAnimatorsEnabled())
                animate().scaleX(target).scaleY(target).setDuration(isPressed() ? 100 : 150).start();
            else { setScaleX(target); setScaleY(target); }
            invalidate();
        }
        @Override protected void onDetachedFromWindow() {
            clearHoldProgress(); animate().cancel(); setScaleX(1); setScaleY(1); super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.scale(getWidth() / 156f, getHeight() / 156f);
            p.setShader(null); p.setStyle(Paint.Style.FILL);
            if (isPressed() || isFocused()) {
                p.setColor(isFocused() ? 0x20007aff : appearance.press);
                c.drawRoundRect(8, 8, 148, 148, 70, 70, p);
            }
            if (isFocused()) {
                p.setColor(0xff0878eb); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                c.drawRoundRect(6, 6, 150, 150, 72, 72, p);
            }
            if (busy) {
                p.setColor(appearance.secondary); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4.5f); p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(51, 51, 105, 105, -70, 290, false, p);
                c.drawLine(78, 61, 78, 78, p); c.drawLine(78, 78, 89, 85, p);
            } else {
                DockSpec.Action normal = fixedAction != null ? fixedAction : action(slot, false);
                DockSpec.Action tool = fixedAction != null ? fixedAction : action(slot, true);
                if (normal == tool) drawAction(c, normal, 1, false);
                else {
                    drawAction(c, normal, 1 - menuProgress, false);
                    drawAction(c, tool, menuProgress, true);
                }
            }
            drawHoldProgress(c, SystemClock.uptimeMillis());
            c.restore();
        }
        private void drawHoldProgress(Canvas c, long now) {
            if (!hold.active() || !holdAllowed()) return;
            float fraction = hold.fraction(now, animateHold());
            if (fraction <= 0) return;
            c.save(); float scale = config.scale / 100f; c.scale(scale, scale, 78, 78);
            p.setShader(null); p.setColor(appearance.ink); p.setAlpha(Math.round(Color.alpha(appearance.ink) * .6f));
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(6, 6, 150, 150, -90, 360 * fraction, false, p);
            p.setAlpha(255); p.setStyle(Paint.Style.FILL); c.restore();
        }
        private void drawAction(Canvas c, DockSpec.Action action, float alpha, boolean label) {
            if (alpha <= 0) return;
            c.save();
            c.translate(0, (label ? 1 : -1) * 10 * (1 - alpha));
            float s = config.scale / 100f * (.96f + .04f * alpha); c.scale(s, s, 78, 78);
            int base = isEnabled() || previewOnly ? appearance.ink : appearance.secondary;
            int color = (base & 0x00ffffff) | Math.round(Color.alpha(base) * alpha) << 24;
            icons.draw(c, action, 78, 78, 69 * config.iconScale / 100f, color);
            c.restore();
        }
    }
}
