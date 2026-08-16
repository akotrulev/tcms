package com.tcms.execution;

import com.tcms.api.UserRef;
import com.tcms.project.CaseStatusView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestExecutionSummary} in {@code docs/specs/openapi.yaml} — one row of a run's checklist.
 *
 * @param caseKey       the case's display key, which is what identifies this row to a reader: an
 *                      execution has no display key of its own (plan §6t).
 * @param caseTitle     <strong>the pinned version's title</strong>, not the case's current one. It
 *                      is rendered beside {@link #caseVersionNo}, and a live title beside a pinned
 *                      version number is two contradictory claims on one line.
 * @param status        null until somebody records a result. The contract marks it nullable and the
 *                      SPA renders a null as "not run", which is exactly what it means.
 * @param assignee      null when nobody has been given the case in this run — an ordinary state,
 *                      and one the contract lied about until this slice (a bare {@code $ref} is not
 *                      nullable).
 * @param executedBy    null for an unexecuted row <em>and</em> for an ingested one, where
 *                      {@code executed_by_token} names a CI token rather than a person.
 * @param quarantined   <strong>a property of the case, read live, and not of this execution</strong>
 *                      (§237). True when somebody has decided this test's results are not to be
 *                      trusted — which says nothing about whether <em>this</em> result is good, and
 *                      changes nothing about the row: a quarantined case still appears on the
 *                      checklist, still takes a verdict, and still counts towards this run's pass
 *                      rate. It is here rather than only on {@link TestExecutionView} because a
 *                      badge a tester sees only after opening the execution is one they see after
 *                      they have already chosen it.
 */
public record TestExecutionSummaryView(
        UUID id,
        UUID caseId,
        String caseKey,
        String caseTitle,
        int caseVersionNo,
        CaseStatusView status,
        UserRef assignee,
        UserRef executedBy,
        Instant executedAt,
        Long durationMs,
        List<DefectLinkView> defects,
        boolean quarantined) {
}
