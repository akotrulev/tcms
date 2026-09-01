package com.tcms.tracker.board;

/**
 * Story B3's grouping axis.
 *
 * <p><del>{@code RELEASE} is <strong>absent until M3</strong> … a release swimlane would render
 * every card in one lane called "None", which reads as a broken board rather than an unbuilt
 * feature.</del> → <strong>M3 added it, which is when {@code issue.release_id} started carrying
 * values.</strong> A card with no release lands in the {@code None} lane the grouping already
 * produces on every other axis.
 *
 * <p>The prediction held exactly: adding the member broke {@code frontend/src/lib/labels.ts},
 * {@code ProjectBoardView.tsx} and {@code BoardSettingsPage.tsx} in one {@code tsc -b}, because
 * {@code swimlaneByLabels} is {@code satisfies Record<SwimlaneBy, string>}. A seventh axis has to
 * edit two places — this enum and that file — and the compile error is the mechanism that says so.
 */
public enum SwimlaneBy {
    NONE,
    EPIC,
    ASSIGNEE,
    LABEL,
    PRIORITY,
    RELEASE
}
