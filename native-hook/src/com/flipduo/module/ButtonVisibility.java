// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/** Session-only override. Never edits a preference or changes the system fullscreen policy. */
final class ButtonVisibility {
    private boolean enabled = true, fullscreen, hideFullscreen;
    private Boolean temporary;
    void configure(boolean enabled, boolean hideFullscreen) {
        if (this.enabled != enabled || this.hideFullscreen != hideFullscreen) temporary = null;
        this.enabled = enabled; this.hideFullscreen = hideFullscreen;
    }
    void scene(boolean fullscreen) {
        if (this.fullscreen != fullscreen) temporary = null;
        this.fullscreen = fullscreen;
    }
    boolean hidden() { return enabled && (temporary != null ? temporary : hideFullscreen && fullscreen); }
    void toggle() { temporary = !hidden(); }
}
