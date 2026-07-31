package com.tcms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
// A repository interface must be @Transactional or its QUERY METHODS run with no
// transaction at all — and therefore with no app.current_company, which under RLS means
// zero rows. Inherited CRUD (findById, count, save) is covered by SimpleJpaRepository's
// own annotation; derived and @Query methods are NOT. See RepositoryTransactionTest,
// which both reproduces the defect and fails the build on a repository missing this.
@Transactional(readOnly = true)
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

    /**
     * The qualifiers on every PROJECT-scope grant of <em>one</em> permission — {@code 'ALL'},
     * {@code 'OWN'}, or no rows at all when the caller does not hold it here.
     *
     * <h2>Why the qualifier is not folded into the two key projections above</h2>
     *
     * <p>{@link #findProjectScopedPermissionKeys} feeds a {@link PermissionSet}, which is a set of
     * keys and must stay one: every consumer — the {@code /me/permissions} response, the visibility
     * gate, every {@code has(…)} call — asks "is it held", and a key that arrived qualified is still
     * held. Returning pairs there would push the qualifier into all of them, where all but one would
     * discard it.
     *
     * <p>So the qualifier is asked for separately, by the one gate that acts on a row, and about the
     * one permission it is gating. That also keeps this query cheap: it is the same index-covered
     * join with one more predicate, run once per authorship-gated write rather than on every check.
     *
     * <p>The company predicate and the team branch are the sibling queries' — see the class header
     * for why the explicit {@code company_id} stays even though RLS also enforces it.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.qualifier
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id = :projectId
               AND rp.permission_key = :permissionKey
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<String> findProjectScopedQualifiers(
            @Param("companyId") UUID companyId,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId,
            @Param("permissionKey") String permissionKey);

    /**
     * The same, for COMPANY-scope grants — {@code project_id IS NULL}, applying to every project in
     * the company. This is the term that makes a Company Admin's {@code case:delete} unqualified on
     * a project where they also hold a Test Engineer grant.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.qualifier
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND rp.permission_key = :permissionKey
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<String> findCompanyScopedQualifiers(
            @Param("companyId") UUID companyId,
            @Param("userId") UUID userId,
            @Param("permissionKey") String permissionKey);

    /**
     * The caller's <strong>PROJECT-scope</strong> role names across the whole company, in one
     * query — what the contract's {@code Project.myRole} renders.
     *
     * <p>Display only. It is <em>not</em> an authorization input, and nothing may branch on
     * the string it returns: code checks permissions, never role names (plan §6b), so that a
     * custom-role designer stays additive. This exists because a person wants to be told
     * "Test Lead", not handed 31 permission keys.
     *
     * <h2>Why COMPANY-scope grants are excluded</h2>
     *
     * <p>{@code project_id IS NOT NULL} is the whole point. The contract defines {@code myRole}
     * as "the caller's role on this project, or null when they hold no project grant", so a
     * Company Admin — who reaches every project through a grant carrying no project id —
     * correctly gets {@code null}. Folding their company role in would label every project
     * "Company Admin", which is a claim about the company rather than about the project.
     *
     * <h2>Why the permission count comes back too</h2>
     *
     * <p>A user can hold more than one project-scope grant on one project: directly and
     * through a team, or through two teams. The contract has room for one string, so one has
     * to win, and "which role is stronger" must be <strong>derived</strong> rather than
     * ranked by name — a hard-coded ordering silently mis-sorts the first custom role anyone
     * adds, and handoff §6 lesson 10 is the same mistake in a different costume. The count of
     * permissions the role actually confers is the ordering, and it comes from the same
     * {@code role_permission} table authorization reads.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.project_id AS projectId,
                   r.name        AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NOT NULL
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<ProjectRoleName> findProjectRoleNames(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId);

    /** One project-scope grant, reduced to what a screen shows plus how to rank it. */
    interface ProjectRoleName {

        UUID getProjectId();

        String getRoleName();

        long getPermissionCount();
    }

    /** Company-filtered by the active context; used to list a user's grants for admin screens. */
    List<RoleAssignment> findByUserId(UUID userId);

    /**
     * Does this user already hold {@code roleId} across the whole company?
     *
     * <p>{@code ProjectIdIsNull} is the COMPANY-scope test, per {@link RoleAssignment}'s
     * encoding of scope in the column rather than in a discriminator. Provisioning needs
     * it to stay idempotent: re-running the CLI must not stack a second identical grant,
     * which would read in an admin screen as though the operator had granted twice.
     */
    boolean existsByCompanyIdAndRoleIdAndUserIdAndProjectIdIsNull(
            UUID companyId, UUID roleId, UUID userId);

    List<RoleAssignment> findByProjectId(UUID projectId);
}
