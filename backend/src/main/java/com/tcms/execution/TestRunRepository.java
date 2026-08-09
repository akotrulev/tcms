package com.tcms.execution;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * The keys of a set of live runs, so a list naming runs resolves them in one query, not N —
     * {@link #findPlanKeys}' shape, for the ingestion history.
     *
     * <p>{@code projectId} is repeated here as in every query on this repository, and
     * {@code deletedAt IS NULL} is deliberate rather than copied: a job that opened a run somebody
     * later deleted answers a null key beside a non-null {@code runId}, which is what
     * {@code IngestionJobSummary} tells the two apart by. Serving the key of a deleted run would
     * offer a link to a screen that 404s.
     */
    @Query("SELECT r.id, r.key FROM TestRun r WHERE r.projectId = :projectId "
            + "AND r.id IN :runIds AND r.deletedAt IS NULL")
    List<Object[]> findRunKeys(
            @Param("projectId") UUID projectId, @Param("runIds") Collection<UUID> runIds);

    /**
     * Starts the run, if it has not started — <strong>the write §72 put in an entity mutator and
     * §155 moved into a predicate</strong>.
     *
     * <p>Called on every result recorded and every ingested report, which makes it the hottest
     * write in the product, and it is the one write on this table that is not the caller's whole
     * intent: the tester asked to record a verdict, and the run starting is a consequence.
     *
     * <h2>Why the guard is in the WHERE clause and not in Java</h2>
     *
     * <p>{@code TestRun.begin} asked {@code status == NOT_STARTED} in the calling transaction and
     * then wrote the whole row. Both halves are the problem. The <em>question</em> was answered
     * against a snapshot taken before a lead's {@code PUT /status} committed, so it said yes on a
     * run that had since been completed; the <em>write</em> then set {@code status} back to
     * {@code IN_PROGRESS} and cleared {@code completed_at}, discarding the closure with no error on
     * either request. Here Postgres re-evaluates the predicate against the current row under the
     * lock this statement takes, so a run somebody closed in the meantime matches nothing and the
     * start does not happen — which is also the right domain answer, not merely a safe one.
     *
     * <p>It is idempotent for the same reason, and that is what makes it safe to call
     * unconditionally: a second result finds {@code IN_PROGRESS} and updates zero rows. Two testers
     * whose first results are in flight at once <strong>do not collide</strong> — the second
     * statement waits for the first to commit, re-reads, and reports nothing done. Under a
     * {@code @Version} on this row those two would instead be a 409 and a rolled-back verdict, which
     * is why this row carries no such column (see {@link TestRun}'s header).
     *
     * <p>{@code now} is the <em>result's</em> instant rather than a fresh read of the clock: they
     * are the same event, and two instants a few microseconds apart would let a run's start sort
     * after the first result recorded in it. {@code actorId} is null on the ingestion path, where
     * nobody performed the start.
     *
     * <p>{@code clearAutomatically} is deliberately not set, for {@code TestCaseDraftRepository}'s
     * reason: the calling transaction holds this run as a managed entity and detaching it
     * mid-write is action at a distance. The consequence is that the in-memory copy keeps the
     * status it was loaded with — no caller re-reads it, and the two that exist use only the id and
     * the key.
     *
     * @return 1 when this call is what started the run, 0 when it was already started, already
     *         closed, deleted, or in another project — the four cases the caller must treat
     *         identically, because in every one of them somebody else's fact is the one on the row
     */
    @Modifying
    @Transactional
    @Query("UPDATE TestRun r "
            + "SET r.status = com.tcms.execution.RunStatus.IN_PROGRESS, r.startedAt = :now, "
            + "r.updatedAt = :now, r.updatedBy = :actorId "
            + "WHERE r.id = :runId AND r.projectId = :projectId AND r.deletedAt IS NULL "
            + "AND r.status = com.tcms.execution.RunStatus.NOT_STARTED")
    int startIfNotStarted(
            @Param("projectId") UUID projectId,
            @Param("runId") UUID runId,
            @Param("actorId") UUID actorId,
            @Param("now") Instant now);

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
