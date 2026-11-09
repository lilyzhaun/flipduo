// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** A swipe dismisses this event for the current hidden-buttons session, not the next event. */
final class AutoEventExpansion {
    private boolean hidden;
    private long dismissed = -1;
    boolean hidden() { return hidden; }
    boolean update(boolean value) {
        if (hidden == value) return false;
        hidden = value; dismissed = -1; return true;
    }
    boolean expand(long generation) { return hidden && dismissed != generation; }
    void dismiss(long generation) { if (hidden) dismissed = generation; }
    void reset() { hidden = false; dismissed = -1; }
}
