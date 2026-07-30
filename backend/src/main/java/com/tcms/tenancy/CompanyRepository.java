package com.tcms.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Companies are instance-level, so this repository is one of the few that is NOT
 * company-filtered — it is what resolves a company before any context exists.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Used by the permission evaluator to honour suspension in code. The policy is
     * suspend-never-delete (plan §6s), so a suspended company keeps every row it had —
     * nothing else would stop its users working.
     */
    boolean existsByIdAndStatus(UUID id, CompanyStatus status);
}
