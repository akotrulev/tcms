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
     * The plan's execution rows that carry <strong>no result</strong> — the checklist §42 writes at
     * run creation, one empty row per case in scope.
     *
     * <h2>Why this exists separately since §101</h2>
     *
     * <p>{@code plan_snapshot} is fed by a statement that joins {@code case_status} inner, so it
     * holds recorded results only. That is the right grain — it is the "current verdict" table — and
     * it means the rollup path has to get the unrecorded half from somewhere.
     *
     * <p><strong>The split is between what grows and what does not.</strong> The recorded half grows
     * without limit: every execution of every case across every run, for as long as the plan is
     * used, which is the scan §6p wrote the rollup to remove. This half is bounded by (cases in
     * scope × configurations) and does not grow with time, so it stays live at no meaningful cost.
     *
     * <p>Dropping it instead would be wrong in exactly one place, and quietly:
     * {@code PlanStatisticsService.byConfiguration} lists only configurations with at least one
     * execution, so a configuration whose run has been opened and not yet recorded would
     * <strong>vanish from the matrix</strong> — §6p's Chrome-versus-Safari failure, from a third
     * direction.
     *
     * <p>Returns {@code [executionId, caseId, runId, runKey, configurationId, runStartedAt]} rather
     * than the entity: nothing about an unrecorded row needs loading as an object, and the columns
     * are exactly the ones {@code Row} carries. Deleted runs are excluded, matching
     * {@link #findForPlan} — a withdrawn run's checklist is not outstanding work.
     */
    @Query("SELECT e.id, e.caseId, r.id, r.key, r.configurationId, r.startedAt "
            + "FROM TestExecution e JOIN TestRun r ON r.id = e.runId "
            + "WHERE e.projectId = :projectId AND r.planId = :planId AND r.deletedAt IS NULL "
            + "AND e.statusId IS NULL")
    List<Object[]> findUnrecordedForPlan(
            @Param("projectId") UUID projectId, @Param("planId") UUID planId);

    /**
     * The distinct cases with a <strong>recorded</strong> result in this plan's runs.
     *
     * <h2>This exists because the rollup path was wrong without it, and wrong in the worst way</h2>
     *
     * <p>{@code PlanStatisticsService} picks its source from the aggregation policy. That alone is
     * not enough: a policy the rollup <em>can</em> serve is not the same claim as a rollup that
     * <em>has</em> been written. A plan whose first results landed since the last scheduler tick —
     * or any plan at all on a deployment where the job has never run — has an empty
     * {@code plan_snapshot}, and reading it would report <strong>every case as not-run</strong>.
     *
     * <p>That is not staleness, which this design accepts and declares through {@code computedAt}.
     * It is a plan that has been executed reporting that nothing has been executed, and it renders
     * as an un-started plan rather than as an error — the shape this repository has paid for in §47,
     * §53, §72 and §81. {@code RollupAgreementTest} caught it, which is the whole reason that class
     * was written before the implementation.
     *
     * <p>Ids only, and one row per case rather than per execution: this is a coverage check, not a
     * second source of results. Deleted runs are excluded, matching {@link #findForPlan} and the
     * rollup's own refresh — otherwise a withdrawn run's cases would make the snapshot look
     * incomplete for ever and the plan page would silently never use the rollup again.
     */
    @Query("SELECT DISTINCT e.caseId FROM TestExecution e JOIN TestRun r ON r.id = e.runId "
            + "WHERE e.projectId = :projectId AND r.planId = :planId AND r.deletedAt IS NULL "
            + "AND e.statusId IS NOT NULL")
    List<UUID> recordedCaseIdsForPlan(
            @Param("projectId") UUID projectId, @Param("planId") UUID planId);

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
     * <p><strong>This header said three false things until §152, and every one of them had a file
     * beside it.</strong> It read: <em>"{@code evidence} has no entity because nothing in the
     * product uploads one — {@code evidence:attach} has no endpoint — and an entity would be a
     * mapped write surface for a table with no writer."</em> {@link Evidence} is an entity,
     * {@code EvidenceController} is the endpoint, {@code EvidenceService} enforces
     * {@code evidence:attach}, and the upload path has had a writer since it shipped. The claim
     * was true when it was written and nothing brought the two back into contact, because a
     * javadoc is the one artifact in this repository no gate reads.
     *
     * <p>Native and grouped for the reason that survives: this is a {@code GROUP BY} over a batch,
     * and the alternative is a count per execution. It is queried rather than answered as a
     * constant {@code 0} because a count nobody looked up is a claim, not a lookup — and that half
     * was never conditional on the table being empty. An execution with no rows is simply absent
     * from the result, and the caller reads it as zero.
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
     * <p><del>Native because {@code defect_link} has no entity:
     * nothing in the product creates one yet — the {@code IssueTracker} port is M4 — so an entity
     * would be a mapped write surface for a table with no writer.</del> → <strong>superseded by
     * {@code V62} (audit C5): there is a writer, and {@link DefectLink} is the entity.</strong> The
     * premise was true when written and stopped being true the moment this table gained
     * {@link DefectLinkService}. The read is owed all the same,
     * because the contract marks {@code defects} required and answering {@code []} unconditionally
     * would be a claim rather than a lookup: an ingested run could carry links this module did not
     * make.
     *
     * <p><strong>It stays native, and for a different reason than the one it was written with.</strong>
     * This is a batch projection over N executions selecting six columns; an entity read would be a
     * fetch through an unmapped edge or one query per row, and the mapping deliberately omits
     * {@code status_cache} — a column this serves and no writer may set ({@code V62}). So the entity
     * exists for the write and this exists for the read, which is the split, not an oversight.
     *
     * <p>{@code company_id} is spelled out beside RLS rather than left to the policy, per §235: the
     * predicate is the intent, the two agreeing makes a mismatch return nothing rather than
     * something, and it is what makes {@code defect_link_execution_key_idx}'s leading column usable
     * here.
     *
     * <p>{@code defect_link} is this module's own table (ERD §5), so this is not the cross-module
     * edge the package comment governs.
     *
     * <p>It said "and the only native query in this class" until §152. {@link #countEvidence}, ten
     * lines above, is native too and was when this was written — a claim contradicted by the
     * method immediately preceding it, which is how little a javadoc costs to be wrong in.
     */
    @Query(value = """
            SELECT d.id, d.execution_id, d.external_key, d.provider, d.url, d.status_cache
            FROM defect_link d
            WHERE d.company_id = :companyId AND d.execution_id IN (:executionIds)
            ORDER BY d.external_key
            """, nativeQuery = true)
    List<Object[]> findDefectLinks(
            @Param("companyId") UUID companyId,
            @Param("executionIds") Collection<UUID> executionIds);
}
