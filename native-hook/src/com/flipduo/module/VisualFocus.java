// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * F04 visual focus: a presentation-only quiet mode for FlipDuo's own camera breathing. It never touches
 * the system notification set, DND, volume or channels, and never rewrites cameraNotifications /
 * cameraBreathing. Timers, stopwatches and activities keep their cues; a new notification judged urgent
 * by the same rule as the event island (full-screen intent, call, alarm without a live timer) still pulses.
 * A pulse quietened during focus is consumed: leaving focus never replays it.
 */
final class VisualFocus {
    private VisualFocus() {}

    /** Whether a new-notification pulse must be dropped (consumed), given the existing gates plus focus. */
    static boolean dropPulse(boolean notifications, boolean breathing, boolean timerOwnsDot, boolean focus, boolean urgent) {
        return !notifications || !breathing || timerOwnsDot || focus && !urgent;
    }
}
