package com.flipduo.module;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Process;
import android.os.UserHandle;
import java.util.Arrays;

/** Privileged, package-targeted messages; no notification body or app-defined objects cross processes. */
final class EventWire {
    static final String REQUEST = "com.flipduo.module.EVENT_REQUEST";
    static final String STATE = "com.flipduo.module.EVENT_STATE";
    static void send(Context context, Intent intent) {
        context.sendBroadcast(intent, StatusWire.PERMISSION, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static boolean from(BroadcastReceiver receiver, Context context, String expectedPackage) {
        int uid = receiver.getSentFromUid();
        if (uid < 0 || !expectedPackage.equals(receiver.getSentFromPackage())) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains(expectedPackage);
    }
    static Intent request(String action, IBinder host, int display, long generation) {
        Bundle data = new Bundle(); data.putBinder("host", host);
        return new Intent(REQUEST).setPackage("com.android.systemui").putExtras(data)
                .putExtra("action", action).putExtra("display", display).putExtra("generation", generation);
    }
}
