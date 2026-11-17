// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import android.content.SharedPreferences;

/** Native vertical launcher grid only. Retired paging preferences are not migrated. */
public final class NativeDesktopColumns {
    public static final String KEY = "native_desktop_columns";
    public static int read(SharedPreferences prefs) { return normalize(prefs.getInt(KEY, 3)); }
    public static int normalize(int columns) { return columns == 4 ? 4 : 3; }

    static int editTopMargin(int itemCount, int visibleRows, int appHeight, int rowSpace, int editHeight) {
        // The native list includes one header and one edit item. Both occupy full rows.
        int apps = Math.max(0, itemCount - 2);
        int emptyRows = visibleRows - (apps + 3) / 4;
        return emptyRows > 0 ? Math.max(0, emptyRows * (appHeight + rowSpace) - editHeight) : 0;
    }

    private NativeDesktopColumns() { }
}
