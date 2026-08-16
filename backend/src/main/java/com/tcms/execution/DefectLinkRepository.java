package com.tcms.execution;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of {@code defect_link} — the table's first mapped access, {@code V62} (audit C5).
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional</strong>, for
 * {@link EvidenceRepository}'s reason: isolation is established in
 * {@code CompanyAwareTransactionManager#doBegin} and Spring Data gives a transaction only to the
 * methods inherited from {@code SimpleJpaRepository}, so a derived method declared here would run
 * with {@code app.current_company} unset and return zero rows while the company context holds the
 * right company.
 *
 * <p>There is no read-many method here and that is not an omission. The three screens that render
 * defect links read them in batch through {@link TestExecutionRepository#findDefectLinks}, which
 * stays native — see its javadoc for why the arrival of this writer does not make an entity read the
 * better shape.
 */
@Transactional(readOnly = true)
public interface DefectLinkRepository extends JpaRepository<DefectLink, UUID> {

    /**
     * The replay lookup, keyed on exactly the columns of
     * {@code defect_link_execution_key_idx} — so a request that passes it and then races another
     * retry of the same intent fails on the constraint rather than writing a twin. A lookup naming
     * fewer columns than the index would pass and then violate it, which is a validated request
     * that 500s.
     */
    Optional<DefectLink> findByExecutionIdAndProviderAndExternalKey(
            UUID executionId, String provider, String externalKey);

    /**
     * Scoped by execution as well as by id, for {@link EvidenceRepository#findByIdAndExecutionId}'s
     * reason: the id alone would unlink a row through a path naming a different execution.
     */
    Optional<DefectLink> findByIdAndExecutionId(UUID id, UUID executionId);
}
