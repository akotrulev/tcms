package com.tcms.execution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestPlan} in {@code docs/specs/openapi.yaml} — one plan, list row and detail alike.
 *
 * <h2>One record for both endpoints, deliberately</h2>
 *
 * <p>The contract declares {@code TestPlan} as {@code allOf: [TestPlanSummary, {…}]}, which is one
 * flat JSON object — so this record is flat, for the reason {@code repository.TestCaseDetailView}
 * gives at length. What is new here is that the <em>list</em> serves this shape too, where the
 * contract's list item was {@code TestPlanSummary}.
 *
 * <p>That was a divergence rather than a choice: the SPA's {@code usePlans} has typed its result as
 * {@code TestPlan[]} since Phase 1, and every {@code TestPlan} is a valid {@code TestPlanSummary} —
 * so the contract now declares the wider shape and no consumer changes. The alternative, two
 * assemblers, is the thing that makes a list row and a detail page disagree about a plan's status
 * three slices later.
 *
 * @param passRate <strong>null, always, in this slice.</strong> It is an aggregate over the plan's
 *                 runs, which is the statistics endpoint this slice does not implement — and the
 *                 contract marks it nullable precisely so "not computed" has a spelling. A plan with
 *                 no executions genuinely has no pass rate; 0 would read as total failure.
 * @param coverage null for the same reason. Coverage needs a denominator that spans
 *                 {@code repository} and {@code reporting}.
 * @param caseCount <strong>not</strong> null, and not the number of scope items: a scope item that
 *                 names a folder means the folder and everything beneath it. See
 *                 {@link CaseFacts#caseCountsByPlan}.
 */
public record TestPlanView(
        UUID id,
        String key,
        long keySeq,
        String name,
        PlanStatus status,
        String milestoneName,
        LocalDate milestoneDate,
        long caseCount,
        Double passRate,
        Double coverage,
        String description,
        List<CriterionView> entryCriteria,
        List<CriterionView> exitCriteria,
        List<ConfigurationView> configurations,
        int currentVersionNo,
        long lockVersion) {
}
