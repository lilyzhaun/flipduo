package com.flipduo.module;

/** Physical safe-area grid on the verified 1208x1392, 520-dpi cover. No Android dependencies. */
final class DockSpec {
    static final int WIDTH = 398, HEIGHT = 664, CELL = 156, HEADER = 156;
    static final int SEAM_BLEED = 3;
    private static final int[] TOP = {156, 338, 494, 156, 312, 494};
    enum Action {
        BACK("返回"), HOME("主页"), RECENTS("最近任务"), NOTIFICATIONS("通知栏"),
        CONTROL_CENTER("控制中心"), MORE("更多工具"), LOCK("锁屏"), SETTINGS("外屏设置"), CLOSE("收起工具");
        final String title;
        Action(String title) { this.title = title; }
    }
    private static final Action[] MAIN = {Action.BACK, Action.HOME, Action.RECENTS, Action.NOTIFICATIONS, Action.CONTROL_CENTER, Action.MORE};
    private static final Action[] TOOLS = {Action.BACK, Action.HOME, Action.RECENTS, Action.LOCK, Action.SETTINGS, Action.CLOSE};
    static int left(int slot) { return slot < 3 ? 24 : 218; }
    static int top(int slot) { return TOP[slot]; }
    static int edgeOffset(int inset, boolean bottomControls) { return bottomControls ? -inset : inset; }
    static Action action(int slot, boolean tools) {
        return (tools ? TOOLS : MAIN)[slot];
    }
    static String shortTitle(Action action) {
        switch (action) {
            case LOCK: return "锁屏";
            case SETTINGS: return "设置";
            case CLOSE: return "收起";
            default: return action.title;
        }
    }
}
