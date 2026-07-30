package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Grants in the active company, plus the two projections {@link PermissionEvaluator}
 * builds its union from.
 *
 * <h2>Why the permission lookups are native queries</h2>
 *
 * <p>{@code role_permission} has no entity — it is a pure join table over reference data
 * and modelling it as a {@code @ManyToMany} would buy a collection nobody navigates.
 * These queries also want <em>only</em> the permission key, so a projection is cheaper
 * than loading grant rows and walking them in Java.
 *
 * <p><strong>A Hibernate filter does not apply to a native query — RLS does.</strong>
 * That is why both queries also carry an explicit {@code company_id = :companyId}
 * predicate: the database policy is the guarantee, the predicate is the intent, and the
 * two agreeing is what makes a mismatch (a caller passing a company other than the open
 * context) return nothing instead of something. {@link PermissionEvaluator} closes that
 * gap at the source by taking the company from {@code CompanyContext} rather than a
 * parameter.
 *
 * <p><strong>Do not delete that predicate because no test fails without it.</strong>
 * Mutation-testing this class confirmed that removing {@code company_id = :companyId}
 * from the PROJECT query breaks <em>nothing</em> — RLS independently scopes
 * {@code role_assignment} to the active company, so the second layer holds when the first
 * is removed. That is defence in depth working as designed, not dead code. It becomes
 * load-bearing the moment a deployment takes V1 up on its offer to disable RLS "if the
 * planner cost is measured and unacceptable", and no test covers that configuration
 * today.
 *
 * <h2>Why two methods rather than one nullable projectId</h2>
 *
 * <p>A single query with {@code (ra.project_id IS NULL OR ra.project_id = :projectId)}
 * and a bound NULL <em>happens</em> to work, because {@code NULL = NULL} is never true
 * and the COMPANY branch survives on its own. Relying on that is exactly the reasoning
 * that produced the unreachable-SYSTEM-grant defect (handoff §11). Two explicit queries
 * cost four lines and depend on no NULL semantics at all.
 */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    /**
     * PROJECT-scope permission keys held on {@code projectId}, whether granted directly
     * to the user or to any team they belong to.
     *
     * <p>The team subquery is itself constrained to {@code companyId} — without it, it
     * would be the one unfiltered step in the union.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id = :projectId
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<String> findProjectScopedPermissionKeys(
            @Param("companyId") UUID companyId,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId);

    /**
     * COMPANY-scope permission keys — {@code project_id IS NULL}, so they apply to every
     * project in the company, present and future. This is what makes Company Admin work
     * without a per-project grant.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<String> findCompanyScopedPermissionKeys(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId);

    /** Company-filtered by the active context; used to list a user's grants for admin screens. */
    List<RoleAssignment> findByUserId(UUID userId);

    List<RoleAssignment> findByProjectId(UUID projectId);
}
