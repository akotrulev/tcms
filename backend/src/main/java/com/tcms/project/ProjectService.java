package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which projects a caller may see, and what they are told about them.
 *
 * <h2>The two gates, and why the second one is the whole point</h2>
 *
 * <p>Access is two <em>independent</em> gates (plan §6b, handoff §3):
 *
 * <ol>
 *   <li><strong>company</strong> — the RLS policy and the Hibernate filter, established from
 *       the authenticated session before this class runs. It scopes the candidate rows to the
 *       active company and nothing here can widen it.</li>
 *   <li><strong>project grant</strong> — an explicit grant per project, asked through
 *       {@link ProjectAccess}. <strong>Company membership conveys authentication and zero
 *       project visibility.</strong> Without this gate a member of Acme who was granted nothing
 *       would see Acme's entire project list — its names, its count, its clients' product
 *       portfolio — and <em>every cross-company test in the suite would still pass</em>, because
 *       those rows are in the caller's own company. That is persona 3 of the isolation harness,
 *       promoted here from a permission question to an HTTP one.</li>
 * </ol>
 *
 * <h2>Why visibility is resolved per project rather than in one clever query</h2>
 *
 * <p>The obvious implementation is a single native query joining {@code role_assignment},
 * {@code team_membership} and {@code role_permission} to return visible project ids. It would
 * be one round trip instead of N, and it is the wrong shape: it is a <strong>second
 * authorization-resolution path beside the permission evaluator</strong>, which is the bug class
 * plan §6b names explicitly and the reason the session token deliberately carries no grant
 * claims (handoff §18). Two implementations of "may they?" diverge eventually, and the one that
 * diverges quietly is the one that decides what a client can read.
 *
 * <p>So the evaluator stays the only answer, and the cost is accepted with its ceiling stated:
 * a company holds <em>a handful of whole products</em>, not dozens of feature areas (plan §1),
 * so N is small and every query behind it is index-covered. The two things that would otherwise
 * be N+1 <em>per project</em> — the case counts and the role names — are each fetched in one
 * grouped query, so what remains is N permission evaluations rather than 3N queries. When the
 * evaluator gets the Caffeine cache §6b specifies, this improves for free and no call site here
 * changes.
 *
 * <h2>Why an unreachable project is 404 and not 403</h2>
 *
 * <p>{@link #findVisible} returns an empty {@link Optional} for a project that does not exist,
 * one that belongs to another company, and one that exists here with no grant for this caller —
 * identically, so the controller answers all three the same way. Telling them apart turns the
 * endpoint into an oracle for what exists in another client's tenancy (§6t, handoff §3 lesson
 * 13). Same rule {@code InvitationController} follows on the one surface a stranger can reach.
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectAccess access;

    public ProjectService(ProjectRepository projects, ProjectAccess access) {
        this.projects = projects;
        this.access = access;
    }

    /**
     * Every project in the active company this caller may see, by name.
     *
     * <p>Archived projects are included. The contract carries {@code archived} as a field
     * rather than as a filter, and the SPA's picker distinguishes them — omitting them
     * server-side would make an archived project unreachable rather than marked, which is not
     * what "suspend, never delete" means one level down.
     */
    @Transactional(readOnly = true)
    public List<ProjectView> listVisible(UUID userId) {
        UUID companyId = CompanyContext.require();

        List<Project> visible = projects.findAllByOrderByNameAsc().stream()
                .filter(project -> access.canView(userId, project.getId()))
                .toList();
        if (visible.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a
            // syntax error, so the grouped query below must not run. A member with no grants
            // is the ordinary case here, not an edge one.
            return List.of();
        }

        Map<UUID, Long> caseCounts =
                caseCounts(companyId, visible.stream().map(Project::getId).toList());
        Map<UUID, String> roleNames = access.projectRoleNames(userId);

        return visible.stream().map(project -> view(project, caseCounts, roleNames)).toList();
    }

    /**
     * One project, or empty when the caller may not see it — for any of the three reasons.
     *
     * @see #listVisible for why the emptiness is deliberately undifferentiated
     */
    @Transactional(readOnly = true)
    public Optional<ProjectView> findVisible(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();

        // findScopedById rather than findById: the latter resolves by primary key through the
        // persistence context, and Hibernate filters do NOT apply to that path. RLS would still
        // cover it, but using one layer where both are available is how a layered defence
        // quietly becomes a single point (see ProjectRepository's header).
        return projects.findScopedById(projectId)
                .filter(project -> access.canView(userId, project.getId()))
                .map(project -> view(
                        project,
                        caseCounts(companyId, List.of(project.getId())),
                        access.projectRoleNames(userId)));
    }

    private ProjectView view(
            Project project, Map<UUID, Long> caseCounts, Map<UUID, String> roleNames) {
        return new ProjectView(
                project.getId(),
                project.getKey(),
                project.getName(),
                project.getDescription(),
                project.isArchived(),
                // A project with no cases has no GROUP BY row at all, so this default is the
                // normal path for a new project rather than a fallback for a broken query.
                caseCounts.getOrDefault(project.getId(), 0L),
                // null when the caller reaches this project through a COMPANY-scope grant —
                // the contract's stated meaning of myRole, not a lookup that failed.
                roleNames.get(project.getId()));
    }

    private Map<UUID, Long> caseCounts(UUID companyId, List<UUID> projectIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (var row : projects.countLiveCasesByProject(companyId, projectIds)) {
            counts.put(row.getProjectId(), row.getCaseCount());
        }
        return counts;
    }
}
