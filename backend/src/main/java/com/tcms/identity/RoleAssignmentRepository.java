package com.tcms.identity;

import java.util.Collection;
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
     *
     * <p><strong>That example is historical since {@code V52} (§210)</strong>, which de-qualified
     * the last grant — no row carries a qualifier now. The rule it illustrates is unchanged and is
     * why the column is still selected here: replace-don't-union is a property of how scopes
     * compose, not of any particular grant, and it must already be right on the day a qualified
     * grant returns.
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
     * The people in this company who hold {@code permission}, most reachable first.
     *
     * <p>This is what a <em>refusal</em> reads. Design language rule 7 requires a refused screen to
     * name the permission and <strong>a person who can grant it</strong>, and the second half was
     * unanswerable: the only company-wide grant list on the wire is the org chart, and the caller
     * asking has just been told they may not do something. So the question is asked here directly
     * and answered with ids, which {@code GranteeLookupService} turns into display names and
     * nothing else.
     *
     * <h2>Ordered by how reachable the holder is, not by name</h2>
     *
     * <p>A company-wide grant ({@code project_id IS NULL}) comes before a project one. Somebody who
     * holds the key everywhere can grant it wherever the reader is standing; somebody who holds it
     * on one project may not be able to help at all. Sorting alphabetically would put the least
     * useful name first as often as not.
     *
     * <p>Team grants are included by the same subquery every other method here uses — a permission
     * held through a team is held, and a refusal that skipped those would name nobody in a company
     * that grants by team.
     *
     * <p>Users are filtered to live memberships: a REMOVED member is not somebody to ask.
     */
    @Query(nativeQuery = true, value = """
            SELECT u.user_id AS userId
              FROM (
                    SELECT ra.user_id AS user_id,
                           MIN(CASE WHEN ra.project_id IS NULL THEN 0 ELSE 1 END) AS reach
                      FROM role_assignment ra
                      JOIN role_permission rp ON rp.role_id = ra.role_id
                     WHERE ra.company_id = :companyId
                       AND rp.permission_key = :permission
                       AND ra.user_id IS NOT NULL
                     GROUP BY ra.user_id
                     UNION ALL
                    SELECT tm.user_id AS user_id,
                           MIN(CASE WHEN ra.project_id IS NULL THEN 0 ELSE 1 END) AS reach
                      FROM role_assignment ra
                      JOIN role_permission rp ON rp.role_id = ra.role_id
                      JOIN team_membership tm ON tm.team_id = ra.team_id
                                             AND tm.company_id = ra.company_id
                     WHERE ra.company_id = :companyId
                       AND rp.permission_key = :permission
                       AND ra.team_id IS NOT NULL
                     GROUP BY tm.user_id
                   ) u
              JOIN company_membership cm ON cm.user_id = u.user_id
                                        AND cm.company_id = :companyId
                                        AND cm.status <> 'REMOVED'
             GROUP BY u.user_id
             ORDER BY MIN(u.reach), u.user_id
             LIMIT 3
            """)
    List<UUID> findGrantees(
            @Param("companyId") UUID companyId, @Param("permission") String permission);

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
     *
     * <h2>Why it takes a LIST of users, and why the two arms are a UNION rather than an OR</h2>
     *
     * <p>The org chart resolves a whole company's matrix — every member against every project the
     * <em>reader</em> can open. One evaluation per pair is N×M, and neither
     * {@code AdminDirectoryService.reachOf} nor {@code ProjectMembershipService.membersOf} licenses
     * that: they accept an N-shaped cost with a stated ceiling, and N×M is not N. An IN-list makes
     * the whole matrix one query.
     *
     * <p>Which forced the shape change. The single-user form could ask
     * {@code user_id = :userId OR team_id IN (…)} because the person was already known; with a list,
     * a team grant's row carries {@code user_id = NULL} and nothing on it says <em>whose</em> cell it
     * is. So the two ways a grant reaches somebody become two arms that each name the person —
     * {@code ra.user_id} directly, {@code tm.user_id} through the membership join — and that same
     * join is what makes {@code via} and {@code teamId} answerable at all rather than a second query.
     *
     * <p>{@code UNION ALL}, never {@code UNION}: a person holding the same role directly <em>and</em>
     * through a team is two grants, and deduplicating them here would silently pick one arm's
     * {@code via} — the ranking below is where two grants become one string, deliberately, because
     * that is where the rule for choosing is written.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.user_id    AS userId,
                   ra.project_id AS projectId,
                   r.name        AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount,
                   'DIRECT'      AS via,
                   CAST(NULL AS uuid) AS teamId
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NOT NULL
               AND ra.user_id IN (:userIds)
             UNION ALL
            SELECT tm.user_id    AS userId,
                   ra.project_id AS projectId,
                   r.name        AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount,
                   'TEAM'        AS via,
                   ra.team_id    AS teamId
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN team_membership tm ON tm.team_id = ra.team_id
                                     AND tm.company_id = :companyId
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NOT NULL
               AND tm.user_id IN (:userIds)
            """)
    List<ProjectRoleName> findProjectRoleNames(
            @Param("companyId") UUID companyId, @Param("userIds") Collection<UUID> userIds);

    /**
     * One caller's project-scope role names — {@link #findProjectRoleNames(UUID, Collection)} asked
     * about a single person.
     *
     * <p>A {@code default} method rather than a second {@code @Query}, which is the whole point:
     * {@code Project.myRole} and the org chart's cells then cannot be resolved by two queries that
     * drift apart. Spring Data never proxies a default method, so there is exactly one SQL statement
     * behind both entry points.
     */
    default List<ProjectRoleName> findProjectRoleNames(UUID companyId, UUID userId) {
        return findProjectRoleNames(companyId, List.of(userId));
    }

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
     *
     * <p>Generalised to an id list alongside its sibling, and split into the same two named arms for
     * the same reason: with a list, a team grant's row does not say whose inheritance it is.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.user_id AS userId,
                   r.name     AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'PROJECT'
               AND ra.user_id IN (:userIds)
             UNION ALL
            SELECT tm.user_id AS userId,
                   r.name     AS roleName,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = ra.role_id) AS permissionCount
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN team_membership tm ON tm.team_id = ra.team_id
                                     AND tm.company_id = :companyId
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'PROJECT'
               AND tm.user_id IN (:userIds)
            """)
    List<InheritedRoleName> findInheritedRoleNames(
            @Param("companyId") UUID companyId, @Param("userIds") Collection<UUID> userIds);

    /** One caller's inherited role names — the sibling default, and it exists for the same reason. */
    default List<InheritedRoleName> findInheritedRoleNames(UUID companyId, UUID userId) {
        return findInheritedRoleNames(companyId, List.of(userId));
    }

    /**
     * <strong>COMPANY-scope</strong> role names per person — the org chart's {@code companyRoles}.
     *
     * <p>A third projection rather than a widening of either sibling, because it answers a different
     * question and must not be foldable into a cell. Roles spec §A1.1 property 2: a COMPANY-scope
     * role is authority over the company, <em>not</em> a default for its projects, and it is never
     * overridden by a project grant. Smearing "Company Admin" across every column is exactly the
     * misreading that rule exists to prevent, so the fact lives on the person and this query cannot
     * produce a project id to hang it on.
     *
     * <p>{@code role.assignable} is <strong>not</strong> filtered here and the caller drops the
     * baseline instead. Assignability is a question about the grant path (whether
     * {@code RoleGrantService} may create the row), and answering a display question with it is the
     * conversion {@code V40} §2 and {@code V41} §1 name as the one-line "cleanup" that breaks four
     * unrelated sites. What the caller actually wants gone is {@code Company Member}, which every
     * member holds and which would therefore be a badge on every row saying nothing.
     *
     * <p>No permission count and so no ranking: these are <em>all</em> the company roles a person
     * holds, not a winner among them. A person with two is a person with two.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.user_id AS userId,
                   ra.role_id AS roleId,
                   r.name     AS roleName
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'COMPANY'
               AND ra.user_id IN (:userIds)
             UNION ALL
            SELECT tm.user_id AS userId,
                   ra.role_id AS roleId,
                   r.name     AS roleName
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN team_membership tm ON tm.team_id = ra.team_id
                                     AND tm.company_id = :companyId
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND r.scope = 'COMPANY'
               AND tm.user_id IN (:userIds)
            """)
    List<CompanyRoleName> findCompanyRoleNames(
            @Param("companyId") UUID companyId, @Param("userIds") Collection<UUID> userIds);

    /** One COMPANY-scope role a person holds. */
    /**
     * Every (person, project, key) a project-scoped grant confers — direct or through a team — for
     * the org chart's Gaps panel. Rows only; the replace-don't-union rule against inherited grants
     * is applied by the caller, per person and per project, the way {@code PermissionEvaluator}
     * applies it.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.user_id         AS userId,
                   ra.project_id      AS projectId,
                   rp.permission_key  AS permissionKey
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NOT NULL
               AND ra.user_id IN (:userIds)
             UNION
            SELECT tm.user_id         AS userId,
                   ra.project_id      AS projectId,
                   rp.permission_key  AS permissionKey
              FROM role_assignment ra
              JOIN role_permission rp ON rp.role_id = ra.role_id
              JOIN team_membership tm ON tm.team_id = ra.team_id
                                     AND tm.company_id = :companyId
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NOT NULL
               AND tm.user_id IN (:userIds)
            """)
    List<HeldOnProject> findHeldOnProjects(
            @Param("companyId") UUID companyId, @Param("userIds") Collection<UUID> userIds);

    /**
     * Every (person, role scope, key) a company-wide grant confers — a COMPANY-scope role, which is
     * never overridden per project, or a PROJECT-scope role held with no project, which is the
     * inherited default a project-scoped grant replaces.
     */
    @Query(nativeQuery = true, value = """
            SELECT ra.user_id         AS userId,
                   r.scope            AS roleScope,
                   rp.permission_key  AS permissionKey
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN role_permission rp ON rp.role_id = ra.role_id
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND ra.user_id IN (:userIds)
             UNION
            SELECT tm.user_id         AS userId,
                   r.scope            AS roleScope,
                   rp.permission_key  AS permissionKey
              FROM role_assignment ra
              JOIN role r ON r.id = ra.role_id
              JOIN role_permission rp ON rp.role_id = ra.role_id
              JOIN team_membership tm ON tm.team_id = ra.team_id
                                     AND tm.company_id = :companyId
             WHERE ra.company_id = :companyId
               AND ra.project_id IS NULL
               AND tm.user_id IN (:userIds)
            """)
    List<HeldCompanyWide> findHeldCompanyWide(
            @Param("companyId") UUID companyId, @Param("userIds") Collection<UUID> userIds);

    interface HeldOnProject {

        UUID getUserId();

        UUID getProjectId();

        String getPermissionKey();
    }

    interface HeldCompanyWide {

        UUID getUserId();

        String getRoleScope();

        String getPermissionKey();
    }

    interface CompanyRoleName {

        UUID getUserId();

        UUID getRoleId();

        String getRoleName();
    }

    /** An inherited grant, reduced to what a screen shows plus how to rank it. */
    interface InheritedRoleName {

        UUID getUserId();

        String getRoleName();

        long getPermissionCount();
    }

    /** One project-scope grant, reduced to what a screen shows plus how to rank it. */
    interface ProjectRoleName {

        UUID getUserId();

        UUID getProjectId();

        String getRoleName();

        long getPermissionCount();

        /**
         * {@code DIRECT} or {@code TEAM} — which arm of the union produced this row.
         *
         * <p>A string rather than the {@code AssignmentVia} enum because this is a native
         * projection and the value is a SQL literal; the enum is applied one layer up, where a
         * value the query cannot produce is a failure rather than a silent default.
         */
        String getVia();

        /** The team the grant named, or null on a {@code DIRECT} row. */
        UUID getTeamId();
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

    /**
     * How many grants name this team as their subject — the count
     * {@code DELETE /teams/{teamId}} refuses on.
     *
     * <p>The column is {@code ON DELETE CASCADE}, so without this the delete would succeed and take
     * every one of these grants with it, silently. {@link TeamWriteService#delete} says why that is
     * a refusal rather than a cascade the product performs.
     */
    long countByTeamId(UUID teamId);
}
