package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
//
// NOT readOnly here, unlike CaseHealthRepository: this repository's owner writes the four authored
// quarantine columns through it. readOnly = true would put Hibernate in a mode where the flush is
// skipped, so the write would silently do nothing on a path where every assertion about the read is
// still true — which is the shape of defect the whole @Transactional rule exists because of.
@Transactional
public interface CaseFlakeRepository extends JpaRepository<CaseFlake, UUID> {

    /**
     * The flake rows for a page of cases, in <strong>one</strong> query.
     *
     * <p>{@link CaseHealthRepository#findForCases}' shape, and absent means the same thing: a case
     * the recompute has not reached has no row, and the caller renders it with no score rather than
     * with a zero.
     */
    @Query("""
            SELECT f FROM CaseFlake f
             WHERE f.caseId IN :caseIds
            """)
    List<CaseFlake> findForCases(@Param("caseIds") Collection<UUID> caseIds);

    /**
     * One case's row, or empty.
     *
     * <p><strong>Not {@code findById}</strong>, and {@link TestCaseRepository#findLiveInProject}
     * states the reason this repository inherits: a primary-key lookup resolves through the
     * persistence context, and Hibernate filters do <em>not</em> apply to that path. RLS would still
     * cover it, but using one layer where both are available is how a layered defence quietly
     * becomes a single point.
     */
    @Query("""
            SELECT f FROM CaseFlake f
             WHERE f.caseId = :caseId
            """)
    Optional<CaseFlake> findScopedById(@Param("caseId") UUID caseId);

    // There was a findQuarantined(Collection<UUID> projectIds) here until §121 phase 3, and it was
    // dead in a way worth recording rather than merely unused: its javadoc named §6l's fourth
    // derived-item generator as its caller, and that caller CANNOT CALL IT. The generator lives in
    // `work`, whose @ApplicationModule allows tenancy/project/identity alone, so ArchUnit refuses
    // the import — the board reads foreign rows through native SQL in work.BoardFacts, exactly as
    // it reads case_health without touching CaseHealthRepository.
    //
    // The lesson is the one this slice was started over, one level up: a seam written for a caller
    // that does not exist yet is a guess about the caller, and this one guessed a module boundary
    // wrong. Adding it back needs a consumer INSIDE this module — a flake worklist screen would be
    // one — and the ORDER BY it carried (flake_score DESC NULLS LAST) belongs to that reader, not
    // to the board, which re-sorts every card by (overdue, priority, id) regardless.
}
