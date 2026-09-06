package com.tcms.tracker;

import java.util.UUID;

/**
 * The contract's {@code IssueSearchHit} — a palette row (021 D-j).
 *
 * <p>Enough to draw it and to navigate, and nothing more. <strong>The project is named</strong>
 * because the search crosses projects and two of them may hold a story with the same title: a row
 * reading "Guest checkout" twice with no way to tell which is the one thing a palette must not do.
 */
public record IssueSearchHitView(
        String key,
        String title,
        IssueLevel typeLevel,
        UUID projectId,
        String projectName,
        IssueStatusView status) {
}
