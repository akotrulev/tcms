package com.tcms.execution;

import com.tcms.project.CaseStatusView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestExecution} in {@code docs/specs/openapi.yaml} — one whole execution, and the response
 * to the write.
 *
 * <p>Flat, because {@code allOf} is flat — {@code repository.TestCaseDetailView} carries the full
 * argument. The summary's eleven components are repeated rather than nested, and
 * {@link #of} takes the {@link TestExecutionSummaryView} the run detail renders so the two cannot
 * disagree about the same execution.
 *
 * @param lockVersion on the wire because the SPA sends it back as {@code If-Match}, so a second
 *                    tester on the same run is a 409 rather than a silent overwrite. The one
 *                    internal that earns its place in a response.
 * @param steps       the <strong>pinned</strong> version's steps, with results merged in. Empty is a
 *                    real state: a Gherkin case has no structured steps and the screen says so.
 */
public record TestExecutionView(
        UUID id,
        UUID caseId,
        String caseKey,
        String caseTitle,
        int caseVersionNo,
        CaseStatusView status,
        UserRefView assignee,
        UserRefView executedBy,
        Instant executedAt,
        Long durationMs,
        List<DefectLinkView> defects,
        String comment,
        String releaseVersion,
        StepFormat stepFormat,
        List<ConditionRefView> preconditions,
        List<ConditionRefView> postconditions,
        List<StepResultView> steps,
        List<EvidenceView> evidence,
        long lockVersion) {

    static TestExecutionView of(
            TestExecutionSummaryView summary,
            String comment,
            String releaseVersion,
            StepFormat stepFormat,
            List<ConditionRefView> preconditions,
            List<ConditionRefView> postconditions,
            List<StepResultView> steps,
            List<EvidenceView> evidence,
            long lockVersion) {

        return new TestExecutionView(
                summary.id(),
                summary.caseId(),
                summary.caseKey(),
                summary.caseTitle(),
                summary.caseVersionNo(),
                summary.status(),
                summary.assignee(),
                summary.executedBy(),
                summary.executedAt(),
                summary.durationMs(),
                summary.defects(),
                comment,
                releaseVersion,
                stepFormat,
                preconditions,
                postconditions,
                steps,
                evidence,
                lockVersion);
    }
}
