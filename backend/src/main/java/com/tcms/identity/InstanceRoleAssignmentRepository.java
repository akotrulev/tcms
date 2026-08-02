package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * SYSTEM-scope grants. Every table this touches
 * ({@code instance_role_assignment}, {@code role_permission}) is instance-level, so
 * these queries work with <em>no company context open</em> — which is required, because
 * the login path resolves SYSTEM grants before any company is active.
 */
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
public interface InstanceRoleAssignmentRepository extends JpaRepository<InstanceRoleAssignment, UUID> {

    /**
     * Permission keys held instance-wide by {@code userId}, <strong>each with its qualifier</strong>.
     *
     * <p>Users only — {@code instance_role_assignment} has no {@code team_id} by design,
     * so there is deliberately no team branch here to mirror
     * {@link RoleAssignmentRepository}. A team is company-owned, and letting a
     * client-administered entity confer operator rights is an escalation path.
     *
     * <p>Instance Admin's grant is derived from the whole catalogue and therefore carries V12's
     * {@code 'ALL'} default on every key — so in practice every qualifier here is {@code ALL}. It is
     * still <em>read</em> rather than assumed: "the operator role is unqualified" is a fact about
     * seed data, and code that hard-codes it stops being true the day a SYSTEM role is defined with
     * a qualified grant, silently and in the direction that grants more.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key AS permissionKey,
                            rp.qualifier      AS qualifier
              FROM instance_role_assignment ira
              JOIN role_permission rp ON rp.role_id = ira.role_id
             WHERE ira.user_id = :userId
            """)
    List<HeldPermissionRow> findSystemHeldPermissions(@Param("userId") UUID userId);

    List<InstanceRoleAssignment> findByUserId(UUID userId);
}
