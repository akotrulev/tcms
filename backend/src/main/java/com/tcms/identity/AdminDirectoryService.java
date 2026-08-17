package com.tcms.identity;

import com.tcms.api.TeamRef;
import com.tcms.api.UserRef;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin <em>read</em> surface — who is in this company, how they are organised, what the roles
 * mean, and who holds which of them.
 *
 * <h2>Every list here is a company directory, never an instance one</h2>
 *
 * <p>{@code app_user}, {@code role} and {@code role_permission} are instance-level tables with no
 * RLS ({@link com.tcms.identity} package comment). Listing any of them directly would hand one
 * client facts about another — the disclosure channel {@link AppUser}'s header names. So the
 * people list is driven from {@code company_membership}, which <em>is</em> policed, and
 * {@code app_user} is read only for ids that membership already produced.
 *
 * <h2>The gate is a permission, and a caller who fails it gets an empty array rather than a 403</h2>
 *
 * <p>{@code company:read} is granted to every company member <strong>explicitly</strong> rather
 * than being implicit in membership (roles spec §A3), precisely so that every check is a real
 * permission check. That makes it the right gate here and makes failing it nearly unreachable: a
 * caller who is not a member cannot open a session on the company at all.
 *
 * <p>So the refusal is an empty list, not a 403. The contract declares only {@code 200} on these
 * four paths, and inventing a status a consumer's generated client does not know about is a
 * divergence in the direction that costs the most (handoff §37 finding 2 is the same omission from
 * the other side). Fail-closed and contract-shaped.
 *
 * <h2>What is filtered, and what is not</h2>
 *
 * <p>Project <em>names</em> are filtered to {@link AdminReach#visibleProjects} everywhere they
 * appear — in a team's project chips and beside a grant. A regular member sees only the projects
 * they hold a grant on (roles spec §A1), and the org chart must not be the way around that.
 * Assignments themselves are filtered to what the caller could actually revoke, which is the
 * invariant {@link #listRoleAssignments} states.
 */
@Service
public class AdminDirectoryService {

    /**
     * The permission that means "you are a member of this company". Held by every role; see the
     * header for why it is asked rather than assumed.
     */
    static final String READ_COMPANY = "company:read";

    /** Roles spec §A3, COMPANY scope. Company Admin and Instance Admin only. */
    static final String MANAGE_COMPANY_MEMBERS = "company:members";

    /** Roles spec §A3, PROJECT scope. Test Lead and up, on the project they lead. */
    static final String MANAGE_PROJECT_MEMBERS = "project:members";

    /** Held by all six built-in roles; what makes a granted project visible at all. */
    private static final String READ_PROJECT = "project:read";

    private final PermissionEvaluator permissions;
    private final ProjectRepository projects;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final RoleRepository roles;
    private final RoleAssignmentRepository roleAssignments;

    public AdminDirectoryService(
            PermissionEvaluator permissions,
            ProjectRepository projects,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            TeamRepository teams,
            TeamMembershipRepository teamMemberships,
            RoleRepository roles,
            RoleAssignmentRepository roleAssignments) {

        this.permissions = permissions;
        this.projects = projects;
        this.users = users;
        this.memberships = memberships;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.roles = roles;
        this.roleAssignments = roleAssignments;
    }

    /**
     * How far this caller can see and reach, asked once for the whole request.
     *
     * <p>N evaluations for N projects, and the ceiling is the one {@code ProjectService} states and
     * accepts: a company holds <em>a handful of whole products</em> (plan §1), and every query
     * behind an evaluation is index-covered. The alternative — one clever join that computes
     * visibility directly — is a second authorization-resolution path beside the evaluator, which
     * is the bug class plan §6b names and {@code ProjectService}'s header refuses in the same
     * words. When the evaluator gets its cache, this improves for free.
     *
     * <h2>{@code company:read} is asked at company scope, and used to have to be asked "anywhere"</h2>
     *
     * <p>This gate read {@code companyWide || held on any project} until handoff §43, because
     * {@code evaluateCompanyWide} refused a Test Lead: roles spec §A3 grants {@code company:read} to
     * "every member" and {@code V3} seeds it onto five of the six roles, but four of those five are
     * <strong>PROJECT-scope</strong> and the company-wide term is SYSTEM + COMPANY only. The
     * workaround was honest and did not scale — the next surface gating on a company-scope
     * permission would have written it again, or fail closed by forgetting.
     *
     * <p>{@code V15} repaired the root instead: every member holds an explicit COMPANY-scope grant
     * of {@link Role#COMPANY_MEMBER_ID}, so the company-wide question now answers for every member
     * and this asks it directly. The rejected alternative — letting the evaluator answer "held
     * anywhere in this company" — would have made {@code case:delete} on one project read as a
     * company-wide capability.
     *
     * <p>The two readings are equivalent for every member the product creates and no longer
     * equivalent for a membership row written by hand: that one now answers "no", which is the
     * fail-closed direction and is repaired by re-running {@code provision-company}.
     */
    @Transactional(readOnly = true)
    public AdminReach reachOf(UUID userId) {
        PermissionSet companyTerm = permissions.evaluateCompanyWide(userId);
        boolean companyWide = companyTerm.has(MANAGE_COMPANY_MEMBERS);
        // Asked of the same already-resolved term, so the org-chart gate costs no extra query —
        // and is asked as its own key rather than folded into companyWide. See AdminReach.
        boolean manageTeams = companyTerm.has(TeamWriteService.MANAGE_TEAMS);
        boolean issueTokens = companyTerm.has(ApiTokenService.ISSUE_TOKENS);
        boolean manageIdentityProviders = companyTerm.has(IdentityProviderService.MANAGE_IDP);
        boolean readsCompany = companyTerm.has(READ_COMPANY);

        Set<UUID> visible = new LinkedHashSet<>();
        Set<UUID> staffable = new LinkedHashSet<>();
        for (Project project : projects.findAllByOrderByNameAsc()) {
            PermissionSet held = permissions.evaluate(userId, project.getId());
            if (held.has(READ_PROJECT)) {
                visible.add(project.getId());
            }
            if (held.has(MANAGE_PROJECT_MEMBERS)) {
                staffable.add(project.getId());
            }
        }
        return new AdminReach(
                Set.copyOf(visible), Set.copyOf(staffable),
                companyWide, manageTeams, issueTokens, manageIdentityProviders, readsCompany);
    }

    /**
     * The people in this company — <strong>memberships, resolved to accounts</strong>, never the
     * instance's user table.
     *
     * <h2>Which status is on the wire, and why it is not simply the account's</h2>
     *
     * <p>Two statuses exist for one person: the account is {@code ACTIVE} instance-wide, the
     * membership is {@code INVITED} until they accept. The contract has room for one, and the
     * useful one is the answer to "can this person work here":
     *
     * <ul>
     *   <li>membership {@code REMOVED} — <strong>absent</strong>. Offboarding is one status update
     *       and it must remove the person from the client's directory, or "removed" would mean
     *       "still listed, merely inert".</li>
     *   <li>membership {@code INVITED} — {@code INVITED}, whatever the account says. Someone
     *       already active in another company is still an invitee <em>here</em>, and rendering
     *       them {@code ACTIVE} would state a fact about a different client's tenancy.</li>
     *   <li>otherwise the account's own status, so a deactivated account reads {@code DEACTIVATED}
     *       — the instance-wide off switch is visible where it matters.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<UserView> listUsers(UUID userId) {
        UUID companyId = CompanyContext.require();
        AdminReach reach = reachOf(userId);
        if (!reach.readsCompany()) {
            return List.of();
        }
        boolean administers = reach.companyWide();

        List<CompanyMembership> present = memberships.findByCompanyId(companyId).stream()
                .filter(membership -> membership.getStatus() != MembershipStatus.REMOVED)
                .toList();
        if (present.isEmpty()) {
            return List.of();
        }

        Map<UUID, AppUser> accounts = byId(present.stream().map(CompanyMembership::getUserId).toList());
        Map<UUID, Set<String>> teamNames = teamNamesByUser();

        return present.stream()
                .map(membership -> assemble(
                        membership,
                        accounts.get(membership.getUserId()),
                        administers,
                        teamNames.getOrDefault(membership.getUserId(), Set.of())))
                .sorted((left, right) -> left.displayName().compareToIgnoreCase(right.displayName()))
                .toList();
    }

    /**
     * One person, assembled exactly as {@link #listUsers} assembles the list — for the write path
     * that has just created or found them.
     *
     * <p>Package-private and sharing {@link #assemble}, rather than a second rendering inside
     * {@link MembershipInviteService}: the status rule below is the non-obvious part of this class
     * (a person {@code ACTIVE} elsewhere is {@code INVITED} <em>here</em>), and a copy of it that
     * drifted would have the invitation response and the directory disagreeing about the person
     * they both just named. This is the same reason {@link #view} is shared with the grant writer.
     *
     * @param administers the caller's {@code company:members} verdict, already computed by the
     *                    gate that let them write at all — asked again here would be N more
     *                    permission evaluations for a question just answered
     */
    @Transactional(readOnly = true)
    UserView userViewOf(CompanyMembership membership, boolean administers) {
        AppUser account = users.findById(membership.getUserId()).orElse(null);
        return assemble(
                membership,
                account,
                administers,
                teamNamesByUser().getOrDefault(membership.getUserId(), Set.of()));
    }

    private static UserView assemble(
            CompanyMembership membership,
            AppUser account,
            boolean administers,
            Set<String> teamNames) {

        return new UserView(
                membership.getUserId(),
                membership.getDisplayName(),
                account == null ? null : account.getEmail(),
                membership.getAvatarUrl(),
                statusOf(membership, account),
                // Withheld from an ordinary member — see UserView#lastLoginAt.
                administers && account != null ? account.getLastLoginAt() : null,
                List.copyOf(teamNames));
    }

    /** The org chart, with each team's people and the projects its grants reach. */
    @Transactional(readOnly = true)
    public List<TeamView> listTeams(UUID userId) {
        AdminReach reach = reachOf(userId);
        if (!reach.readsCompany()) {
            return List.of();
        }
        return renderTeams(reach, teams.findAllByOrderByNameAsc());
    }

    /**
     * One team, rendered exactly as the list renders it — what the write endpoints answer with.
     *
     * <p>Through the same {@link #renderTeams} rather than a second assembler, which is the decision
     * {@code CaseRenderer} exists to enforce one module over: a create that answered with a
     * hand-built view would drift from the list the client refetches, and the divergence shows up as
     * a row that changes when the page reloads.
     *
     * @param reach the caller's reach, already computed by the write's own gate — passed in rather
     *              than re-derived, because {@link #reachOf} evaluates every project
     */
    @Transactional(readOnly = true)
    public TeamView teamViewOf(AdminReach reach, Team team) {
        return renderTeams(reach, List.of(team)).get(0);
    }

    private List<TeamView> renderTeams(AdminReach reach, List<Team> subjects) {
        Map<UUID, String> projectNames = projectNames();
        Map<UUID, String> roleNames = new HashMap<>();
        roles.findAll().forEach(role -> roleNames.put(role.getId(), role.getName()));

        Map<UUID, UserRef> people = peopleById();
        Map<UUID, List<TeamMembership>> membersByTeam = new HashMap<>();
        for (TeamMembership membership : teamMemberships.findAll()) {
            membersByTeam.computeIfAbsent(membership.getTeamId(), key -> new ArrayList<>())
                    .add(membership);
        }

        Map<UUID, List<RoleAssignment>> grantsByTeam = new HashMap<>();
        for (RoleAssignment grant : roleAssignments.findAllByOrderByGrantedAtDesc()) {
            if (grant.getTeamId() != null) {
                grantsByTeam.computeIfAbsent(grant.getTeamId(), key -> new ArrayList<>()).add(grant);
            }
        }

        return subjects.stream()
                .map(team -> new TeamView(
                        team.getId(),
                        team.getParentTeamId(),
                        team.getName(),
                        team.getDescription(),
                        refs(membersByTeam.getOrDefault(team.getId(), List.of()).stream()
                                .filter(TeamMembership::isLead)
                                .map(TeamMembership::getUserId)
                                .toList(), people),
                        refs(membersByTeam.getOrDefault(team.getId(), List.of()).stream()
                                .map(TeamMembership::getUserId)
                                .toList(), people),
                        grantsByTeam.getOrDefault(team.getId(), List.of()).stream()
                                // A team can hold a grant on a project this reader has none on.
                                // Rendering its name here would make the org chart a way around
                                // "membership conveys zero project visibility" (roles spec §A1).
                                .filter(grant -> reach.maySee(grant.getProjectId()))
                                .map(grant -> new TeamView.ProjectRole(
                                        grant.getProjectId(),
                                        projectNames.get(grant.getProjectId()),
                                        roleNames.get(grant.getRoleId())))
                                .toList()))
                .toList();
    }

    /**
     * The six built-in roles with their permissions expanded.
     *
     * <p>All six, including {@code Instance Admin} — the contract enumerates it, and an admin
     * choosing a role benefits from seeing the tier above theirs exists. It is not offerable in a
     * grant: {@link RoleGrantService} refuses a SYSTEM-scope role, and the SPA's picker filters on
     * {@link RoleView#scope} rather than on the name.
     */
    @Transactional(readOnly = true)
    public List<RoleView> listRoles(UUID userId) {
        return reachOf(userId).readsCompany() ? roleCatalogue() : List.of();
    }

    /**
     * The same assembly, ungated — for the paths that have already gated and need role names to
     * render a grant with. Calling {@link #listRoles} from there would re-run {@link #reachOf} and
     * its N evaluations to answer a question the caller has already answered.
     *
     * <h2>{@link Role#COMPANY_MEMBER_ID} is filtered out, and it is not cosmetic</h2>
     *
     * <p>The baseline every member holds is a consequence of membership, not a decision about a
     * person: it is granted on joining (V15), refused by {@link RoleGrantService}, and never
     * revocable. Offering it here would put it in the grant picker, where the only two things an
     * administrator could do with it are grant something everybody already has and — through the
     * assignment list — revoke a colleague's sight of the company with no way back short of the
     * operator CLI.
     *
     * <p>It is also what keeps the response inside the contract: {@code openapi.yaml}'s
     * {@code Role.name} is a closed enum, so a name on this wire that is not in it would be a
     * divergence a generated client cannot parse.
     *
     * <h2>{@link Role#AGENT_ID} is NOT filtered out, and the filter must not become
     * {@code WHERE assignable}</h2>
     *
     * <p>V40 gave {@code role} an {@code assignable} flag and set it false for two rows, which
     * makes this predicate look like a constant that wants replacing. It does not. Both screens
     * that consume this endpoint read the same list — the grant pickers <em>and</em> the
     * token-issue form — and the token form is the only place Agent is ever chosen. Narrowing this
     * to the assignable roles hides it there, so the role becomes unissuable through the product
     * and the seed row is inert.
     *
     * <p>The assignment UI filters on {@code assignable} client-side instead, which is affordance
     * rather than security: {@link RoleGrantService} is the enforcement and refuses the grant
     * whatever any client offers. Precedent is one file over —
     * {@code IssueTokenDialog} filters SYSTEM scope client-side for the same reason.
     */
    List<RoleView> roleCatalogue() {
        Map<UUID, List<String>> permissionsByRole = new HashMap<>();
        for (RoleRepository.RolePermission row : roles.findAllRolePermissions()) {
            permissionsByRole.computeIfAbsent(row.getRoleId(), key -> new ArrayList<>())
                    .add(row.getPermissionKey());
        }
        return roles.findAllByOrderByNameAsc().stream()
                .filter(role -> !Role.COMPANY_MEMBER_ID.equals(role.getId()))
                .map(role -> new RoleView(
                        role.getId(),
                        role.getName(),
                        role.getScope(),
                        role.isBuiltIn(),
                        role.isAssignable(),
                        role.getDescription(),
                        List.copyOf(permissionsByRole.getOrDefault(role.getId(), List.of()))))
                .toList();
    }

    /**
     * The grants in this company that this caller may administer.
     *
     * <h2>The list is exactly the set of grants the caller could revoke, and that is the point</h2>
     *
     * <p>A Test Lead sees the grants on the project they lead and nothing else — not the company's
     * other projects' staffing, and not the company-wide grants that made someone a Company Admin.
     * A Company Admin sees all of it.
     *
     * <p>Filtering by {@link AdminReach#mayStaff} rather than by visibility is what makes the
     * screen honest: every row it renders carries a Revoke the server will accept, and every grant
     * the server would accept a revoke for is on the screen. The alternative — list what you can
     * see, gate what you can change — is the arrangement where a Test Lead reads the whole
     * company's org structure off an admin page they were given for one project.
     *
     * @param projectId narrows to one project's grants. An unreachable or non-existent id yields an
     *                  empty list rather than a 404, both because the contract declares only
     *                  {@code 200} here and because an empty list is what "you administer nothing
     *                  there" honestly is (§6t: the two must be indistinguishable).
     */
    @Transactional(readOnly = true)
    public List<RoleAssignmentView> listRoleAssignments(UUID userId, UUID projectId) {
        AdminReach reach = reachOf(userId);
        if (!reach.readsCompany()) {
            return List.of();
        }
        Map<UUID, String> projectNames = projectNames();
        Map<UUID, RoleView> roleViews = new HashMap<>();
        roleCatalogue().forEach(role -> roleViews.put(role.id(), role));
        Map<UUID, UserRef> people = peopleById();
        Map<UUID, String> teamNames = new HashMap<>();
        teams.findAllByOrderByNameAsc().forEach(team -> teamNames.put(team.getId(), team.getName()));

        return roleAssignments.findAllByOrderByGrantedAtDesc().stream()
                .filter(grant -> projectId == null || projectId.equals(grant.getProjectId()))
                // The baseline every member holds is not an administrable grant — see
                // roleCatalogue(). Rendering it would offer a Revoke that removes a colleague's
                // sight of the company, and would put a role the contract does not enumerate on
                // the wire inside RoleAssignment.role.
                .filter(grant -> !Role.COMPANY_MEMBER_ID.equals(grant.getRoleId()))
                .filter(grant -> reach.mayStaff(grant.getProjectId()))
                .map(grant -> view(grant, reach, projectNames, roleViews, people, teamNames))
                .toList();
    }

    /** Assembles one grant for the wire. Package-private so the write path renders identically. */
    RoleAssignmentView view(
            RoleAssignment grant,
            AdminReach reach,
            Map<UUID, String> projectNames,
            Map<UUID, RoleView> roleViews,
            Map<UUID, UserRef> people,
            Map<UUID, String> teamNames) {

        return new RoleAssignmentView(
                grant.getId(),
                roleViews.get(grant.getRoleId()),
                grant.getUserId() == null ? null : people.get(grant.getUserId()),
                grant.getTeamId() == null
                        ? null
                        : new TeamRef(
                                grant.getTeamId(), teamNames.get(grant.getTeamId())),
                grant.getProjectId(),
                reach.maySee(grant.getProjectId())
                        ? projectNames.get(grant.getProjectId())
                        : null,
                grant.getGrantedAt());
    }

    /** One grant, rendered for the caller who just made it — the write path's view assembler. */
    @Transactional(readOnly = true)
    RoleAssignmentView viewOf(RoleAssignment grant, UUID userId) {
        Map<UUID, RoleView> roleViews = new HashMap<>();
        roleCatalogue().forEach(role -> roleViews.put(role.id(), role));
        Map<UUID, String> teamNames = new HashMap<>();
        teams.findAllByOrderByNameAsc().forEach(team -> teamNames.put(team.getId(), team.getName()));
        return view(grant, reachOf(userId), projectNames(), roleViews, peopleById(), teamNames);
    }

    // =====================================================================
    // assembly helpers
    // =====================================================================

    /**
     * Everyone in this company, keyed by id — display names from the <em>membership</em>, emails
     * from the account.
     *
     * <p>{@link CompanyMembership#getDisplayName} and not {@link AppUser#getCanonicalName}: §6u, and
     * the reason {@link UserRef} says so at length.
     *
     * <p>Package-private rather than private since §54, so {@link ProjectMembershipService} can
     * render its list from the same assembly. The alternative was a second implementation of the
     * §6u display-name rule in the same module — ~~and the codebase already keeps a running tally
     * of forced copies of this shape ({@code work.UserRefView}); adding one that was not forced
     * would make that tally meaningless.~~ → <strong>§147: there is no tally and no copies.</strong>
     * The shape is {@link UserRef} in {@code com.tcms.api}, the six copies are gone, and a seventh
     * fails {@code ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape} instead of being
     * counted by hand. What this paragraph is still about is the <em>rule</em> rather than the
     * shape: {@link IdentityPeopleDirectory} is the one place §6u is applied, and no architecture
     * test can see a second implementation of it.
     */
    Map<UUID, UserRef> peopleById() {
        UUID companyId = CompanyContext.require();
        List<CompanyMembership> present = memberships.findByCompanyId(companyId);
        if (present.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AppUser> accounts = byId(present.stream().map(CompanyMembership::getUserId).toList());

        Map<UUID, UserRef> people = new HashMap<>();
        for (CompanyMembership membership : present) {
            AppUser account = accounts.get(membership.getUserId());
            people.put(membership.getUserId(), new UserRef(
                    membership.getUserId(),
                    membership.getDisplayName(),
                    account == null ? null : account.getEmail(),
                    membership.getAvatarUrl()));
        }
        return people;
    }

    private Map<UUID, AppUser> byId(Collection<UUID> userIds) {
        Map<UUID, AppUser> accounts = new HashMap<>();
        // The ids come from company_membership rows RLS already scoped, which is the precondition
        // findByIdIn states — never from a request.
        users.findByIdIn(userIds).forEach(account -> accounts.put(account.getId(), account));
        return accounts;
    }

    private Map<UUID, String> projectNames() {
        Map<UUID, String> names = new HashMap<>();
        projects.findAllByOrderByNameAsc().forEach(project -> names.put(project.getId(), project.getName()));
        return names;
    }

    /** Team names per user, sorted, so the directory's Teams column is stable between requests. */
    private Map<UUID, Set<String>> teamNamesByUser() {
        Map<UUID, String> names = new HashMap<>();
        teams.findAllByOrderByNameAsc().forEach(team -> names.put(team.getId(), team.getName()));

        Map<UUID, Set<String>> byUser = new HashMap<>();
        for (TeamMembership membership : teamMemberships.findAll()) {
            String name = names.get(membership.getTeamId());
            if (name != null) {
                byUser.computeIfAbsent(membership.getUserId(), key -> new TreeSet<>()).add(name);
            }
        }
        return byUser;
    }

    private static List<UserRef> refs(List<UUID> userIds, Map<UUID, UserRef> people) {
        return userIds.stream()
                .map(people::get)
                .filter(person -> person != null)
                .sorted((left, right) -> left.displayName().compareToIgnoreCase(right.displayName()))
                .toList();
    }

    /**
     * Which of the two statuses a person has is on the wire — see {@link #listUsers}, where the
     * three arms are argued.
     *
     * <p>Package-private rather than private since the org chart, for {@link #peopleById}'s reason:
     * a second implementation of a rule this specific, in the same module, is the copy nothing
     * compares. Two people can differ on whether an invitee of this company who is active in
     * another reads {@code INVITED} or {@code ACTIVE}, and the answer must not depend on which
     * screen asked.
     */
    static UserStatus statusOf(CompanyMembership membership, AppUser account) {
        if (membership.getStatus() == MembershipStatus.INVITED) {
            return UserStatus.INVITED;
        }
        return account == null ? UserStatus.DEACTIVATED : account.getStatus();
    }
}
