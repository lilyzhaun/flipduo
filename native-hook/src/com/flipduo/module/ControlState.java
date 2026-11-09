package com.flipduo.module;

/** Per-native-window intent, independent of disposable child Views. */
final class ControlState {
    private boolean enabled = true;
    private boolean powerMode;
    boolean active() { return enabled && !powerMode; }
    void disable() { enabled = false; }
    void setEnabled(boolean value) { enabled = value; }
    void enterPowerMode() { powerMode = true; }
    void exitPowerMode() { powerMode = false; }
}
