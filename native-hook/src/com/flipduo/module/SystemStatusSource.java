package com.flipduo.module;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.telephony.*;
import android.util.Log;
import android.view.Choreographer;
import java.lang.reflect.Method;
import java.util.*;

/** Runs inside SystemUI so privileged radio states are real, not guessed by FlipHome. */
final class SystemStatusSource {
    private static SystemStatusSource instance;
    private static final Handler STARTUP = new Handler(Looper.getMainLooper());
    private static boolean starting;
    private static int attempts;
    private static int statusTint, statusTintDisplay = -1;
    private static long statusTintAt;
    private final Context context;
    private final TrioState state = new TrioState();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TelephonyManager phone;
    private Radio radio;
    private int subId = -1;
    private final RadioTechnology radioTechnology = new RadioTechnology();
    private Method miuiLevel;
    private BroadcastReceiver systemEvents, requests;
    private ConnectivityManager.NetworkCallback wifiEvents;
    private SubscriptionManager.OnSubscriptionsChangedListener subscriptions;
    private boolean systemRegistered, requestsRegistered, wifiRegistered, subscriptionsRegistered, radioRegistered;
    private boolean closed, ready;
    private boolean tintPending;
    private final Choreographer.FrameCallback publishTint = frameTime -> {
        tintPending = false;
        if (!ready || closed) return;
        context().sendBroadcast(StatusWire.tint(statusTint, statusTintDisplay, statusTintAt), StatusWire.PERMISSION);
    };
    private String lastDescription = "";
    private final Runnable publish = () -> {
        state.statusTint = statusTint; state.statusTintDisplay = statusTintDisplay;
        state.statusTintKnown = statusTintDisplay >= 0;
        state.statusTintAt = statusTintAt;
        String description = "centre=" + state.centre() + " signal=" + state.mobileLevel + " known=" + state.mobileKnown + " service=" + state.inService + " wifi=" + state.wifiConnected
                + " sub=" + subId + " display=" + radioTechnology.displayNetwork + "/" + radioTechnology.displayOverride
                + " registered=" + radioTechnology.serviceNetwork + " data=" + radioTechnology.dataNetwork;
        if (!description.equals(lastDescription)) { NativeModule.info("SystemUI state " + description); lastDescription = description; }
        context().sendBroadcast(StatusWire.encode(state), StatusWire.PERMISSION);
        RuntimeReport.state(context(), state);
    };
    private Context context() { return context; }

    static void statusTint(int color, int displayId) {
        if (statusTint == color && statusTintDisplay == displayId) return;
        statusTint = color; statusTintDisplay = displayId;
        statusTintAt = SystemClock.elapsedRealtimeNanos();
        if (instance != null) instance.tintChanged();
    }

    static void start(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { STARTUP.post(() -> start(context)); return; }
        if (instance != null || starting || attempts >= 3) return;
        starting = true; attempts++;
        SystemStatusSource candidate = new SystemStatusSource(context);
        try {
            candidate.listen();
            instance = candidate; // Publish only a completely registered source.
            starting = false;
        } catch (RuntimeException | LinkageError error) {
            candidate.close();
            NativeModule.warning("Status registration failed, attempt " + attempts + "/3; partial listeners removed", error);
            if (attempts < 3) STARTUP.postDelayed(() -> { starting = false; start(context); }, attempts * 1500L);
            else starting = false;
        }
    }
    private SystemStatusSource(Context context) {
        this.context = context;
        try { miuiLevel = SignalStrength.class.getMethod("getMiuiLevel"); }
        catch (NoSuchMethodException error) { NativeModule.info("Miui signal level unavailable; using framework signal level"); }
    }

    private void changed() {
        if (!ready || closed) return;
        handler.removeCallbacks(publish); handler.postDelayed(publish, 40);
    }
    private void tintChanged() {
        if (!ready || closed || tintPending) return;
        // Bound traffic to one lightweight message per display frame. Never postpone a frame already queued.
        tintPending = true;
        Choreographer.getInstance().postFrameCallback(publishTint);
    }
    private void listen() {
        systemEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (closed) return;
                if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                    int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), max = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    state.battery = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, max));
                    int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
                    state.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                }
                state.airplane = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                state.saver = context.getSystemService(PowerManager.class).isPowerSaveMode();
                wifi(); bindPhone(); changed();
            }
        };
        context.registerReceiver(systemEvents, systemFilter(), null, handler, Context.RECEIVER_EXPORTED);
        systemRegistered = true;
        requests = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { changed(); }
        };
        context.registerReceiver(requests, new IntentFilter(StatusWire.REQUEST), StatusWire.PERMISSION, handler, Context.RECEIVER_EXPORTED);
        requestsRegistered = true;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        wifiEvents = new ConnectivityManager.NetworkCallback() {
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) { wifi(); changed(); }
                    @Override public void onLost(Network n) { wifi(); changed(); }
                };
        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiEvents, handler);
        wifiRegistered = true;
        subscriptions = new SubscriptionManager.OnSubscriptionsChangedListener() { @Override public void onSubscriptionsChanged() { bindPhone(); changed(); } };
        context.getSystemService(SubscriptionManager.class).addOnSubscriptionsChangedListener(context.getMainExecutor(), subscriptions);
        subscriptionsRegistered = true;
        wifi(); bindPhone(); ready = true; changed();
        NativeModule.info("v6.86 SystemUI status source ready; MIUI signal level, independent DisplayInfo and WWAN registration; frame-coalesced tint retained");
    }

    private void close() {
        closed = true; ready = false; handler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(publishTint); tintPending = false;
        if (systemRegistered) cleanup(() -> context.unregisterReceiver(systemEvents));
        if (requestsRegistered) cleanup(() -> context.unregisterReceiver(requests));
        if (wifiRegistered) cleanup(() -> context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiEvents));
        if (subscriptionsRegistered) cleanup(() -> context.getSystemService(SubscriptionManager.class).removeOnSubscriptionsChangedListener(subscriptions));
        if (radioRegistered && phone != null && radio != null) cleanup(() -> phone.unregisterTelephonyCallback(radio));
        systemRegistered = requestsRegistered = wifiRegistered = subscriptionsRegistered = radioRegistered = false;
        phone = null; radio = null;
    }

    private void cleanup(Runnable unregister) {
        try { unregister.run(); }
        catch (RuntimeException error) { Log.w("FlipDuoNative", "Could not remove failed-start listener", error); }
    }

    private static IntentFilter systemFilter() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED); f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.RSSI_CHANGED_ACTION); f.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
        return f;
    }

    private void wifi() {
        if (closed) return;
        try {
            WifiManager wm = context.getSystemService(WifiManager.class);
            state.wifiKnown = true; state.wifiEnabled = wm.isWifiEnabled();
            WifiInfo info = wm.getConnectionInfo();
            state.wifiConnected = state.wifiEnabled && info != null && info.getSupplicantState() == SupplicantState.COMPLETED;
            // Do not use the default network's transport: a VPN is not a Wi-Fi disconnect.
            state.wifiLevel = state.wifiConnected && info.getRssi() > -127 && info.getRssi() < 0
                    ? Math.min(3, WifiManager.calculateSignalLevel(info.getRssi(), 5)) : -1;
        } catch (SecurityException error) { state.wifiKnown = false; state.wifiLevel = -1; }
    }

    private void bindPhone() {
        if (closed) return;
        try {
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = sm.getActiveSubscriptionInfoList();
            if (active == null) active = Collections.emptyList();
            int selected = RadioTechnology.selectSubscription(SubscriptionManager.getActiveDataSubscriptionId(),
                    SubscriptionManager.getDefaultDataSubscriptionId(), active.stream().mapToInt(SubscriptionInfo::getSubscriptionId).toArray());
            state.simPresent = !active.isEmpty();
            if (selected == subId && phone != null && radioRegistered) return;
            if (phone != null && radio != null && radioRegistered) phone.unregisterTelephonyCallback(radio);
            radioRegistered = false;
            subId = selected; phone = null; radio = null;
            radioTechnology.reset();
            state.inService = false; state.mobileLevel = -1; state.technology = "";
            state.mobileKnown = !state.simPresent || state.airplane;
            if (selected < 0) return;
            phone = context.getSystemService(TelephonyManager.class).createForSubscriptionId(selected);
            radio = new Radio(selected); phone.registerTelephonyCallback(context.getMainExecutor(), radio); radioRegistered = true;
            ServiceState service = phone.getServiceState(); if (service != null) service(service);
            SignalStrength strength = phone.getSignalStrength(); if (strength != null) state.mobileLevel = signalLevel(strength);
            radioTechnology.data(phone.getDataNetworkType()); technology();
        } catch (SecurityException error) {
            state.mobileKnown = false;
            if (!radioRegistered) { phone = null; radio = null; subId = -1; }
            NativeModule.warning("Radio state unavailable", error);
        }
    }

    private void service(ServiceState service) {
        state.mobileKnown = true;
        int packet = 0, voice = 0;
        boolean dataRegistered = false;
        for (NetworkRegistrationInfo info : service.getNetworkRegistrationInfoList()) {
            // IWLAN/VoWiFi is not a cellular RAT, even when preferred for packet data.
            if (info.getTransportType() != AccessNetworkConstants.TRANSPORT_TYPE_WWAN || !info.isRegistered()) continue;
            if ((info.getDomain() & NetworkRegistrationInfo.DOMAIN_PS) != 0) {
                dataRegistered = true; packet = info.getAccessNetworkTechnology();
            }
            if ((info.getDomain() & NetworkRegistrationInfo.DOMAIN_CS) != 0) voice = info.getAccessNetworkTechnology();
        }
        radioTechnology.service(packet, voice);
        state.inService = service.getState() == ServiceState.STATE_IN_SERVICE || dataRegistered;
        technology();
    }
    private int signalLevel(SignalStrength strength) {
        if (miuiLevel != null) {
            try { return Math.max(0, Math.min(4, (Integer) miuiLevel.invoke(strength))); }
            catch (ReflectiveOperationException error) {
                miuiLevel = null;
                NativeModule.warning("Miui signal level failed; using framework signal level", error);
            }
        }
        return strength.getLevel();
    }
    private void technology() { state.technology = state.inService && !state.airplane ? radioTechnology.technology() : ""; }

    private final class Radio extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener, TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.ActiveDataSubscriptionIdListener {
        final int id;
        Radio(int id) { this.id = id; }
        @Override public void onSignalStrengthsChanged(SignalStrength s) { if (!closed && radio == this && id == subId) { state.mobileLevel = signalLevel(s); changed(); } }
        @Override public void onServiceStateChanged(ServiceState s) { if (!closed && radio == this && id == subId) { service(s); changed(); } }
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            if (closed || radio != this || id != subId) return;
            radioTechnology.display(info.getNetworkType(), info.getOverrideNetworkType()); technology(); changed();
        }
        @Override public void onDataConnectionStateChanged(int ignored, int type) {
            if (closed || radio != this || id != subId) return;
            radioTechnology.data(type); technology(); changed();
        }
        @Override public void onActiveDataSubscriptionIdChanged(int activeDataSubId) {
            if (closed || radio != this || id != subId) return;
            bindPhone(); changed();
        }
    }
}
