package com.tcms.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evidence rows for one execution, or a batch of them.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is not optional.</strong> Isolation is
 * established in {@code CompanyAwareTransactionManager#doBegin}, and Spring Data gives a transaction
 * only to the CRUD methods <em>inherited</em> from {@code SimpleJpaRepository} — never to the
 * derived methods <em>declared</em> here. Without the annotation those run outside any transaction,
 * {@code app.current_company} is never set, and every read returns <strong>zero rows</strong> while
 * the company context holds the right company: fail-closed and completely silent.
 * {@code RepositoryTransactionTest} derives its list from the live context, so omitting it fails the
 * build rather than emptying a screen.
 */
@Transactional(readOnly = true)
public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

    // A findByExecutionIdIn(List<UUID>) sat here until §152, headed "for the run detail: every
    // execution's evidence in one query rather than one query per row". The run detail does not
    // read evidence — it reads COUNTS, through TestExecutionRepository.countEvidence, which is
    // already the single grouped query that header was worried about. The one screen that needs
    // the rows is the single-execution detail, and it asks for one execution's, below. So this was
    // not an N+1 prevented; it was an N+1 that could not occur, guarded against twice.

    /**
     * One execution's evidence, oldest first — the order it was attached in, which is the order the
     * tester was working in and therefore the order the story reads in.
     */
    List<Evidence> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);

    /**
     * Scoped by execution as well as by id: the id alone would serve a row through a path naming a
     * different execution, which is the two-segment defect {@code CaseDeleteService} guards one
     * entity over.
     */
    Optional<Evidence> findByIdAndExecutionId(UUID id, UUID executionId);
}
