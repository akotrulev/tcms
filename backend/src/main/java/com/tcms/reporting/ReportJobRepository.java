package com.tcms.reporting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job record's reads: claim one, poll one, list a project's, find the job an artifact belongs
 * to, and sweep the ones a dead worker left claimed.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional.</strong> Isolation is
 * established in {@code CompanyAwareTransactionManager#doBegin}, and Spring Data gives a transaction
 * only to the CRUD methods <em>inherited</em> from {@code SimpleJpaRepository} — never to the
 * derived and {@code @Query} methods <em>declared</em> here. Without the annotation those run outside
 * any transaction, {@code app.current_company} is never set, and every read returns <strong>zero
 * rows</strong> while the company context holds the right company: fail-closed and completely
 * silent. {@code RepositoryTransactionTest} derives its list from the live context, so omitting it
 * fails the build rather than emptying a screen.
 *
 * <p>The class-level {@code readOnly} does not stop the worker from writing: it opens its own
 * read-write transaction and these calls join it, per Spring's propagation rules. The claim below is
 * a read; the mutation is on the entity.
 */
@Transactional(readOnly = true)
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    /**
     * The oldest unclaimed job <em>in the open company</em> — the worker's whole query.
     *
     * <p>There is no {@code companyId} parameter and that is the design, not an omission: the row
     * filter is RLS plus the Hibernate company filter, so a caller with no context open sees
     * <strong>zero</strong> rows rather than another company's job. It is also why the worker must
     * loop over companies and open each context in turn — a global "find all pending jobs" is not a
     * faster version of this method, it is a method that always answers empty.
     *
     * <p>No {@code SKIP LOCKED} and no lock hint: v1 runs one worker on one replica (the filesystem
     * store pins the deployment to one), so there is no second claimant to race. A second worker
     * needs this method to change, which is where to start.
     */
    Optional<ReportJob> findFirstByStatusOrderByRequestedAtAsc(ReportJobStatus status);

    /**
     * One job, scoped by project as well as by id.
     *
     * <p>The id alone would serve a job through a path naming a different project — the two-segment
     * defect {@code CaseDeleteService} guards one module over — and here it would do it <em>after</em>
     * a gate that was evaluated against the project in the path, which is the version of that bug
     * that passes an access-control test.
     */
    Optional<ReportJob> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * The job a previous request with this {@code Idempotency-Key} queued, if any ({@code V66},
     * audit E2).
     *
     * <p><strong>The two predicates plus the company are exactly the three columns of
     * {@code report_job_created_idempotency_key_idx}</strong>, and that is a correctness requirement
     * rather than a coincidence: a lookup narrower than the constraint finds nothing and then
     * violates the index on insert — a validated request that 500s. {@code V14}'s header carries the
     * full account. {@code company_id} is supplied by RLS and the Hibernate filter rather than named
     * here, which is the third column.
     *
     * <p>Deliberately not filtered on {@code status}. A retry of a request whose job has since
     * FAILED must still be recognised as that same intent — answering "no such job, here is a new
     * one" would silently re-run a report the caller was only asking about, and the failure is on
     * the job for them to read.
     */
    Optional<ReportJob> findByProjectIdAndCreatedIdempotencyKey(UUID projectId, String key);

    /**
     * The job that produced this artifact, in either slot.
     *
     * <p>This is how the download route proves an artifact belongs to the project in its path:
     * {@code report_artifact} carries no {@code project_id} ({@code V1}), so the job is the only
     * thing that scopes it. Both columns are checked because every completed job writes two
     * artifacts — the requested format and the JSON model — and the model is exactly the one the
     * on-screen view asks for, so scoping only {@code artifact_id} would make the screen's own
     * artifact unreachable while the export worked.
     */
    @Query("""
            select job from ReportJob job
            where job.projectId = :projectId
              and (job.artifactId = :artifactId or job.modelArtifactId = :artifactId)
            """)
    Optional<ReportJob> findByArtifact(
            @Param("projectId") UUID projectId, @Param("artifactId") UUID artifactId);

    /**
     * The project's recent generation requests, newest first.
     *
     * <p>Bounded in the method name rather than by a {@code Pageable}, because the bound is a
     * property of the operation and not a choice a caller makes: {@code GET …/reports/jobs} declares
     * no paging, so a page size that arrived as an argument would be a knob with exactly one
     * setting, and the day somebody passed a different one the contract would not know.
     *
     * <p>Ordered by {@code requestedAt} and not by {@code finishedAt}: a queue is read in the order
     * things were asked for, and half these rows have no finish time at all.
     */
    List<ReportJob> findTop100ByProjectIdOrderByRequestedAtDesc(UUID projectId);

    /**
     * Jobs claimed before {@code cutoff} and still {@code RUNNING} — the reaper's whole query.
     *
     * <p>{@code startedAt} rather than {@code requestedAt}: the age that matters is how long the
     * work has been <em>claimed</em>, and a job that sat in a long queue before a healthy worker
     * took it is not stuck. {@code startedAt} is non-null on every {@code RUNNING} row by
     * construction — {@link ReportJob#claim} writes both in one call — so this needs no null arm.
     */
    List<ReportJob> findByStatusAndStartedAtBefore(ReportJobStatus status, Instant cutoff);
}
