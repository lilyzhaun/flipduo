// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

/**
 * One decision for who owns the cover control slot and what each of our parts may still do there.
 * Inputs stay independent facts (widget panel open, mini window in the slot, SystemUI event state,
 * button session, pending tool); nothing here sends an ACK, opens or closes a panel, or touches a window.
 * Pure Java so the same rules can be checked on the host for every input combination.
 */
final class SlotOwnership {
    enum Owner { NAVIGATION, ACTIVITY, WIDGETS, MINI_WINDOW }

    static final class Facts {
        boolean preview, enabled = true, eventIsland, islandLock, landscape;
        /** The widget panel is open (user intent already applied) / a native mini window occupies the slot. */
        boolean widgets, mini;
        /** SystemUI event state; SystemUI already reports expanded=false for urgent alerts and while a mini window owns the slot. */
        boolean focus, expanded, urgent;
        boolean buttonsHidden, toolPending;
    }

    /** Draw, touch, independent compositor material and accessibility, decided together for one part. */
    static final class Part {
        final boolean draw, touch, material, accessible;
        Part(boolean draw, boolean touch, boolean material, boolean accessible) {
            this.draw = draw; this.touch = touch; this.material = material; this.accessible = accessible;
        }
    }

    final Owner owner;
    /** False while a native panel (widgets or mini window) owns the slot: the whole dock is transparent, untouchable and hidden from accessibility. */
    final boolean dockShown;
    /** Native content replaces our slot drawing; drives the background-suppression signal. */
    final boolean nativeContent;
    /** Whether the cover background behind the slot is covered (widgets keep the cover background). */
    final boolean coverFillOccluded;
    final boolean headerShown;
    /** An event-island activity card is expanded: keys perform nothing and leave the input region. */
    final boolean activityExpanded;
    /** Event-island mode parts: navigation keys with their panels, the island with its panel, the idle lock key. */
    final Part navigation, island, lock;
    final boolean canOpenWidgets, canRequestMini, longPressAllowed;
    /** Widgets must yield to a focused activity or an urgent alert; temporary unavailability is not a dismissal. */
    final boolean closeWidgets;

    private SlotOwnership(Facts f) {
        boolean slotOwned = f.widgets || f.mini;
        boolean expanded = f.eventIsland && f.expanded;
        activityExpanded = expanded;
        owner = f.mini ? Owner.MINI_WINDOW : f.widgets ? Owner.WIDGETS : expanded ? Owner.ACTIVITY : Owner.NAVIGATION;
        dockShown = !slotOwned;
        nativeContent = slotOwned || expanded;
        coverFillOccluded = f.mini || expanded;
        headerShown = !slotOwned;
        boolean lockVisible = f.enabled && f.eventIsland && f.islandLock && !f.focus && !f.urgent && !f.expanded && !slotOwned;
        boolean keysDrawn = !(f.expanded || slotOwned);
        // Hidden keys are already transparent and disabled; they now also leave the accessibility tree.
        navigation = new Part(keysDrawn, !f.preview && !f.expanded && !slotOwned && !f.toolPending, !nativeContent, keysDrawn);
        // User decision 2026-11-08: under a native card the transparent island also stops taking touches
        // (the window input region already skipped it); it yields drawing, touch, material and accessibility together.
        island = new Part(!nativeContent, !nativeContent, !nativeContent, !nativeContent);
        lock = new Part(lockVisible, lockVisible && !f.preview && !f.toolPending, lockVisible && !nativeContent, lockVisible);
        canOpenWidgets = f.enabled && !f.buttonsHidden && !f.landscape && f.eventIsland && !f.focus && !f.urgent && !f.expanded
                && !f.toolPending && !f.mini;
        canRequestMini = !f.preview && !f.mini && !f.toolPending && !expanded;
        longPressAllowed = f.enabled && !f.buttonsHidden && !slotOwned && !expanded && !f.toolPending;
        closeWidgets = f.widgets && (f.focus || f.urgent);
    }

    static SlotOwnership decide(Facts facts) { return new SlotOwnership(facts); }
}
