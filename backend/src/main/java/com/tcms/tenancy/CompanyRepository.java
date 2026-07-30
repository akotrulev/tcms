package com.tcms.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Companies are instance-level, so this repository is one of the few that is NOT
 * company-filtered — it is what resolves a company before any context exists.
 */
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
