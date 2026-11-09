// SPDX-License-Identifier: GPL-3.0-only
package com.flipduo.module;

import java.text.BreakIterator;

/**
 * F01: the single summary line of a collapsed event-island activity. Priority: the native timer text
 * SystemUI already rendered, then a determinate progress from the same contract as the camera ring
 * (CameraProgress: standard extras or MIUI focus progressInfo, never text/ETA/icons), then the app name.
 * No stage text is offered: no verified structured stage field exists, so nothing like "配送中" is guessed.
 */
final class IslandSummary {
    enum Kind { CLOCK, PROGRESS, APP }

    final Kind kind;
    final String text;

    private IslandSummary(Kind kind, String text) { this.kind = kind; this.text = text; }

    static IslandSummary choose(String clock, int progress, String app) {
        if (clock != null && !clock.isEmpty()) return new IslandSummary(Kind.CLOCK, clock);
        if (progress >= 0 && progress <= 100) return new IslandSummary(Kind.PROGRESS, progress + "%");
        return new IslandSummary(Kind.APP, app == null ? "" : app);
    }

    /** 0..1 fraction to a whole percent, or -1 when there is no reliable determinate value. */
    static int percent(float fraction) {
        if (!(fraction >= 0f) || fraction > 1f) return -1;
        return Math.round(fraction * 100f);
    }

    /** Number of user-perceived characters (grapheme clusters). */
    static int graphemes(String text) {
        BreakIterator it = BreakIterator.getCharacterInstance(); it.setText(text);
        int count = 0; while (it.next() != BreakIterator.DONE) count++;
        return count;
    }

    /** The first {@code count} grapheme clusters; never splits a surrogate pair or combining sequence. */
    static String prefix(String text, int count) {
        BreakIterator it = BreakIterator.getCharacterInstance(); it.setText(text);
        int end = 0;
        for (int i = 0; i < count; i++) { int next = it.next(); if (next == BreakIterator.DONE) break; end = next; }
        return text.substring(0, end);
    }
}
