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

    java.util.List<Role> findAllByOrderByNameAsc();

    /**
     * Every {@code (role, permission)} pair in the catalogue, in one query — what
     * {@code GET /roles} expands each role's permission list from.
     *
     * <p>Native, because {@code role_permission} has no entity: it is a pure join table over
     * reference data and modelling it as a {@code @ManyToMany} would buy a collection nobody
     * navigates ({@link RoleAssignmentRepository}'s header makes the same call for the same table).
     *
     * <p>Instance-level and un-policed, like {@code role} itself — the six built-ins are the
     * complete role set in v1 and every company reads the same rows. That is on the ERD §0.1
     * exception list and is the reason no {@code company_id} predicate appears here; a custom-role
     * designer would have to change that, and would have to change this query with it.
     *
     * <p>The whole catalogue rather than one role's: the endpoint renders all six, so this is one
     * round trip instead of six, and it is ~200 rows of static reference data.
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            SELECT role_id AS roleId, permission_key AS permissionKey
              FROM role_permission
             ORDER BY permission_key
            """)
    java.util.List<RolePermission> findAllRolePermissions();

    /** One row of {@code role_permission}, which has no entity of its own. */
    interface RolePermission {

        UUID getRoleId();

        String getPermissionKey();
    }
}
