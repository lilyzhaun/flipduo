// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Local labels only. No usage history, online suggestions, settings writes or OEM adapter mutations. */
final class AppSearchIndex {
    record App(String packageName, String label) { }

    static List<App> filter(List<App> apps, String query) {
        String text = query.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return List.copyOf(apps);
        List<App> result = new ArrayList<>();
        for (App app : apps) if (app.label.toLowerCase(Locale.ROOT).contains(text)
                || app.packageName.toLowerCase(Locale.ROOT).contains(text)) result.add(app);
        // Exact names, then name prefixes, then substrings/package matches; stable within each group.
        result.sort(Comparator.comparingInt(app -> rank(app, text)));
        return result;
    }

    private static int rank(App app, String query) {
        String label = app.label.toLowerCase(Locale.ROOT);
        return label.equals(query) ? 0 : label.startsWith(query) ? 1 : label.contains(query) ? 2 : 3;
    }
}
