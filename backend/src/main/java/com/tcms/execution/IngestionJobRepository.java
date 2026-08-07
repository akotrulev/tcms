package com.tcms.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** One project's jobs, newest first — the ingestion history a screen would list. */
    List<IngestionJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
