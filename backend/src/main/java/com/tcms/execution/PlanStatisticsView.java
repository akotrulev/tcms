package com.tcms.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code PlanStatistics} in {@code docs/specs/openapi.yaml} — where a plan stands right now.
 *
 * <p>This is the "are we ready?" question, and it is deliberately <strong>not</strong> the aggregate
 * execution report (§6e), which asks "what happened across the whole cycle?" and spans many runs.
 * Both are needed and mixing them produces a number that is neither: this one reflects only the
 * latest state of each case, so a case that failed in March and passed last night is green here and
 * appears twice there.
 *
 * @param counts          counts, never percentages, for {@link ResultCountsView}'s reason — the SPA
 *                        offers a denominator toggle and needs both numbers to compute either.
 * @param coveragePercent <strong>a headline, not a footnote.</strong> 89% pass over 40% coverage is
 *                        not a green light, and putting the two side by side is what makes that
 *                        impossible to miss (§6p). Independent of the aggregation policy: a case is
 *                        covered or it is not, however its several results resolve to one.
 * @param stale           nothing executed against this plan in {@code STALE_AFTER} days. A plan page
 *                        showing 94% from three weeks ago, presented as current, is the most
 *                        dangerous screen in the product — so the age is on the response rather than
 *                        left to a client to work out from {@link #latestExecutionAt}.
 * @param bySuite         every suite holding an in-scope case, including suites where nothing has
 *                        been executed — those are the rows a release meeting is looking for, and a
 *                        breakdown built from the executions alone would omit exactly them.
 * @param blockers        the failed and blocked cases, named rather than counted. "21 failed" is a
 *                        number; 21 named cases with their defect links are a work list.
 * @param notRun          likewise. Unexecuted cases are the most commonly overlooked release risk.
 * @param trend           pass rate over the plan's successive runs. A plan at 89% and rising means
 *                        something quite different from 89% and falling.
 */
public record PlanStatisticsView(
        UUID planId,
        String planKey,
        String planName,
        int planVersionNo,
        AggregationPolicy aggregationPolicy,
        ResultCountsView counts,
        double coveragePercent,
        Instant latestExecutionAt,
        boolean stale,
        List<ConfigurationCountsView> byConfiguration,
        List<SuiteCountsView> bySuite,
        List<TestExecutionSummaryView> blockers,
        List<PlanCaseRefView> notRun,
        List<CriterionView> exitCriteria,
        List<TrendPointView> trend) {

    /**
     * One configuration's slice of the plan.
     *
     * <p>Keyed by the configuration's <em>name</em> rather than its id, because the contract declares
     * a string and the SPA renders it as the row label. A configuration is unique by name within a
     * project ({@code V1}), so this does not collapse two distinct rows into one.
     */
    public record ConfigurationCountsView(String configuration, ResultCountsView counts) {
    }

    /** One suite's slice of the plan, over that suite's in-scope cases only. */
    public record SuiteCountsView(
            UUID suiteId, String suiteKey, String suiteName, ResultCountsView counts) {
    }

    /**
     * One point on the trend line — one run of this plan.
     *
     * @param passRate passed over <em>executed</em> within that run, not over the plan's scope. The
     *                 line asks whether the runs are converging or degrading, and a run covering a
     *                 third of the plan would otherwise plot as a collapse rather than as a partial
     *                 run. This is the one percentage on the response, and it is one because a
     *                 sparkline has no room to state a denominator per point.
     */
    public record TrendPointView(String runKey, Instant executedAt, double passRate) {
    }
}
