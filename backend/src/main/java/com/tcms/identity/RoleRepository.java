package com.tcms.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The six built-in roles — seeded reference data, instance-level, never written from
 * application code. Read-only in practice; {@link Role} deliberately offers no factory.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    java.util.List<Role> findByScope(RoleScope scope);
}
