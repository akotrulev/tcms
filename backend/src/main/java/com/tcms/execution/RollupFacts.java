package com.tcms.execution;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place {@code execution} names a table {@code reporting} owns</strong> — and this
 * module's <em>second</em> seam. {@link CaseFacts} is the first and reads {@code repository}.
 *
 * <p>Two seams rather than one, which is a departure from {@code work.BoardFacts}' precedent of a
 * single class naming two foreign modules, and it is deliberate: {@code CaseFacts} is nine hundred
 * lines whose header is an argument about the <em>repository</em> boundary specifically — its
 * membership union, its version marks, its plan-scope expansion. Adding a rollup reader to it would
 * put two unrelated boundaries in one file with nothing distinguishing them, and the next person
 * would have to read both arguments to change either.
 *
 * <h2>The direction is the unusual part, and it is stated so nobody "fixes" it</h2>
 *
 * <p>{@code reporting} builds these tables by reading {@code execution}'s, and {@code execution}
 * then reads them back. <strong>At the table level that is a cycle.</strong> At the Java level there
 * is no dependency in either direction — no import, no bean, no call — which is the only level
 * ArchUnit and Spring Modulith check, and the only level at which a compile can break. The
 * alternative was moving two working endpoints into {@code reporting}, which would have made a
 * screen's module depend on which storage strategy it happened to use.
 *
 * <p><strong>The cost, stated plainly: nothing checks that this class still reads what
 * {@code ExecutionSource} still writes.</strong> A column renamed on one side compiles on the other.
 * {@code RollupAgreementTest} is what closes it — computing both ways over one fixture and asserting
 * equality — which is the same device {@code ExecutionEndpointTest} uses for the {@code repository}
 * seam: machine-checked end to end rather than asserted in this comment.
 *
 * <h2>Native SQL, and no entity for any of the three tables</h2>
 *
 * <p>{@code ExecutionSource}'s argument, unchanged and symmetrical: a JPA entity mapped over a table
 * another module owns is one table with two sets of column expectations for {@code ddl-auto:
 * validate} to check, and two places a mapping can drift. Nothing here reads a rollup row as an
 * object anyway — every consumer aggregates.
 *
 * <h2>What each method is NOT allowed to be asked</h2>
 *
 * <p>Neither rollup can answer a <em>denominator</em>. {@code plan_snapshot} holds one row per case
 * that has a recorded result, so coverage, not-run and the scope size come from
 * {@link CaseFacts#casesInPlanScope} exactly as they did before (§46, §6p) — the scope is the plan's
 * own, and a rollup that could be asked for it would be a second, disagreeing definition.
 */
@Component
class RollupFacts {

    /**
     * Constructor-injected rather than {@code @PersistenceContext} on a field: the project's rule is
     * constructor injection everywhere, and Spring resolves this to the same shared,
     * transaction-bound proxy the annotation would have produced.
     */
    private final EntityManager entities;

    RollupFacts(EntityManager entities) {
        this.entities = entities;
    }

    // ------------------------------------------------------------ suite history ---

    /** One suite's pre-aggregated counts for one run, and when they were written. */
    record SuiteRunRow(
            UUID runId,
            UUID configurationId,
            int suiteVersionNo,
            long passed,
            long failed,
            long blocked,
            long skipped,
            long notRun,
            long total,
            Instant lastExecutedAt,
            Instant updatedAt) {
    }

    /**
     * Every {@code suite_run_summary} row for one suite.
     *
     * <p>No {@code ORDER BY}: the caller sorts by the run's own {@code executedAt}, which lives on
     * {@code test_run} and not here. Ordering twice would put a stable-looking sequence in front of
     * a reader that the page then re-sorts, which is how two loads of one page come to disagree
     * about which run came first (this is {@code SuiteHistoryService}'s recorded reason for sorting
     * at the end rather than in SQL).
     */
    List<SuiteRunRow> suiteRunSummaries(UUID suiteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entities.createNativeQuery("""
                SELECT s.run_id, s.configuration_id, s.suite_version_no,
                       s.passed, s.failed, s.blocked, s.skipped, s.not_run, s.total,
                       s.last_executed_at, s.updated_at
                  FROM suite_run_summary s
                 WHERE s.suite_id = :suiteId
                """)
                .setParameter("suiteId", suiteId)
                .getResultList();

        List<SuiteRunRow> rows = new ArrayList<>(results.size());
        for (Object[] row : results) {
            rows.add(new SuiteRunRow(
                    (UUID) row[0],
                    (UUID) row[1],
                    ((Number) row[2]).intValue(),
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    ((Number) row[5]).longValue(),
                    ((Number) row[6]).longValue(),
                    ((Number) row[7]).longValue(),
                    ((Number) row[8]).longValue(),
                    (Instant) row[9],
                    (Instant) row[10]));
        }
        return List.copyOf(rows);
    }

    /**
     * The runs holding an execution of these cases that the rollup has <strong>not</strong>
     * summarised for this suite yet.
     *
     * <h2>Why the tail exists at all, and why omitting it would be a regression rather than staleness</h2>
     *
     * <p>A run whose results were recorded ninety seconds ago has no {@code suite_run_summary} row
     * until the next scheduler tick. Served from the rollup alone, that suite's history would be
     * <em>missing its most recent run entirely</em> — not a number fifteen minutes old, which is the
     * cost this design accepts and states through {@code computedAt}, but a row that is not there.
     * The live path never had that failure, and a tester who has just finished a run and opens the
     * history is precisely the person who would meet it.
     *
     * <p>So the read is rollup ∪ tail, and the tail is bounded by what has happened since the last
     * tick rather than by the project's whole history — which is the half that grows without limit
     * and the half the rollup exists to remove. {@code NOT EXISTS} against the rollup is the bound,
     * rather than a timestamp comparison: a run created long ago can receive a result today, and
     * that result changes an <em>already-summarised</em> row, which is the correcting statement's
     * job and correctly shows as staleness rather than as an absence.
     */
    List<UUID> runsWithoutSummary(List<UUID> caseIds, UUID suiteId) {
        if (caseIds.isEmpty()) {
            // An empty IN list is a SQL syntax error, and an empty suite is ordinary.
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<UUID> results = entities.createNativeQuery("""
                SELECT DISTINCT e.run_id
                  FROM test_execution e
                 WHERE e.case_id IN (:caseIds)
                   AND NOT EXISTS (SELECT 1 FROM suite_run_summary s
                                    WHERE s.run_id = e.run_id
                                      AND s.suite_id = :suiteId)
                """)
                .setParameter("caseIds", caseIds)
                .setParameter("suiteId", suiteId)
                .getResultList();

        return List.copyOf(results);
    }

    // ---------------------------------------------------------- plan statistics ---

    /**
     * One case's current verdict in one configuration, as {@code plan_snapshot} holds it.
     *
     * @param statusKind the {@link com.tcms.project.StatusKind} name, resolved when the rollup was
     *     written. Carried as text rather than resolved through {@code case_status} here, because
     *     the whole point of {@code V1}'s {@code status_kind} column is that reporting aggregates
     *     project-custom statuses "without knowing their names" — and a project that renames Blocked
     *     between two ticks must not change what a past verdict meant.
     */
    record PlanSnapshotRow(
            UUID executionId,
            UUID caseId,
            UUID configurationId,
            String statusKind,
            Instant executedAt,
            Instant updatedAt) {
    }

    List<PlanSnapshotRow> planSnapshot(UUID planId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entities.createNativeQuery("""
                SELECT ps.execution_id, ps.case_id, ps.configuration_id, ps.status_kind,
                       ps.executed_at, ps.updated_at
                  FROM plan_snapshot ps
                 WHERE ps.plan_id = :planId
                """)
                .setParameter("planId", planId)
                .getResultList();

        List<PlanSnapshotRow> rows = new ArrayList<>(results.size());
        for (Object[] row : results) {
            rows.add(new PlanSnapshotRow(
                    (UUID) row[0],
                    (UUID) row[1],
                    (UUID) row[2],
                    (String) row[3],
                    (Instant) row[4],
                    (Instant) row[5]));
        }
        return List.copyOf(rows);
    }

    /**
     * Pass rate per run of one plan, aggregated in the database.
     *
     * <h2>From {@code execution_fact}, and neither from {@code plan_snapshot} nor from a scan</h2>
     *
     * <p>{@code plan_snapshot} <strong>cannot</strong> hold this: it keeps one row per case, and a
     * trend is one point per <em>run</em>. Computing it from {@code test_execution} instead would
     * keep the very scan the rollup exists to remove, so the rollup path would be slower than the
     * live one, having added a table and kept the query.
     *
     * <p>{@code execution_fact} already carries {@code plan_id}, {@code run_id},
     * {@code status_kind}, {@code counts_as_pass} and {@code executed_at}, and {@code V1} ships
     * {@code execution_fact_plan_idx (company_id, plan_id, executed_at DESC)} for exactly this. The
     * join to {@code test_run} is this module's own table, so the only foreign name here is the fact
     * log.
     *
     * <p>It also means the trend and the headline share one clock — both move on the scheduler tick,
     * in that order (see {@code ExecutionFactScheduler}) — where a trend computed live beside a
     * rolled-up headline would disagree by up to fifteen minutes with nothing on screen to say so.
     *
     * <p>The predicates mirror {@code PlanStatisticsService.trend} exactly and each has a reason
     * stated there: only <strong>verdicts</strong> count (a case owed a result has no pass rate),
     * the run's point in time is its <strong>last</strong> result rather than its first, and a run
     * with nothing recorded is omitted rather than plotted at zero — a point at the bottom of the
     * chart is the strongest possible statement of failure.
     */
    record PlanTrendRow(String runKey, Instant executedAt, long passed, long executed) {
    }

    List<PlanTrendRow> planTrend(UUID planId) {
        @SuppressWarnings("unchecked")
        List<Object[]> results = entities.createNativeQuery("""
                SELECT r.key,
                       max(f.executed_at)                               AS executed_at,
                       count(*) FILTER (WHERE f.status_kind = 'PASSED') AS passed,
                       count(*)                                         AS executed
                  FROM execution_fact f
                  JOIN test_run r ON r.id = f.run_id
                 WHERE f.plan_id = :planId
                   AND r.deleted_at IS NULL
                   AND f.status_kind NOT IN ('UNTESTED', 'RETEST', 'WIP')
                 GROUP BY r.id, r.key
                """)
                .setParameter("planId", planId)
                .getResultList();

        List<PlanTrendRow> rows = new ArrayList<>(results.size());
        for (Object[] row : results) {
            rows.add(new PlanTrendRow(
                    (String) row[0],
                    (Instant) row[1],
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()));
        }
        return List.copyOf(rows);
    }
}
