package com.tcms.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion jobs — the idempotency lookup, and one project's history.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional</strong> — see
 * {@link EvidenceRepository}'s header for the whole account. Isolation is established in
 * {@code doBegin}, so a derived method declared here runs with no company set and returns zero rows,
 * silently. On this repository the consequence has a name: the idempotency check would find nothing,
 * so <em>every</em> CI retry would ingest a second time.
 */
@Transactional(readOnly = true)
public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    /**
     * The replay lookup.
     *
     * <p><strong>Keyed on the idempotency key alone, not on the key and the project.</strong>
     * {@code uq_ingestion_idempotency} is {@code (company_id, idempotency_key)} — company-scoped —
     * and the Hibernate company filter supplies the first half, so this method is the constraint
     * read back exactly. Adding {@code projectId} would make the query narrower than the index that
     * refuses the insert: the same key posted to a second project would find nothing here, be
     * treated as new, and then fail on the constraint as a 500 in the middle of a CI build.
     */
    Optional<IngestionJob> findByIdempotencyKey(String idempotencyKey);

    /**
     * One project's jobs, newest first — the history.
     *
     * <p><strong>A constructor projection rather than the entity</strong>, and that is the whole
     * point of the method: {@code raw_payload} is a {@code JSONB} blob that for a 5,000-test build
     * is megabytes, and selecting the entity would pull every one of them across to render a list
     * that shows none. {@link IngestionJobSummary}'s header carries why the list serves a shape with
     * no {@code unmatched} field at all rather than an empty one.
     *
     * <p><strong>{@code id DESC} is a tiebreak, not decoration.</strong> Two CI jobs can post inside
     * one microsecond — a matrix build does it every time — and {@code createdAt} alone would leave
     * their order up to the plan, so the two rows swap between reloads under the cursor of the
     * person reading them. ~~This was
     * {@code findByProjectIdOrderByCreatedAtDesc}~~ → replaced: that method had no caller from the
     * day it was written (§117) and its ordering had this defect.
     */
    @Query("SELECT new com.tcms.execution.IngestionJobRow("
            + "j.id, j.status, j.format, j.runId, j.matchedCount, j.unmatchedCount, j.createdAt) "
            + "FROM IngestionJob j WHERE j.projectId = :projectId "
            + "ORDER BY j.createdAt DESC, j.id DESC")
    List<IngestionJobRow> findSummariesInProject(@Param("projectId") UUID projectId);

    /**
     * One job that is in this project — the second gate, per {@link TestRunRepository}.
     *
     * <p>The row is company-scoped by RLS and by the Hibernate filter, so an id belonging to a
     * <strong>sibling project</strong> in the same company resolves through {@code findById} and
     * would be readable by somebody holding no grant on the project that owns it. The narrowest of
     * the two-segment defects, and every read and delete in this module has had to write it out
     * since {@code CaseDeleteService}.
     */
    @Query("SELECT j FROM IngestionJob j WHERE j.projectId = :projectId AND j.id = :jobId")
    Optional<IngestionJob> findOneInProject(
            @Param("projectId") UUID projectId, @Param("jobId") UUID jobId);
}
