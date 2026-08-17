package com.tcms.identity;

import com.tcms.identity.RoleAssignmentRepository.InheritedRoleName;
import com.tcms.identity.RoleAssignmentRepository.ProjectRoleName;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Roles spec §A1.1's <strong>replace-don't-union</strong> override, in one place: a grant naming the
 * project wins, otherwise the company-wide PROJECT-scope role applies, otherwise there is no name.
 *
 * <h2>Why this is a type and not the one line it used to be</h2>
 *
 * <p>It was {@code roleNames.getOrDefault(project.getId(), inheritedRoleName)} in
 * {@code ProjectService.view}, and that was fine while one screen rendered it. The org chart renders
 * the same rule per person per project, so copying the line would make the count of
 * implementations <strong>four</strong>: this one, {@code PermissionEvaluator.projectTerm} (which
 * resolves permissions rather than names and so would not notice the disagreement),
 * {@code ProjectService}, and the chart. Three is already one more than anybody keeps in step; the
 * frontend's {@code roleAccess.ts} says so about itself in a comment.
 *
 * <p>The failure mode is specific and it is not cosmetic. §A1.1 is <em>"the one place in this model
 * where adding a grant REMOVES a permission"</em>: union instead of replace renders the colleague
 * you just demoted to Viewer as still a Test Lead. The switch is <strong>whether</strong> a grant
 * names the project, never <em>what</em> that grant confers.
 *
 * <h2>Ranking, and why it is here too</h2>
 *
 * <p>A person can hold two grants on one project — directly and through a team, or through two
 * teams — and the contract has room for one string. The winner is the role conferring more
 * permissions, <strong>derived from {@code role_permission}</strong> rather than from a hard-coded
 * ordering of names, which is the rule {@code RoleAssignmentRepository}'s header already states and
 * which generalising the query had to carry: it is now a maximum per (person, project) rather than
 * per project. Ties break on the name so the answer is stable between requests rather than dependent
 * on row order — picking either of two equally-strong roles is defensible, picking a different one
 * each time is not.
 *
 * <h2>What it does not decide</h2>
 *
 * <p>Nothing here answers <em>may they</em>. These strings are display only, and nothing may branch
 * on one (plan §6b). The authorization answer is {@code PermissionEvaluator}'s, and the test that
 * pins these two resolutions of one rule to each other is the point of the pairing — a cell asserting
 * somebody has a role on a project they cannot open is this path disagreeing with that one.
 */
final class EffectiveRoleNames {

    /**
     * Strongest project-naming grant per person per project. The winning <em>row</em> rather than
     * its name, because {@code via} and {@code viaTeam} are properties of the grant that won and
     * re-deriving them from a name would be a second ranking.
     */
    private final Map<UUID, Map<UUID, ProjectRoleName>> named;

    /** Strongest company-wide PROJECT-scope role name per person, when they hold one. */
    private final Map<UUID, String> inherited;

    private EffectiveRoleNames(
            Map<UUID, Map<UUID, ProjectRoleName>> named, Map<UUID, String> inherited) {
        this.named = named;
        this.inherited = inherited;
    }

    private static final Comparator<ProjectRoleName> NAMED_BY_STRENGTH =
            Comparator.comparingLong(ProjectRoleName::getPermissionCount)
                    .thenComparing(ProjectRoleName::getRoleName);

    private static final Comparator<InheritedRoleName> INHERITED_BY_STRENGTH =
            Comparator.comparingLong(InheritedRoleName::getPermissionCount)
                    .thenComparing(InheritedRoleName::getRoleName);

    /**
     * Reduces both queries' rows to the resolved answer.
     *
     * <p>Takes the rows rather than reading them, so the rule is testable without a database and
     * without a company context — the queries are covered where they live.
     */
    static EffectiveRoleNames of(
            Collection<ProjectRoleName> namedGrants, Collection<InheritedRoleName> inheritedGrants) {

        Map<UUID, Map<UUID, ProjectRoleName>> named = new HashMap<>();
        for (ProjectRoleName row : namedGrants) {
            named.computeIfAbsent(row.getUserId(), person -> new HashMap<>())
                    .merge(row.getProjectId(), row, EffectiveRoleNames::stronger);
        }

        Map<UUID, String> inherited = new HashMap<>();
        Map<UUID, InheritedRoleName> strongestInherited = new HashMap<>();
        for (InheritedRoleName row : inheritedGrants) {
            strongestInherited.merge(row.getUserId(), row,
                    (existing, candidate) ->
                            INHERITED_BY_STRENGTH.compare(candidate, existing) > 0
                                    ? candidate : existing);
        }
        strongestInherited.forEach((person, row) -> inherited.put(person, row.getRoleName()));

        return new EffectiveRoleNames(named, inherited);
    }

    private static ProjectRoleName stronger(ProjectRoleName existing, ProjectRoleName candidate) {
        return NAMED_BY_STRENGTH.compare(candidate, existing) > 0 ? candidate : existing;
    }

    /**
     * <strong>The override.</strong> This person's effective PROJECT-scope role name on this
     * project, or empty when they reach it some other way — a Company Admin, whose role is a claim
     * about the company rather than about any project.
     */
    Optional<String> on(UUID userId, UUID projectId) {
        ProjectRoleName winner = named.getOrDefault(userId, Map.of()).get(projectId);
        return Optional.ofNullable(winner != null ? winner.getRoleName() : inherited.get(userId));
    }

    /**
     * The project-naming grant that won here, when one exists — the source of {@code via} and
     * {@code viaTeam}.
     *
     * <p>Empty means the cell is inherited or absent, and {@link #on} is what tells those two apart.
     * Returning the row rather than a {@code via} keeps one ranking: the arm that won and the name
     * that shows cannot come from different grants.
     */
    Optional<ProjectRoleName> namedGrantOn(UUID userId, UUID projectId) {
        return Optional.ofNullable(named.getOrDefault(userId, Map.of()).get(projectId));
    }

    /**
     * One person's effective names across the projects a caller is rendering, absent entries
     * omitted — the shape {@code Project.myRole} needs, where a missing key means null.
     */
    Map<UUID, String> forPerson(UUID userId, Collection<UUID> projectIds) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID projectId : projectIds) {
            on(userId, projectId).ifPresent(name -> names.put(projectId, name));
        }
        return names;
    }
}
