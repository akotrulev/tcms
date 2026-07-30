package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * SYSTEM-scope grants. Every table this touches
 * ({@code instance_role_assignment}, {@code role_permission}) is instance-level, so
 * these queries work with <em>no company context open</em> — which is required, because
 * the login path resolves SYSTEM grants before any company is active.
 */
public interface InstanceRoleAssignmentRepository extends JpaRepository<InstanceRoleAssignment, UUID> {

    /**
     * Permission keys held instance-wide by {@code userId}.
     *
     * <p>Users only — {@code instance_role_assignment} has no {@code team_id} by design,
     * so there is deliberately no team branch here to mirror
     * {@link RoleAssignmentRepository}. A team is company-owned, and letting a
     * client-administered entity confer operator rights is an escalation path.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key
              FROM instance_role_assignment ira
              JOIN role_permission rp ON rp.role_id = ira.role_id
             WHERE ira.user_id = :userId
            """)
    List<String> findSystemPermissionKeys(@Param("userId") UUID userId);

    List<InstanceRoleAssignment> findByUserId(UUID userId);
}
