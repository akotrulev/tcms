package com.tcms.tracker.imports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Past and in-flight imports.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} on the interface is not decoration.</strong>
 * Spring Data gives a transaction only to the inherited CRUD methods, so a derived method without
 * it runs with no company set and every RLS-protected read here returns zero rows — fail-closed and
 * silent. Every repository interface in this codebase carries it for that reason.
 */
@Transactional(readOnly = true)
public interface ImportRunRepository extends JpaRepository<ImportRun, UUID> {

    /** {@code settings-import.html}'s table — newest first. */
    List<ImportRun> findByProjectIdOrderByStartedAtDesc(UUID projectId);

    /**
     * Whether a completed run already brought this source project into this one (D-m).
     *
     * <p>The predicate is {@code uq_import_run_completed_source}'s, so this answers the same
     * question the constraint would answer four minutes later — as a 409 somebody can read rather
     * than as a violation on the last statement of a long transaction. <strong>One derivation:</strong>
     * {@link ImportPreviewService} computes the preview's {@code ALREADY_IMPORTED} blocker from
     * this same method, and the run refuses from it, so the two cannot disagree.
     */
    boolean existsByProjectIdAndProviderAndSiteUrlAndSourceProjectKeyAndStatus(
            UUID projectId,
            ImportProvider provider,
            String siteUrl,
            String sourceProjectKey,
            ImportRunStatus status);

    /** A replayed {@code Idempotency-Key} answers with this run rather than starting a second. */
    Optional<ImportRun> findByCreatedIdempotencyKey(String createdIdempotencyKey);

    /** {@link ImportReaper}'s sweep — a claim older than the timeout, in the open company. */
    List<ImportRun> findByStatusAndStartedAtBefore(ImportRunStatus status, Instant cutoff);
}
