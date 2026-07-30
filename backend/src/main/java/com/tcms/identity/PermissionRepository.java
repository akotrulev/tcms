package com.tcms.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The permission catalogue — 55 seeded rows, instance-level. Read through
 * {@link PermissionCatalogue} rather than directly, so the key set is loaded once
 * instead of on every authorization check.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface PermissionRepository extends JpaRepository<Permission, String> {

    List<Permission> findByAreaOrderByKeyAsc(String area);
}
