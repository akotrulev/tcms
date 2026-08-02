package com.tcms.execution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Runs, by project, plan and release. {@code @Transactional} per {@link TestPlanRepository}. */
@Transactional(readOnly = true)
public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    /**
     * A project's live runs, newest first, optionally narrowed by plan and release.
     *
     * <p>Both filters are expressed as {@code :value IS NULL OR column = :value} rather than as
     * separate derived methods, because the SPA sends them independently and four query methods
     * would be four places the {@code deletedAt} predicate can be forgotten.
     *
     * <p>A filter that matches nothing answers an empty list, never a 404: "which of my runs belong
     * to that plan" has the honest answer "none" even when the plan id names something in another
     * project, and answering 404 there would turn the query string into an existence oracle.
     */
    @Query("SELECT r FROM TestRun r WHERE r.projectId = :projectId AND r.deletedAt IS NULL "
            + "AND (:planId IS NULL OR r.planId = :planId) "
            + "AND (:releaseVersion IS NULL OR r.releaseVersion = :releaseVersion) "
            + "ORDER BY r.keySeq DESC")
    List<TestRun> findLiveInProject(
            @Param("projectId") UUID projectId,
            @Param("planId") UUID planId,
            @Param("releaseVersion") String releaseVersion);

    /** One live run that is in this project — the second gate, per {@link TestPlanRepository}. */
    @Query("SELECT r FROM TestRun r WHERE r.projectId = :projectId AND r.id = :runId "
            + "AND r.deletedAt IS NULL")
    Optional<TestRun> findLiveInProject(
            @Param("projectId") UUID projectId, @Param("runId") UUID runId);

    /**
     * The run a previous request with this {@code Idempotency-Key} created, if any.
     *
     * <p>The predicates match {@code test_run_created_idempotency_key_idx} exactly, and
     * {@code deletedAt} is deliberately not among them — {@link TestPlanRepository#findByIdempotencyKey}
     * carries both arguments in full.
     */
    @Query("SELECT r FROM TestRun r WHERE r.projectId = :projectId "
            + "AND r.createdIdempotencyKey = :key")
    Optional<TestRun> findByIdempotencyKey(
            @Param("projectId") UUID projectId, @Param("key") String key);

    /** The plan keys a set of runs point at, so a run list resolves them in one query, not N. */
    @Query("SELECT p.id, p.key FROM TestPlan p WHERE p.id IN :planIds")
    List<Object[]> findPlanKeys(@Param("planIds") Collection<UUID> planIds);

    /**
     * How many live runs were created from this plan — what {@link PlanDeleteService} refuses on.
     *
     * <p>{@code deletedAt IS NULL} on the run, so a plan whose only runs were themselves deleted can
     * be deleted too: the refusal exists to protect runs somebody can still open, and a soft-deleted
     * run is not one of those. The {@code projectId} predicate is the same second gate every query
     * in this repository carries — the plan has already been resolved through it, and a count that
     * did not repeat it would be the one query in the path that could see another project's rows.
     */
    @Query("SELECT COUNT(r) FROM TestRun r WHERE r.projectId = :projectId "
            + "AND r.planId = :planId AND r.deletedAt IS NULL")
    long countLiveByPlan(@Param("projectId") UUID projectId, @Param("planId") UUID planId);
}
