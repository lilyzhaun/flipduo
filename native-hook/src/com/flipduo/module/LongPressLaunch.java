// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.provider.Settings;

/** Builds fresh fixed-purpose intents; never forwards an Intent, URI, flags or extras from preferences. */
final class LongPressLaunch {
    static Intent appIntent(PackageManager pm, String packageName) throws PackageManager.NameNotFoundException {
        String target = NavigationLongPress.appPackage(packageName);
        if (target.isEmpty()) return null;
        Intent entry = pm.getLaunchIntentForPackage(target);
        ComponentName component = entry == null ? null : entry.getComponent();
        if (component == null || !target.equals(component.getPackageName())) return null;
        ActivityInfo info = pm.getActivityInfo(component, 0);
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled || !target.equals(info.packageName)) return null;
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent systemIntent(NavigationLongPress action) {
        Intent intent = switch (action) {
            case CAMERA -> new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage("com.android.camera");
            case ASSISTANT -> new Intent(Intent.ACTION_VOICE_COMMAND);
            case SYSTEM_SETTINGS -> new Intent(Settings.ACTION_SETTINGS);
            default -> throw new IllegalArgumentException("Not a launch action");
        };
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
