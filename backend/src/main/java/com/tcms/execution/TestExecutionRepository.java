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
