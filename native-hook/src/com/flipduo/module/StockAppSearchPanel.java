// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.util.LruCache;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Search UI attached to FlipLauncher's existing content. Never creates a Window or edits its launcher model. */
final class StockAppSearchPanel extends LinearLayout {
    private static final ExecutorService LOAD = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final Runnable dismissed;
    private final EditText query;
    private final TextView status;
    private final ListView list;
    private final Results results = new Results();
    private final int foreground, secondary, previousSoftInput;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> icons = new LruCache<>(24);
    private final HashSet<String> pendingIcons = new HashSet<>();
    private final IdentityHashMap<View, Integer> accessibility = new IdentityHashMap<>();
    private View previousFocus;
    private List<AppSearchIndex.App> apps = List.of(), visible = List.of();
    private boolean closed, loading = true, failed;
    private final Runnable keyboard;

    StockAppSearchPanel(Activity activity, Runnable dismissed) {
        super(activity); this.activity = activity; this.dismissed = dismissed;
        setOrientation(VERTICAL); setClickable(true); setFocusable(true);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        foreground = dark ? Color.WHITE : 0xff191919; secondary = dark ? 0xffbcbcbc : 0xff595959;
        setBackgroundColor(dark ? 0xff161616 : 0xfffafafa);
        setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(activity); query.setHint("应用名称或包名"); query.setTextColor(foreground); query.setHintTextColor(secondary);
        query.setTextSize(16); query.setSingleLine(true); query.setMinHeight(dp(48)); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); query.setContentDescription("搜索应用名称或包名");
        keyboard = () -> {
            if (!closed && eligible(activity)) {
                query.requestFocus(); activity.getSystemService(InputMethodManager.class).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            }
        };
        header.addView(query, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cancel = action("取消"); cancel.setOnClickListener(v -> close()); header.addView(cancel);
        addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = new TextView(activity); status.setTextColor(secondary); status.setTextSize(13);
        status.setPadding(dp(4), dp(8), dp(4), dp(8)); status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("正在读取本机应用…"); addView(status);
        list = new ListView(activity); list.setAdapter(results); list.setDivider(null);
        list.setOnItemClickListener((parent, view, position, id) -> launch(visible.get(position)));
        addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable text) { }
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            if (visible.size() == 1) launch(visible.get(0));
            return true;
        });
        previousSoftInput = activity.getWindow().getAttributes().softInputMode;
        setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            setPadding(dp(16) + safe.left, dp(12) + safe.top, dp(16) + safe.right, dp(12) + safe.bottom);
            return insets; // Sibling OEM views still receive the original insets.
        });
    }

    void show(FrameLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View sibling = root.getChildAt(i); accessibility.put(sibling, sibling.getImportantForAccessibility());
            sibling.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        previousFocus = activity.getCurrentFocus(); root.addView(this, new FrameLayout.LayoutParams(-1, -1));
        // Keep the native grid stationary. Only this panel consumes the IME's occupied area.
        activity.getWindow().setSoftInputMode((previousSoftInput & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        requestApplyInsets(); query.requestFocus(); post(keyboard);
        PackageManager pm = activity.getPackageManager();
        LOAD.execute(() -> {
            try {
                List<AppSearchIndex.App> loaded = catalogue(pm);
                main.post(() -> { if (!closed) { loading = false; apps = loaded; filter(); } });
            } catch (SecurityException error) {
                NativeLog.warning("Cannot read local launchable apps", error);
                main.post(() -> { if (!closed) { loading = false; failed = true; filter(); } });
            }
        });
    }

    static List<AppSearchIndex.App> catalogue(PackageManager pm) {
        LinkedHashMap<String, AppSearchIndex.App> apps = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            var entry = info.activityInfo;
            if (entry == null || !entry.exported || !entry.enabled || !entry.applicationInfo.enabled) continue;
            String label = entry.applicationInfo.loadLabel(pm).toString();
            apps.putIfAbsent(entry.packageName, new AppSearchIndex.App(entry.packageName, label));
        }
        List<AppSearchIndex.App> result = new ArrayList<>(apps.values()); Collator collator = Collator.getInstance();
        result.sort((a, b) -> { int name = collator.compare(a.label(), b.label()); return name != 0 ? name : a.packageName().compareTo(b.packageName()); });
        return result;
    }

    private void filter() {
        visible = AppSearchIndex.filter(apps, query.getText().toString()); results.notifyDataSetChanged();
        status.setText(loading ? "正在读取本机应用…" : failed ? "无法读取应用列表，请退出后重试"
                : visible.isEmpty() ? (query.getText().toString().isBlank() ? "没有可打开的应用" : "没有匹配的应用") : "本机应用 · " + visible.size());
    }

    private void launch(AppSearchIndex.App app) {
        if (closed || !eligible(activity)) { close(); return; }
        try {
            Intent intent = LongPressLaunch.appIntent(activity.getPackageManager(), app.packageName());
            if (intent == null) { status.setText("应用已不可用，请退出后重试"); return; }
            ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(activity.getDisplay().getDisplayId());
            activity.startActivity(intent, options.toBundle()); close();
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException error) {
            NativeLog.warning("Search result was not opened", error); status.setText("无法打开此应用，请选择其他结果");
        }
    }

    void close() {
        if (closed) return; closed = true; removeCallbacks(keyboard);
        activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        activity.getWindow().setSoftInputMode(previousSoftInput);
        if (getParent() instanceof ViewGroup parent) parent.removeView(this);
        for (var entry : accessibility.entrySet()) entry.getKey().setImportantForAccessibility(entry.getValue());
        accessibility.clear(); icons.evictAll(); pendingIcons.clear();
        if (previousFocus != null && previousFocus.isAttachedToWindow()) previousFocus.requestFocus();
        dismissed.run();
    }

    static boolean eligible(Activity activity) {
        Display display = activity.getDisplay();
        if (!NativeModule.config().desktopSearchEnabled() || activity.isFinishing() || activity.isDestroyed() || display == null || display.getState() != Display.STATE_ON
                || activity.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        Display.Mode mode = display.getMode();
        return CutoutAppConfig.isCoverDisplay(mode.getPhysicalWidth(), mode.getPhysicalHeight());
    }

    private TextView action(String label) {
        TextView text = new TextView(getContext()); text.setText(label); text.setTextColor(foreground); text.setTextSize(15);
        text.setGravity(Gravity.CENTER); text.setMinWidth(dp(48)); text.setMinHeight(dp(48)); text.setFocusable(true);
        TypedValue value = new TypedValue(); getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        if (value.resourceId != 0) text.setBackgroundResource(value.resourceId);
        return text;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** The row that started the request may have been recycled. Update the live binding instead. */
    static void updateDisplayedIcon(ViewGroup rows, String packageName, Drawable drawable) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i) instanceof LinearLayout row && row.getChildAt(0) instanceof ImageView icon
                    && packageName.equals(icon.getTag())) icon.setImageDrawable(drawable);
        }
    }

    private final class Results extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return visible.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout existing) row = existing;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(64)); row.setPadding(dp(12), dp(8), dp(12), dp(8));
                ImageView icon = new ImageView(getContext()); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout labels = new LinearLayout(getContext()); labels.setOrientation(VERTICAL);
                TextView label = new TextView(getContext()); label.setTextSize(16); label.setTextColor(foreground);
                TextView pkg = new TextView(getContext()); pkg.setTextSize(12); pkg.setTextColor(secondary);
                pkg.setSingleLine(true); pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(label); labels.addView(pkg);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                labelParams.setMarginStart(dp(12)); row.addView(labels, labelParams);
            }
            AppSearchIndex.App app = visible.get(position);
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(app.label()); ((TextView) labels.getChildAt(1)).setText(app.packageName());
            ImageView icon = (ImageView) row.getChildAt(0); icon.setTag(app.packageName());
            Drawable cached = icons.get(app.packageName()); icon.setImageDrawable(cached);
            if (cached == null && !closed && pendingIcons.add(app.packageName())) LOAD.execute(() -> {
                try {
                    Drawable loaded = activity.getPackageManager().getApplicationIcon(app.packageName());
                    main.post(() -> {
                        pendingIcons.remove(app.packageName());
                        if (!closed) {
                            icons.put(app.packageName(), loaded);
                            updateDisplayedIcon(list, app.packageName(), loaded);
                        }
                    });
                } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
                    // Package removal/visibility change during the query is normal; launching revalidates it.
                    main.post(() -> pendingIcons.remove(app.packageName()));
                }
            });
            return row;
        }
    }
}
