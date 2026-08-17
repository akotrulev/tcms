package com.tcms.identity;

import com.tcms.api.TeamRef;
import com.tcms.api.UserRef;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org chart — who is here, which team they are in, and what they can do on the projects
 * <strong>this caller</strong> can open.
 *
 * <h2>The rule the whole surface reduces to</h2>
 *
 * <p><em>The people axis is a company fact; the project axis is the reader's own.</em> Person rows
 * are complete — every non-{@code REMOVED} member, their teams, their lead flags, their status, all
 * of which {@code GET /users} and {@code GET /teams} already serve to every member (plan §6k: org
 * structure is not sensitive <em>within</em> a company, and hiding it defeats the purpose). The
 * matrix columns are exactly {@link AdminReach#visibleProjects}. Two colleagues open this and see
 * the same people and different matrices; that is the design, not a bug, and the screen is required
 * to say so unconditionally rather than render a sparser grid in silence.
 *
 * <p>A project the caller cannot open contributes <strong>no column, no cell and no count</strong>.
 * Not even a count: §6t requires an unreachable project and a non-existent one to be
 * indistinguishable, {@link AdminReach#visibleProjects} draws the same line ("an id the caller
 * already holds is not a disclosure, a name is"), and a count is a name-free existence claim about
 * the company's portfolio. Dropping the {@link AdminReach#maySee} filter is the mutation that makes
 * the response <em>bigger</em>, renders correctly on every screen, and turns this chart into the way
 * around "membership conveys zero project visibility".
 *
 * <h2>Gated on {@code company:read}, and answering an empty document rather than 403</h2>
 *
 * <p>{@code company:read} because every member reads this chart; §84 recorded that inventing a key
 * for it would be "a 403 no administrator could grant their way out of", and {@code company:members}
 * would make it useless for its stated purpose — a lead who is not a Company Admin holds neither
 * that nor {@code company:teams}.
 *
 * <p>The empty document follows the four other identity reads ({@code AdminController}'s header):
 * the contract declares only {@code 200} here, and inventing a status a generated client does not
 * know about is the costlier divergence. The gate is real and effectively unfailable, because a
 * caller who is not a member cannot open a session on this company at all.
 *
 * <h2>Why this is a service beside {@link AdminDirectoryService} rather than a method on it</h2>
 *
 * <p>That class is already <em>the</em> assembler for people and teams and is 545 lines; a sixth
 * read method on it buys nothing. What matters is that this one <strong>reuses</strong> rather than
 * re-implements: {@link AdminDirectoryService#reachOf} for the project axis,
 * {@link AdminDirectoryService#peopleById} for the {@link UserRef} assembly — the one place §6u's
 * display-name rule is applied, and no architecture test can see a second implementation of it —
 * and {@link AdminDirectoryService#listTeams} for the team layer, so this chart and
 * {@code /admin/teams} cannot draw different trees or different project chips.
 *
 * <h2>Two queries for the whole matrix, not N×M</h2>
 *
 * <p>The naive shape is one {@link PermissionEvaluator#evaluate} per (person, project) pair. For 40
 * people and 8 projects that is 320 evaluations of several queries each, and neither
 * {@link AdminDirectoryService#reachOf} nor {@code ProjectMembershipService.membersOf} licenses it:
 * both accept an N-shaped cost with a stated ceiling, and N×M is not N. Instead the two generalised
 * role-name queries resolve every member at once and {@link EffectiveRoleNames} — the same type
 * {@code Project.myRole} resolves through — answers each cell. If somebody ever "simplifies" this
 * into a loop over the evaluator, this paragraph is the reason not to.
 */
@Service
public class OrgChartService {

    private final AdminDirectoryService directory;
    private final ProjectRepository projects;
    private final CompanyMembershipRepository memberships;
    private final AppUserRepository users;
    private final TeamRepository teams;
    private final TeamMembershipRepository teamMemberships;
    private final RoleAssignmentRepository roleAssignments;

    public OrgChartService(
            AdminDirectoryService directory,
            ProjectRepository projects,
            CompanyMembershipRepository memberships,
            AppUserRepository users,
            TeamRepository teams,
            TeamMembershipRepository teamMemberships,
            RoleAssignmentRepository roleAssignments) {

        this.directory = directory;
        this.projects = projects;
        this.memberships = memberships;
        this.users = users;
        this.teams = teams;
        this.teamMemberships = teamMemberships;
        this.roleAssignments = roleAssignments;
    }

    /**
     * The whole chart, in one snapshot, for this caller.
     *
     * <p>{@code @Transactional} on the entry point and nowhere below it: this method calls only its
     * own private helpers and injected beans, never its own transactional method through {@code
     * this}. A self-invocation here would not be a missing rollback but a missing
     * {@code app.current_company} — the transaction manager sets the GUC in {@code doBegin}, so the
     * unproxied call runs outside any transaction and <em>every</em> RLS read silently returns zero
     * rows while {@link CompanyContext} holds the right company (the §116 defect, HANDOFF §160).
     */
    @Transactional(readOnly = true)
    public OrgChartView chartFor(UUID userId) {
        AdminReach reach = directory.reachOf(userId);
        if (!reach.readsCompany()) {
            return new OrgChartView(List.of(), List.of(), List.of());
        }
        UUID companyId = CompanyContext.require();

        List<OrgChartProjectView> columns = projects.findAllByOrderByNameAsc().stream()
                // THE DISCLOSURE BOUNDARY, in one predicate. Everything else on this surface is
                // assembly.
                .filter(project -> reach.maySee(project.getId()))
                .map(project -> new OrgChartProjectView(
                        project.getId(), project.getKey(), project.getName(), project.isArchived()))
                .toList();

        List<CompanyMembership> present = memberships.findByCompanyId(companyId).stream()
                .filter(membership -> membership.getStatus() != MembershipStatus.REMOVED)
                .toList();
        if (present.isEmpty()) {
            // Not merely an optimization: an empty collection bound into a SQL `IN ()` is a syntax
            // error, so the three matrix queries below must not run. A company with no present
            // members is reachable — every membership REMOVED — rather than hypothetical.
            return new OrgChartView(directory.listTeams(userId), List.of(), columns);
        }

        List<UUID> people = present.stream().map(CompanyMembership::getUserId).toList();
        Set<UUID> columnIds = columns.stream()
                .map(OrgChartProjectView::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        EffectiveRoleNames effective = EffectiveRoleNames.of(
                roleAssignments.findProjectRoleNames(companyId, people),
                roleAssignments.findInheritedRoleNames(companyId, people));
        Map<UUID, List<String>> companyRoles = companyRolesByPerson(companyId, people);
        Map<UUID, TeamRef> teamsById = teamsById();
        Membership teamMembership = membershipByPerson();
        Map<UUID, UserRef> refs = directory.peopleById();
        Map<UUID, AppUser> accounts = accountsById(people);

        // Withheld from an ordinary member, the same rule and the same reason as User.lastLoginAt.
        boolean administers = reach.companyWide();

        List<OrgChartPersonView> rows = present.stream()
                .map(membership -> row(
                        membership, accounts.get(membership.getUserId()), administers,
                        refs, teamsById, teamMembership, companyRoles, effective, columnIds))
                .sorted(Comparator.comparing(
                        OrgChartPersonView::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new OrgChartView(directory.listTeams(userId), rows, columns);
    }

    private OrgChartPersonView row(
            CompanyMembership membership,
            AppUser account,
            boolean administers,
            Map<UUID, UserRef> refs,
            Map<UUID, TeamRef> teamsById,
            Membership teamMembership,
            Map<UUID, List<String>> companyRoles,
            EffectiveRoleNames effective,
            Set<UUID> columnIds) {

        UUID person = membership.getUserId();
        UserRef ref = refs.get(person);

        return new OrgChartPersonView(
                person,
                ref == null ? membership.getDisplayName() : ref.displayName(),
                ref == null ? null : ref.email(),
                membership.getAvatarUrl(),
                AdminDirectoryService.statusOf(membership, account),
                teamRefs(teamMembership.all().getOrDefault(person, List.of()), teamsById),
                teamRefs(teamMembership.led().getOrDefault(person, List.of()), teamsById),
                companyRoles.getOrDefault(person, List.of()),
                cells(person, effective, columnIds, teamsById),
                administers && account != null ? account.getLastLoginAt() : null);
    }

    /**
     * One person's cells — <strong>only over {@code columnIds}</strong>, which is where the
     * boundary is enforced a second time by construction: a project absent from the columns cannot
     * produce a cell even if the person demonstrably holds a role on it.
     */
    private List<OrgChartAssignmentView> cells(
            UUID person,
            EffectiveRoleNames effective,
            Set<UUID> columnIds,
            Map<UUID, TeamRef> teamsById) {

        List<OrgChartAssignmentView> cells = new ArrayList<>();
        for (UUID projectId : columnIds) {
            effective.on(person, projectId).ifPresent(roleName -> {
                var winner = effective.namedGrantOn(person, projectId).orElse(null);
                // No grant NAMES this project, so the name came from the company-wide role — which
                // is what INHERITED means. Reading the arm off the winning grant rather than
                // re-deriving it keeps one ranking: the arm shown and the name shown cannot come
                // from two different grants.
                AssignmentVia via = winner == null
                        ? AssignmentVia.INHERITED
                        : AssignmentVia.valueOf(winner.getVia());
                TeamRef viaTeam = via == AssignmentVia.TEAM ? teamsById.get(winner.getTeamId()) : null;
                cells.add(new OrgChartAssignmentView(projectId, roleName, via, viaTeam));
            });
        }
        return List.copyOf(cells);
    }

    /**
     * COMPANY-scope role names per person, with the {@link Role#COMPANY_MEMBER_ID} baseline dropped.
     *
     * <p>Dropped by <em>id</em> rather than by {@code role.assignable}: three seeded rows are
     * non-assignable and only this one is a consequence of membership. Filtering on the flag would
     * also hide a future non-assignable COMPANY-scope role that is a real fact about a person, which
     * is the baseline-specific-vs-general confusion {@code V40} §2 names.
     */
    private Map<UUID, List<String>> companyRolesByPerson(UUID companyId, List<UUID> people) {
        Map<UUID, Set<String>> byPerson = new HashMap<>();
        for (var row : roleAssignments.findCompanyRoleNames(companyId, people)) {
            if (!Role.COMPANY_MEMBER_ID.equals(row.getRoleId())) {
                byPerson.computeIfAbsent(row.getUserId(), key -> new TreeSet<>()).add(row.getRoleName());
            }
        }
        Map<UUID, List<String>> names = new HashMap<>();
        byPerson.forEach((person, roles) -> names.put(person, List.copyOf(roles)));
        return names;
    }

    private Map<UUID, TeamRef> teamsById() {
        Map<UUID, TeamRef> refs = new HashMap<>();
        teams.findAllByOrderByNameAsc()
                .forEach(team -> refs.put(team.getId(), new TeamRef(team.getId(), team.getName())));
        return refs;
    }

    /** Every person's team ids, and the subset they lead — one pass over the membership table. */
    private Membership membershipByPerson() {
        Map<UUID, List<UUID>> all = new HashMap<>();
        Map<UUID, List<UUID>> led = new HashMap<>();
        for (TeamMembership membership : teamMemberships.findAll()) {
            all.computeIfAbsent(membership.getUserId(), key -> new ArrayList<>())
                    .add(membership.getTeamId());
            if (membership.isLead()) {
                led.computeIfAbsent(membership.getUserId(), key -> new ArrayList<>())
                        .add(membership.getTeamId());
            }
        }
        return new Membership(all, led);
    }

    private record Membership(Map<UUID, List<UUID>> all, Map<UUID, List<UUID>> led) {
    }

    private static List<TeamRef> teamRefs(List<UUID> teamIds, Map<UUID, TeamRef> teamsById) {
        return teamIds.stream()
                .map(teamsById::get)
                .filter(team -> team != null)
                .sorted(Comparator.comparing(TeamRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<UUID, AppUser> accountsById(List<UUID> people) {
        Map<UUID, AppUser> accounts = new HashMap<>();
        // The ids come from company_membership rows RLS already scoped, which is the precondition
        // findByIdIn states — never from a request.
        users.findByIdIn(people).forEach(account -> accounts.put(account.getId(), account));
        return accounts;
    }

}
