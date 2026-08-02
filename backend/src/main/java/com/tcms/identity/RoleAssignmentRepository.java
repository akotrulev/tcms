package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants in the active company, plus the three projections {@link PermissionEvaluator}
 * builds its union from.
 *
 * <h2>Why the permission lookups are native queries</h2>
 *
 * <p>{@code role_permission} has no entity — it is a pure join table over reference data
 * and modelling it as a {@code @ManyToMany} would buy a collection nobody navigates.
 * These queries also want <em>only</em> the permission key and its qualifier, so a projection is
 * cheaper than loading grant rows and walking them in Java.
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
     * PROJECT-scope permissions held on {@code projectId}, whether granted directly
     * to the user or to any team they belong to — <strong>each with its qualifier</strong>.
     *
     * <p>The team subquery is itself constrained to {@code companyId} — without it, it
     * would be the one unfiltered step in the union.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key AS permissionKey,
                            rp.qualifier      AS qualifier
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
    List<HeldPermissionRow> findProjectScopedHeldPermissions(
            @Param("companyId") UUID companyId,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId);

    /**
     * Permissions from a company-wide grant of a <strong>COMPANY-scope role</strong> —
     * {@code Company Admin} and the {@code Company Member} baseline.
     *
     * <h2>Why this is split from {@link #findInheritedProjectRoleHeldPermissions}</h2>
     *
     * <p>Both are {@code project_id IS NULL}, and until roles became inheritable that was the whole
     * definition of "company-scope" — one query sufficed because {@code scopeError} refused to grant
     * any other kind of role that way. It no longer does, so {@code project_id IS NULL} now covers
     * two populations with <strong>different override semantics</strong>:
     *
     * <ul>
     *   <li>a COMPANY-scope role is authority over the company itself and is <em>never</em>
     *       overridden by a project grant — otherwise anyone holding {@code project:members} could
     *       neutralise the company's administrator by granting them Viewer on a project;</li>
     *   <li>a PROJECT-scope role granted company-wide is a <em>default for every project</em>, and
     *       is exactly what a project-level grant replaces.</li>
     * </ul>
     *
     * <p>Keying the split on {@code role.scope} rather than on a role id list is the same rule
     * {@code IdentityProjectAccess#projectRoleNames} follows and V12's qualifier column states:
     * derive it from the catalogue, never from a hard-coded ranking of names, or the first custom
     * role is mis-answered.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key AS permissionKey,
                            rp.qualifier      AS qualifier
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'COMPANY'
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<HeldPermissionRow> findCompanyRoleHeldPermissions(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId);

    /**
     * Permissions <strong>inherited</strong> by every project — a PROJECT-scope role granted
     * with no project, meaning "this role, everywhere in this company".
     *
     * <p>Deliberately **not** unioned with a project's own grants: see
     * {@link #hasProjectScopedAssignment}. These apply only where the project has said nothing.
     *
     * <p>The qualifier matters here specifically: a company-wide Test Engineer inherits
     * {@code artifact:edit} with its {@code PLAN_SCOPE} qualifier into every project, and a project
     * grant of Test Lead must <em>replace</em> that with {@code ALL} rather than merge to it.
     */
    @Query(nativeQuery = true, value = """
            SELECT DISTINCT rp.permission_key AS permissionKey,
                            rp.qualifier      AS qualifier
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'PROJECT'
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<HeldPermissionRow> findInheritedProjectRoleHeldPermissions(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId);

    /**
     * Whether this caller has <strong>any</strong> grant naming this project — the switch that
     * decides whether the inherited company-wide role applies here.
     *
     * <h2>Existence of an assignment, not "does it grant anything"</h2>
     *
     * <p>The override triggers on the <em>assignment</em>, never on the keys it happens to confer.
     * A project grant of a role that grants little — Viewer over a company-wide Test Lead — is the
     * whole point of the feature, and a check written as "are there project keys" would union the
     * two instead of replacing, silently restoring the permissions the grant was made to remove. A
     * role with no permissions at all would likewise fail to override, which is the same defect one
     * degree further along.
     *
     * <p>Reads {@code role_assignment} alone for that reason — it never joins {@code role_permission},
     * so nothing about the catalogue can change the answer.
     */
    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1
                             FROM role_assignment ra
                            WHERE ra.company_id = :companyId
                              AND ra.project_id = :projectId
                              AND (ra.user_id = :userId
                                   OR ra.team_id IN (SELECT tm.team_id
                                                       FROM team_membership tm
                                                      WHERE tm.user_id = :userId
                                                        AND tm.company_id = :companyId)))
            """)
    boolean hasProjectScopedAssignment(
            @Param("companyId") UUID companyId,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId);

    // The three per-key qualifier queries that used to sit here — findProjectScopedQualifiers,
    // findCompanyRoleQualifiers, findInheritedProjectRoleQualifiers — were DELETED in §65, together
    // with the SYSTEM one on InstanceRoleAssignmentRepository. They asked the same three terms as
    // the projections above, for one key, so the evaluator resolved the replace-don't-union rule
    // TWICE and the two copies had to agree forever. Folding the qualifier into the projections
    // (see HeldPermissionRow) made them dead. Do not reintroduce one to make a gate "cheaper": a
    // second reading of which grant wins is the defect this deletion closes, and the projections
    // are the same index-covered joins with one more column.

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

    /**
     * The caller's <strong>inherited</strong> project-role grants — PROJECT-scope roles held with no
     * project, so they name the role that applies on every project that has no grant of its own.
     *
     * <p>Ranked by the sibling query's rule and for its reason: two inherited grants (directly and
     * through a team) can both apply, the contract has room for one string, and "which is stronger"
     * must be derived from the catalogue rather than from a hard-coded ordering of names.
     *
     * <p>Without this, {@code Project.myRole} would read {@code null} on every inherited project
     * while the caller demonstrably has a role there — the contract defines the field as "the
     * caller's role on this project", and an inherited role is their role on it.
     */
    @Query(nativeQuery = true, value = """
            SELECT r.name AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'PROJECT'
               AND (ra.user_id = :userId
                    OR ra.team_id IN (SELECT tm.team_id
                                        FROM team_membership tm
                                       WHERE tm.user_id = :userId
                                         AND tm.company_id = :companyId))
            """)
    List<InheritedRoleName> findInheritedRoleNames(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId);

    /** An inherited grant, reduced to what a screen shows plus how to rank it. */
    interface InheritedRoleName {

        String getRoleName();

        long getPermissionCount();
    }

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

    /**
     * Every grant in the active company, newest first — the admin screen's list before it is
     * narrowed to what the caller may administer.
     *
     * <p>A query method, so the Hibernate filter applies on top of RLS. Unpaginated, matching
     * {@code GET /role-assignments}'s bare-array contract and the ceiling {@code ProjectService}
     * states: a company holds a handful of whole products and tens of people, not thousands of
     * grants. It becomes a pagination question the day that stops being true, and the contract
     * has to change first.
     */
    List<RoleAssignment> findAllByOrderByGrantedAtDesc();

    /**
     * One grant by id, through a query rather than {@code findById}, <strong>so the company filter
     * applies in the application layer too</strong>.
     *
     * <p>{@code findById} goes through {@code EntityManager.find}, which Hibernate does
     * <em>not</em> apply {@code @Filter} to. RLS would still refuse another company's row, so the
     * difference is defence in depth rather than a live leak — and this is the revoke path, where
     * the two layers agreeing is what makes a foreign id answer "no such grant" rather than
     * depending on the database alone. {@code ProjectRepository.findScopedById} exists for the
     * same reason and says so in the same words.
     */
    Optional<RoleAssignment> findScopedById(UUID id);

    /**
     * The identical grant, if it is already there — the four lookups behind the contract's
     * "re-granting an identical assignment returns the existing one with 200".
     *
     * <p>Four methods rather than one with nullable parameters, for the reason this class's header
     * gives about the permission queries: a bound NULL that <em>happens</em> to compare correctly is
     * the reasoning that produced the unreachable-SYSTEM-grant defect. {@code ProjectIdIsNull} is
     * the COMPANY-scope test, per {@link RoleAssignment}'s encoding of scope in the column.
     *
     * <p>They are the <em>fast</em> path only. {@code uq_role_assignment_user} and
     * {@code uq_role_assignment_team} (V13) are what actually make a duplicate impossible — two
     * admins clicking Grant at the same instant both read nothing here, and one of them loses at
     * the index. {@code AdminController.onDuplicateGrant} turns that loss back into the 200 this
     * lookup would have produced a moment earlier.
     */
    Optional<RoleAssignment> findByCompanyIdAndRoleIdAndUserIdAndProjectId(
            UUID companyId, UUID roleId, UUID userId, UUID projectId);

    Optional<RoleAssignment> findByCompanyIdAndRoleIdAndUserIdAndProjectIdIsNull(
            UUID companyId, UUID roleId, UUID userId);

    Optional<RoleAssignment> findByCompanyIdAndRoleIdAndTeamIdAndProjectId(
            UUID companyId, UUID roleId, UUID teamId, UUID projectId);

    Optional<RoleAssignment> findByCompanyIdAndRoleIdAndTeamIdAndProjectIdIsNull(
            UUID companyId, UUID roleId, UUID teamId);
}
