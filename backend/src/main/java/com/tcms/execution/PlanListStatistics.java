package com.tcms.execution;

import com.tcms.project.StatusKind;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code TestPlanSummary.passRate}, {@code coverage}, {@code exitCriteriaMet} and
 * {@code exitCriteriaTotal} for a <strong>page of plans</strong> (005 N1, N2 — slice S5).
 *
 * <h2>Why this is not {@link PlanStatisticsService} called once per row</h2>
 *
 * <p>{@link PlanService}'s header said these stay null for two reasons, and 005 S5 answers both
 * rather than overruling either.
 *
 * <p>The first was cost: <em>"filling them here would mean resolving every plan's executions once
 * per row of the plan list, which is that endpoint run N times for a screen that shows a bar."</em>
 * That is a statement about the <em>implementation</em>, and it is what this class exists to avoid
 * — <strong>two queries for the whole page</strong>, one expanding every plan's scope and one
 * collapsing every plan's results, neither of them per plan. N1 and N2 share both, which is why
 * they land together: splitting them would be two passes over one dataset.
 *
 * <p>The second was meaning: <em>"a pass rate has no meaning without the aggregation policy that
 * produced it, and the list has nowhere to state one."</em> That reasoning is <strong>kept, and it
 * is what fixes the policy here</strong>. The list has nowhere to state a policy, so it may not
 * offer a choice of one — it reports {@link AggregationPolicy#LATEST}, the default the statistics
 * page opens on, and the number a reader clicking through will see first. A list that varied its
 * policy invisibly is precisely the §6p ambiguity that argument is about; a list that names one
 * fixed reading is not.
 *
 * <h2>The arithmetic is {@link PlanStatisticsService}'s, restated in the small</h2>
 *
 * <p>Under {@code LATEST} the whole of that service's resolution collapses to two facts per
 * in-scope case: whether it has ever carried a recorded <em>verdict</em> (coverage, which is
 * deliberately policy-independent — see {@code coveredCases}) and what its <em>newest</em> recorded
 * result was (the pass rate's numerator). Both are aggregates, so both are computed in the database
 * rather than by reading every execution into Java, and the denominator is the scope size — because
 * {@code tally} adds one not-run row for every in-scope case that contributed nothing, which makes
 * the total land exactly on the scope under the three one-per-case policies.
 *
 * <p>That is a second implementation of one computation, which this repository has a standing rule
 * about. It is forced here by the same thing that forces {@code rollupTrend}: a per-row answer for a
 * page cannot be assembled from a per-plan service without running it per row. It is held to the
 * original by a test rather than by this comment — {@code PlanStatisticsEndpointTest.ListStatistics}
 * asserts the list's numbers against the detail endpoint's over one fixture, the device
 * {@code SuiteScopeAgreementTest} and {@code RollupAgreementTest} already use.
 *
 * <h2>The rollup is deliberately not read</h2>
 *
 * <p>{@code plan_snapshot} could serve {@code LATEST}, and {@code PlanStatisticsService} reads it —
 * behind a completeness check that costs a further query per plan. On a list that check is the
 * expense the rollup was supposed to remove, and skipping it is the defect
 * {@code snapshotIsComplete} exists to prevent, reported on every row at once. So the list is
 * always live, always current, and never behind: the property {@code computedAt} exists to declare
 * on the detail is simply true here.
 */
@Component
class PlanListStatistics {

    private final EntityManager entities;

    private final CaseFacts cases;

    private final PlanCriteria criteria;

    PlanListStatistics(EntityManager entities, CaseFacts cases, PlanCriteria criteria) {
        this.entities = entities;
        this.cases = cases;
        this.criteria = criteria;
    }

    /**
     * What the plan list shows beside a plan's name.
     *
     * @param passRate percentage 0-100 over the plan's scope, or <strong>null when nothing in scope
     *                 has a result</strong>. Null and not zero: a plan nobody has run has no pass
     *                 rate, and {@code 0} says every case failed.
     * @param coverage percentage 0-100 — in-scope cases carrying a recorded verdict over in-scope
     *                 cases. Null for an empty scope, where the denominator is zero.
     * @param exitCriteriaMet how many of the plan's exit criteria are currently met. A criterion
     *                 this endpoint cannot evaluate is <strong>not met</strong> here, which is a
     *                 narrowing of {@link CriterionView#met}'s three-valued answer and is stated on
     *                 the contract field: "2 of 3" has no spelling for "and one we did not check".
     *                 The detail keeps the third value, which is where a reader goes to see which.
     * @param exitCriteriaTotal how many the plan declares. {@code 0} is ordinary and renders as "no
     *                 exit criteria", never as "0 of 0 met".
     */
    record PlanStats(Double passRate, Double coverage, int exitCriteriaMet, int exitCriteriaTotal) {

        /** A plan with an empty scope and nothing recorded — the state of every plan on its first day. */
        static final PlanStats NONE = new PlanStats(null, null, 0, 0);
    }

    /**
     * Statistics for every plan on the page, in two queries plus the criteria each plan already
     * carries.
     *
     * @param plans the rows the list is about to render. Their {@code exitCriteria} column is read
     *              from the entity rather than re-queried — it is already loaded, and parsing it is
     *              {@link PlanCriteria}'s single implementation.
     */
    @Transactional(readOnly = true)
    Map<UUID, PlanStats> forPlans(UUID projectId, List<TestPlan> plans) {
        if (plans.isEmpty()) {
            return Map.of();
        }

        List<UUID> planIds = plans.stream().map(TestPlan::getId).toList();
        Map<UUID, Set<UUID>> scopes = cases.caseIdsByPlans(projectId, planIds);
        Map<UUID, Map<UUID, LatestResult>> results = latestResults(projectId, planIds);

        Map<UUID, PlanStats> stats = new HashMap<>(plans.size());
        for (TestPlan plan : plans) {
            stats.put(plan.getId(), statsFor(
                    plan,
                    scopes.getOrDefault(plan.getId(), Set.of()),
                    results.getOrDefault(plan.getId(), Map.of())));
        }
        return stats;
    }

    private PlanStats statsFor(
            TestPlan plan, Set<UUID> inScope, Map<UUID, LatestResult> results) {

        if (inScope.isEmpty()) {
            // Coverage's denominator is zero and every criterion is unevaluable, so the criteria are
            // parsed for their count and nothing is claimed about them. `PlanStatisticsService`
            // reports 0.0 coverage for the same plan, which is the one place the two differ and is
            // deliberate: the contract declares this field nullable precisely so an empty scope has
            // a spelling that is not a number.
            List<CriterionView> declared = criteria.parse(plan.getExitCriteria());
            return new PlanStats(null, null, 0, declared.size());
        }

        long passed = 0;
        long covered = 0;
        long recorded = 0;
        for (UUID caseId : inScope) {
            LatestResult result = results.get(caseId);
            if (result == null) {
                continue;
            }
            recorded++;
            if (result.covered()) {
                covered++;
            }
            if (result.latestKind() == StatusKind.PASSED) {
                passed++;
            }
        }

        long total = inScope.size();
        double coveragePercent = covered * 100.0 / total;
        // The denominator is the SCOPE and not the executed set — `tally`'s phantom not-run rows,
        // arrived at by arithmetic instead of by materialising them. A case added to the plan after
        // the last run appears as not-run and deflates nothing, which is what coverage is for.
        double passPercent = passed * 100.0 / total;

        List<CriterionView> evaluated = criteria.evaluate(
                criteria.parse(plan.getExitCriteria()),
                // `PlanStatisticsService.actuals`' two entries, and its condition: pass_rate is
                // offered only when there is a denominator, which an in-scope plan always has.
                Map.of("coverage", coveragePercent, "pass_rate", passPercent));

        int met = 0;
        for (CriterionView criterion : evaluated) {
            if (Boolean.TRUE.equals(criterion.met())) {
                met++;
            }
        }

        return new PlanStats(
                // Null when nothing IN SCOPE has been recorded — counted over the intersection and
                // not over the query's own rows, because a plan whose scope was narrowed after a run
                // still has results for cases it no longer covers, and those must not make an
                // untouched plan report 0%. Keyed on "has a result",
                // not on "passed anything": a plan whose every result is a failure has a pass rate
                // and it is zero, and collapsing those two into one null would hide the worst
                // plan on the list behind the same dash as the untouched one.
                recorded == 0 ? null : passPercent,
                coveragePercent,
                met,
                evaluated.size());
    }

    /**
     * One recorded case in one plan.
     *
     * @param latestKind the kind of its newest recorded result, ordered as
     *                   {@code PlanStatisticsService.resolve} orders {@code LATEST} — by
     *                   {@code executed_at} and then by execution id, so a tie resolves the same way
     *                   on every request rather than however the database returned the rows
     * @param covered    whether <strong>any</strong> of its recorded results was a verdict, which is
     *                   not the same question and is deliberately policy-independent: a case that
     *                   passed in March and was moved to RETEST last night is covered and is not
     *                   passing
     */
    private record LatestResult(StatusKind latestKind, boolean covered) {
    }

    /**
     * Every plan's recorded results, collapsed to one row per (plan, case), in one query.
     *
     * <p>Both aggregates come out of one {@code GROUP BY} rather than two passes:
     * {@code array_agg(… ORDER BY …)[1]} is the newest row's kind and {@code bool_or} is "ever a
     * verdict". Two queries would each carry their own copy of "recorded means {@code status_id IS
     * NOT NULL} and {@code executed_at IS NOT NULL}", which is the predicate
     * {@code ExecutionFacts}' header already explains is guarded twice on purpose.
     *
     * <p>Out-of-scope and soft-deleted cases are not excluded <em>here</em> — the caller intersects
     * with the scope it already holds, which is the same drop {@code rowsInScope} performs in Java
     * and one fewer join in a query that runs for a whole page.
     *
     * <p>Deleted runs are excluded, matching {@code findForPlan}: a withdrawn run's results are not
     * the plan's current state.
     */
    private Map<UUID, Map<UUID, LatestResult>> latestResults(
            UUID projectId, Collection<UUID> planIds) {

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT r.plan_id,
                               e.case_id,
                               (array_agg(s.kind ORDER BY e.executed_at DESC, e.id DESC))[1],
                               bool_or(s.kind NOT IN ('UNTESTED', 'RETEST', 'WIP'))
                          FROM test_execution e
                          JOIN test_run r ON r.id = e.run_id AND r.deleted_at IS NULL
                          JOIN case_status s ON s.id = e.status_id
                         WHERE e.project_id = :projectId
                           AND r.plan_id IN (:planIds)
                           AND e.status_id IS NOT NULL
                           AND e.executed_at IS NOT NULL
                         GROUP BY r.plan_id, e.case_id
                        """)
                .setParameter("projectId", projectId)
                .setParameter("planIds", planIds)
                .getResultList();

        Map<UUID, Map<UUID, LatestResult>> byPlan = new HashMap<>();
        for (Object[] row : rows) {
            byPlan.computeIfAbsent((UUID) row[0], id -> new HashMap<>())
                    .put((UUID) row[1], new LatestResult(
                            StatusKind.valueOf((String) row[2]),
                            Boolean.TRUE.equals(row[3])));
        }
        return byPlan;
    }
}
