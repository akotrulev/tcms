package com.tcms.execution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Executions, by run. {@code @Transactional} for the reason on {@link TestPlanRepository}. */
@Transactional(readOnly = true)
public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {

    /**
     * One run's executions.
     *
     * <p>Ordered by the case's display key <em>sequence</em> rather than by insertion: a tester
     * working down a run wants the same order every time they come back to it, and the row order a
     * database happens to return is not an order. The sequence comes from the pinned snapshot, so
     * it is resolved after the fact by {@link RunService} — this query orders by {@code caseId} only
     * to make the result deterministic before that.
     */
    @Query("SELECT e FROM TestExecution e WHERE e.projectId = :projectId AND e.runId = :runId "
            + "ORDER BY e.caseId")
    List<TestExecution> findForRun(
            @Param("projectId") UUID projectId, @Param("runId") UUID runId);

    /**
     * One execution, checked against <strong>both</strong> the project and the run in the path.
     *
     * <p>The run predicate is the third gate and it is not redundant: an execution id belonging to
     * a different run in the same project would otherwise resolve through a path naming a run the
     * caller happens to be looking at, and the response would show a result from a run the client
     * never asked about. One empty {@link Optional} for every way of missing, so the controller has
     * nothing to un-collapse.
     */
    @Query("SELECT e FROM TestExecution e WHERE e.projectId = :projectId AND e.runId = :runId "
            + "AND e.id = :executionId")
    Optional<TestExecution> findInRun(
            @Param("projectId") UUID projectId,
            @Param("runId") UUID runId,
            @Param("executionId") UUID executionId);

    /**
     * Result tallies for a batch of runs, as {@code [runId, statusId, count]}.
     *
     * <p>One grouped query for a whole page rather than one per run: the run list shows a bar per
     * row, and resolving it per row is the N+1 that looks harmless because each query is fast. A
     * null {@code statusId} is a real group — it is every execution nobody has recorded yet.
     */
    @Query("SELECT e.runId, e.statusId, COUNT(e) FROM TestExecution e "
            + "WHERE e.runId IN :runIds GROUP BY e.runId, e.statusId")
    List<Object[]> tallyByRun(@Param("runIds") Collection<UUID> runIds);

    /**
     * Every execution belonging to any live run of one plan.
     *
     * <p>The unit the statistics endpoint aggregates over. A plan is typically covered by several
     * runs — a nightly CI run, a manual regression run, a re-run of the failures — so "the plan's
     * results" is the union across them and "the latest result" is resolved <strong>per case</strong>
     * rather than per run (plan §6p). That is why this returns rows rather than a tally: which row
     * wins is a question about the aggregation policy, and a {@code GROUP BY} would have to be
     * rewritten for each of the five.
     *
     * <p><strong>Deleted runs are excluded.</strong> Runs are soft-deleted, and a plan's readiness
     * must not be argued from a run somebody withdrew — §6p's rule that deleted runs "must not
     * orphan history" is about the case timeline, where the entry is marked and kept; a
     * *current-state* figure is the other case, and counting a withdrawn run there would restore a
     * result the person deleting it meant to retract.
     *
     * <p>Returns the run's own columns alongside, so {@code byConfiguration} and {@code trend} do not
     * re-query per run: {@code [execution, runId, runKey, configurationId, startedAt]}. An execution
     * has no configuration of its own — {@code configuration_id} is on {@code test_run} (V1), because
     * a run is by definition one pass in one configuration.
     */
    @Query("SELECT e, r.id, r.key, r.configurationId, r.startedAt "
            + "FROM TestExecution e JOIN TestRun r ON r.id = e.runId "
            + "WHERE e.projectId = :projectId AND r.planId = :planId AND r.deletedAt IS NULL")
    List<Object[]> findForPlan(@Param("projectId") UUID projectId, @Param("planId") UUID planId);

    /**
     * Every execution of one case, across every run, as {@code [execution, run]}.
     *
     * <p><strong>Deleted runs are INCLUDED here, and that is the difference from
     * {@link #findForPlan}</strong> — the two are otherwise the same join and the contrast is the
     * whole point. §6p: "deleted runs must not orphan history … with the entry marked rather than
     * vanishing". A timeline is a record of what happened; a plan's statistics are a claim about
     * where things stand. Counting a withdrawn result in the second would restore a verdict somebody
     * retracted, and dropping it from the first would silently shorten history. So this returns the
     * row and {@code CaseHistoryService} marks it.
     *
     * <p>Returns the whole run rather than columns: a history entry names the run's key, name, plan,
     * configuration, release, build and source, which is most of the entity, and a projection
     * listing seven columns positionally is the shape that breaks silently when one is inserted.
     * A case's runs are bounded by how many times it has been executed, so this is not the
     * hundreds-of-rows-per-request case {@link #findForRun} is tuned for.
     *
     * <p>Ordered by execution time, oldest first, with {@code NULLS LAST}: an execution nobody has
     * recorded has no {@code executedAt}, and it belongs at the end as work outstanding rather than
     * at the beginning as the oldest event. The id breaks ties so the order is stable across two
     * calls — without it a case executed twice in one run comes back in whichever order the
     * database chose, and the flakiness figure computed from that order would move between requests.
     */
    @Query("SELECT e, r FROM TestExecution e JOIN TestRun r ON r.id = e.runId "
            + "WHERE e.projectId = :projectId AND e.caseId = :caseId "
            + "ORDER BY e.executedAt ASC NULLS LAST, e.id ASC")
    List<Object[]> findForCase(
            @Param("projectId") UUID projectId, @Param("caseId") UUID caseId);

    /**
     * One suite's executions, already tallied, as {@code [runId, statusId, count, lastExecutedAt]}.
     *
     * <p><strong>Grouped in the database rather than in Java</strong>, which is the difference from
     * {@link #findForCase} and the reason this is a separate query rather than a wider call to that
     * one. A case's timeline names every execution individually and is bounded by how often one case
     * has run; a suite's history names none of them — it renders one row per run carrying counts — and
     * its input is every execution of every case in a subtree. Fetching those to count them in a loop
     * would pull a project's entire execution table through the persistence context to produce a
     * dozen rows.
     *
     * <p>The grouping stops at {@code status_id} rather than answering the six buckets, because the
     * mapping from a status to a bucket is {@code StatusKind} — a project-configurable vocabulary
     * that {@link ResultStatuses} resolves and SQL here has no access to. So this returns
     * (run × status) tallies and the service folds them.
     *
     * <p><strong>Deleted runs are INCLUDED</strong>, exactly as in {@link #findForCase} and for the
     * same §6p rule: a history entry is a record of what happened, so the row stays and the service
     * marks it. Contrast {@link #findForPlan}, which excludes them because a plan's statistics are a
     * claim about where things stand.
     *
     * <p>{@code MAX(executedAt)} is the entry's timestamp: the most recent result recorded against
     * the suite in that run. It is null for a run that has opened but recorded nothing, and the
     * service falls back to the run's own start — the contract marks {@code executedAt} required and
     * the page sorts by it.
     */
    @Query("SELECT e.runId, e.statusId, COUNT(e), MAX(e.executedAt) "
            + "FROM TestExecution e "
            + "WHERE e.projectId = :projectId AND e.caseId IN :caseIds "
            + "GROUP BY e.runId, e.statusId")
    List<Object[]> tallyForCases(
            @Param("projectId") UUID projectId, @Param("caseIds") Collection<UUID> caseIds);

    /**
     * How many evidence rows hang off each of a batch of executions, as {@code [executionId, count]}.
     *
     * <p>Native and grouped, for {@link #findDefectLinks}'s reasons exactly: {@code evidence} has no
     * entity because nothing in the product uploads one — {@code evidence:attach} has no endpoint —
     * and an entity would be a mapped write surface for a table with no writer.
     *
     * <p>It is queried rather than answered as a constant {@code 0} for the other half of that same
     * argument: a count nobody looked up is a claim, not a lookup, and an ingested run could carry
     * evidence this module did not create. An execution with no rows is simply absent from the
     * result, and the caller reads it as zero.
     */
    @Query(value = """
            SELECT e.execution_id, COUNT(*)
            FROM evidence e
            WHERE e.execution_id IN (:executionIds)
            GROUP BY e.execution_id
            """, nativeQuery = true)
    List<Object[]> countEvidence(@Param("executionIds") Collection<UUID> executionIds);

    /**
     * The defects linked to a batch of executions, as {@code [id, executionId, externalKey,
     * provider, url, statusCache]}.
     *
     * <p>Native, and the only native query in this class, because {@code defect_link} has no entity:
     * nothing in the product creates one yet — the {@code IssueTracker} port is M4 — so an entity
     * would be a mapped write surface for a table with no writer. The read is owed all the same,
     * because the contract marks {@code defects} required and answering {@code []} unconditionally
     * would be a claim rather than a lookup: an ingested run could carry links this module did not
     * make.
     *
     * <p>{@code defect_link} is this module's own table (ERD §5), so this is not the cross-module
     * edge the package comment governs.
     */
    @Query(value = """
            SELECT d.id, d.execution_id, d.external_key, d.provider, d.url, d.status_cache
            FROM defect_link d
            WHERE d.execution_id IN (:executionIds)
            ORDER BY d.external_key
            """, nativeQuery = true)
    List<Object[]> findDefectLinks(@Param("executionIds") Collection<UUID> executionIds);
}
