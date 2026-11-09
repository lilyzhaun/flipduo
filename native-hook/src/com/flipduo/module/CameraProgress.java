package com.flipduo.module;

import android.app.Notification;
import org.json.*;
import java.util.Iterator;

/** Determinate progress only. Never infer percentages from text, icons, animation, ETA or package name. */
final class CameraProgress {
    static float read(Notification n) {
        if (n == null || n.extras == null || Notification.CATEGORY_CALL.equals(n.category)
                || Notification.CATEGORY_TRANSPORT.equals(n.category)) return -1;
        if (!n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                && n.extras.containsKey(Notification.EXTRA_PROGRESS)) {
            int max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
            int value = n.extras.getInt(Notification.EXTRA_PROGRESS, -1);
            if (max > 0 && value >= 0 && value <= max) return value / (float) max;
        }
        String json = n.extras.getString("miui.focus.param");
        if (json == null || json.length() > 65536) return -1;
        try { return find(new JSONObject(json), 0, new int[]{64}); }
        catch (JSONException invalid) { return -1; }
    }

    private static float percent(JSONObject info) {
        if (info == null || info.optBoolean("isAutoProgress", false)) return -1;
        Object raw = info.opt("progress");
        if (!(raw instanceof Number number)) return -1;
        double p = number.doubleValue();
        return Double.isFinite(p) && p >= 0 && p <= 100 ? (float) (p / 100) : -1;
    }

    private static float find(Object node, int depth, int[] budget) throws JSONException {
        if (depth > 8 || --budget[0] < 0) return -1;
        if (node instanceof JSONObject object) {
            for (String key : new String[]{"progressInfo", "multiProgressInfo"}) {
                float value = percent(object.optJSONObject(key));
                if (value >= 0) return value;
            }
            // Legacy base/revert progress templates also use a total of 100, even with two visual sections.
            String scene = object.optString("scene");
            if (scene.equals("carHailing") || scene.equals("events") || scene.equals("foodDelivery")
                    || scene.equals("templateBaseProgressScene") || scene.equals("templateRevertProgressScene")) {
                float value = percent(object); if (value >= 0) return value;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && budget[0] > 0) {
                String key = keys.next();
                // Do not descend into an explicitly auto-progress block and accidentally display a stale value.
                if (key.equals("progressInfo") || key.equals("multiProgressInfo")) continue;
                Object child = object.opt(key);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    float value = find(child, depth + 1, budget); if (value >= 0) return value;
                }
            }
        } else if (node instanceof JSONArray array) for (int i = 0; i < array.length() && budget[0] > 0; i++) {
            float value = find(array.opt(i), depth + 1, budget); if (value >= 0) return value;
        }
        return -1;
    }
}
