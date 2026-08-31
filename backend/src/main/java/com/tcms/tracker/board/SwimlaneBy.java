package com.tcms.tracker.board;

/**
 * Story B3's grouping axis.
 *
 * <p>{@code RELEASE} is <strong>absent until M3</strong>, and that is a decision rather than an
 * omission: {@code issue_fact.release_id} is NULL until that module writes it, so a release
 * swimlane would render every card in one lane called "None" — which reads as a broken board, not
 * as an unbuilt feature. M3 adds the member, and {@code frontend/src/lib/labels.ts} makes that a
 * compile error on the frontend until it is labelled.
 */
public enum SwimlaneBy {
    NONE,
    EPIC,
    ASSIGNEE,
    LABEL,
    PRIORITY
}
