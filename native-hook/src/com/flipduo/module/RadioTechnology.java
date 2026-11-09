package com.flipduo.module;

/** DisplayInfo is a display decision, not the type of a currently connected data bearer. */
final class RadioTechnology {
    int displayNetwork, displayOverride, serviceNetwork, voiceNetwork, dataNetwork;

    void reset() { displayNetwork = displayOverride = serviceNetwork = voiceNetwork = dataNetwork = 0; }
    void display(int network, int override) { displayNetwork = network; displayOverride = override; }
    void service(int packet, int voice) { serviceNetwork = packet; voiceNetwork = voice; }
    void data(int network) { dataNetwork = network; }

    String technology() {
        if (cellular(displayNetwork)) return TrioState.technology(displayNetwork, displayOverride);
        // Registration exists before data connects, and remains available with mobile data off.
        int network = cellular(serviceNetwork) ? serviceNetwork : cellular(dataNetwork) ? dataNetwork : voiceNetwork;
        return TrioState.technology(network, 0);
    }
    private static boolean cellular(int network) { return !TrioState.technology(network, 0).isEmpty(); }

    static int selectSubscription(int activeData, int defaultData, int[] available) {
        for (int id : available) if (id == activeData) return id;
        for (int id : available) if (id == defaultData) return id;
        return available.length == 1 ? available[0] : -1;
    }
}
