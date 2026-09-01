package com.tcms.tracker.release;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code IssueTestCase} — a case linked to this story (H7), with the latest result it
 * has <strong>anywhere</strong>.
 *
 * <p>Deliberately not scoped to a release: a story's own panel asks <em>does this case pass</em>,
 * and readiness asks <em>did it pass for the version we are shipping</em>. Those are different
 * questions and the second is {@code GET /releases/{releaseId}/readiness}.
 *
 * <p>Sourced from {@code case_health}, the read model {@code repository} maintains — the same row
 * {@code TestCaseSummary.lastResult} is served from, so a case cannot show one result on its own
 * screen and another on the story that links it.
 */
public record IssueTestCaseView(
        UUID caseId,
        String caseKey,
        String title,
        String lastResultKind,
        Instant lastExecutedAt,
        String lastReleaseVersion) {
}
