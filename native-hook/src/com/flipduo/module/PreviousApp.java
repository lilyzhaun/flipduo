// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Only reorders an existing ordinary task on the same unlocked cover and user. Never relaunches or removes tasks. */
final class PreviousApp {
    static boolean open(Context context, int displayId) throws ReflectiveOperationException {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null || display.getState() != Display.STATE_ON
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        DisplayMetrics size = new DisplayMetrics(); display.getRealMetrics(size);
        if (!CutoutAppConfig.isCoverDisplay(size.widthPixels, size.heightPixels)) return false;
        int user = (int) ActivityManager.class.getDeclaredMethod("getCurrentUser").invoke(null);
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        int task = select(manager.getRunningTasks(16), displayId, user);
        if (task < 0) return false;
        ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(displayId);
        manager.moveTaskToFront(task, 0, options.toBundle()); return true;
    }

    static int select(List<ActivityManager.RunningTaskInfo> tasks, int display, int user) throws ReflectiveOperationException {
        Class<?> type = android.app.TaskInfo.class;
        Field taskDisplay = type.getDeclaredField("displayId"), taskUser = type.getDeclaredField("userId");
        Method activity = type.getDeclaredMethod("getActivityType"), mode = type.getDeclaredMethod("getWindowingMode");
        int current = -1;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.topActivity == null || task.taskId < 0 || taskDisplay.getInt(task) != display || taskUser.getInt(task) != user
                    || (int) activity.invoke(task) != 1 || (int) mode.invoke(task) != 1) continue;
            if (current < 0) {
                if (!task.isVisible()) return -1; // No foreground ordinary app: never guess a source from hidden history.
                current = task.taskId;
            } else if (task.taskId != current) return task.taskId;
        }
        return -1;
    }
}
