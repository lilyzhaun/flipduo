package com.flipduo.module;

/** State rules independently implemented against the installed CombinedStatus reference. */
final class TrioState {
    int battery = -1, wifiLevel = -1, mobileLevel = -1;
    boolean charging, saver, wifiKnown, wifiEnabled, wifiConnected;
    boolean mobileKnown, simPresent, inService, airplane;
    String technology = "";
    boolean statusTintKnown;
    int statusTint, statusTintDisplay = -1;
    long statusTintAt;

    boolean showWifi() { return wifiKnown && wifiEnabled && wifiConnected; }
    boolean showCross() { return simPresent && (airplane || (mobileKnown && !inService)); }
    boolean litCell(int dot) { return mobileKnown && simPresent && inService && !airplane && mobileLevel > dot; }
    String centre() {
        if (showWifi()) return "wifi";
        return mobileKnown && simPresent && inService && !airplane && !technology.isEmpty() ? technology : "–";
    }
    int wifiSegments() { return wifiLevel < 0 ? 0 : wifiLevel == 0 ? 1 : wifiLevel == 1 ? 2 : 3; }
    int batteryTint(int normal) {
        if (saver) return -19712;
        if (charging) return -14895277;
        return battery >= 0 && battery < 15 ? -1621190 : normal;
    }
    static String technology(int network, int override) {
        if ((network == 13 || network == 19 || network == 20) && (override == 3 || override == 4 || override == 5)) return "5G";
        switch (network) {
            case 1: case 2: case 4: case 7: case 11: case 16: return "2G";
            case 3: case 5: case 6: case 8: case 9: case 10: case 12: case 14: case 15: case 17: return "3G";
            case 13: case 19: return "4G";
            case 20: return "5G";
            default: return "";
        }
    }

    static float gap(float readoutWidth) {
        return readoutWidth <= 0 ? 0 : (float) Math.toDegrees(Math.asin(Math.min(.98, (readoutWidth / 2 + 8) / 50)));
    }
}
