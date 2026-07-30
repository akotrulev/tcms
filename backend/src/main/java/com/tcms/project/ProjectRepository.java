package com.tcms.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every method here is implicitly company-scoped by the Hibernate filter — with one
 * exception worth knowing: {@code findById} resolves through the persistence context
 * and by primary key, which Hibernate filters do NOT apply to. RLS is what covers that
 * gap, which is why the database policy is not optional. {@link #findScopedById} exists
 * for call sites that want the filter to apply too.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOrderByNameAsc();

    Optional<Project> findByKey(String key);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<Project> findScopedById(UUID id);

    boolean existsByKey(String key);
}
