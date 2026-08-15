package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface CompanyEmailDomainRepository extends JpaRepository<CompanyEmailDomain, UUID> {

    /**
     * The company's own claims, for the administrator's screen.
     *
     * <p>There is deliberately no {@code findByDomain} here. That is the pre-authentication
     * question, it is asked with no context open, and under RLS this repository would answer it
     * with zero rows — {@code tcms_resolve_providers_for_domain} (V55) answers it instead.
     */
    List<CompanyEmailDomain> findByCompanyIdOrderByDomainAsc(UUID companyId);
}
