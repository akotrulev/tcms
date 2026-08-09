package com.tcms.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The permission catalogue — 55 seeded rows, instance-level. Read through
 * {@link PermissionCatalogue} rather than directly, so the key set is loaded once
 * instead of on every authorization check.
 *
 * <p><strong>It declares nothing, and that is the shape of the thing it is.</strong>
 * {@link PermissionCatalogue} calls {@code findAll()} exactly once and holds the result; the
 * catalogue is 55 immutable seeded rows, so there is no second question to ask of this table.
 * A {@code findByArea} lived here until §152 and had never been called — reading the catalogue
 * one area at a time is a screen nobody has built, and the permission matrix that would need it
 * is rendered from {@code GET /roles} instead.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface PermissionRepository extends JpaRepository<Permission, String> {}
