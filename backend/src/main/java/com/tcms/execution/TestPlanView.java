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
 * @param passRate <s><strong>null, always, in this slice.</strong> It is an aggregate over the
 *                 plan's runs, which is the statistics endpoint this slice does not implement — and
 *                 the contract marks it nullable precisely so "not computed" has a spelling.</s>
 *                 → <strong>written since 005 S5</strong>, by {@link PlanListStatistics} in one
 *                 batched pass over the page rather than by running the statistics endpoint per row.
 *                 Percentage 0-100 over the plan's scope under {@link AggregationPolicy#LATEST} —
 *                 the list has nowhere to state a policy, so it states one fixed reading rather than
 *                 varying invisibly, which is that class's whole argument.
 *                 <p>What survives unchanged is the nullability and its meaning: a plan with no
 *                 executions genuinely has no pass rate, and {@code 0} would read as total failure.
 * @param coverage <s>null for the same reason. Coverage needs a denominator that spans
 *                 {@code repository} and {@code reporting}.</s> → the denominator is the plan's own
 *                 scope, which {@link CaseFacts} has expanded since M2 and neither of those modules
 *                 is needed for. Null for an empty scope, where the denominator is zero.
 * @param exitCriteriaMet how many of {@link #exitCriteria} are currently met, and
 * @param exitCriteriaTotal how many the plan declares — "2 of 3" on the list row (005 N2). The
 *                 <em>count</em>, not the criteria themselves, which this record already carries in
 *                 full: the number is what a list row has space for. Computed in the same batched
 *                 pass as {@code passRate}, because splitting it would be two passes over one
 *                 dataset. A criterion this pass cannot evaluate counts as not met — "2 of 3" has no
 *                 spelling for "and one we did not check", and {@link CriterionView#met} keeps the
 *                 three-valued answer for the detail, which is where a reader goes to see which.
 * @param caseCount <strong>not</strong> null, and not the number of scope items: a scope item that
 *                 names a folder means the folder and everything beneath it. See
 *                 {@link CaseFacts#caseCountsByPlan}.
 * @param suiteIds the plan's scope, as stored — <strong>added so the editor can write back what it
 *                 was served</strong>. {@code caseCount} is an expansion and a derived number: it
 *                 cannot be edited, and nothing else on this response said which folders a plan
 *                 covers. §68 found the identical gap on a case's classification, where the detail
 *                 served names and the writer took ids, and the rule it produced is that a field a
 *                 {@code PATCH} accepts must be readable from the {@code GET} that precedes it.
 *                 Sorted, for {@link PlanVersions}' hash.
 * @param caseIds  likewise, for the scope items that name a single case. Empty on every plan the
 *                 SPA creates — its dialog offers folders only, deliberately — and not empty in
 *                 general, because the API has always accepted both.
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
        int exitCriteriaMet,
        int exitCriteriaTotal,
        String description,
        List<CriterionView> entryCriteria,
        List<CriterionView> exitCriteria,
        List<ConfigurationView> configurations,
        List<UUID> suiteIds,
        List<UUID> caseIds,
        List<String> tags,
        List<PlanSectionView> sections,
        String scopeText,
        String outOfScopeText,
        int currentVersionNo,
        long lockVersion,
        /*
          Whether this plan is out of the working list (V65, audit D2).

          A boolean while the entity stores `archived_at`, which is ProjectView.archived's shape and
          copied from it deliberately: PUT /projects/{projectId}/archive has done this one level up
          since M2. WHEN it happened, and by whom, are the audit row's (plan.archived /
          plan.unarchived).

          Served on the DETAIL as well as the list, and served even though the default list cannot
          contain an archived plan: a screen reached by a bookmark, a link from a run, or
          `?archived=true` has to be able to say so, and a plan that looks identical to a live one
          on the one screen that shows it in full is how somebody edits a plan they believe is
          current. Orthogonal to `status`, which archiving never touches.
        */
        boolean archived) {
}
