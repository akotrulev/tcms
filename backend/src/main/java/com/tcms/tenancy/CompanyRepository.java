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
}
