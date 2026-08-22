package com.tcms.repository;

import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cases across every project the caller can see — the one read in this module that names no project
 * (§249).
 *
 * <h2>The gate is borrowed, and from {@code project}, not {@code identity}</h2>
 *
 * <p>{@link ProjectService#readAllVisible} hands back exactly the projects this caller holds
 * {@code project:read} on, computed by the one implementation of that question
 * ({@code ProjectAccess.canView} → {@code PermissionEvaluator}). {@code AdminReach.visibleProjects}
 * is the same set reached through {@code identity}, and this module may not ask it: its package
 * comment permits the {@code identity} edge for display names and forbids a permission question
 * through it in as many words — <em>"a permission check appearing anywhere in this package is a
 * defect regardless of what this list permits"</em>. The two are not two implementations; they are
 * two exposures of one, and this is the exposure {@code repository} is allowed to reach. The master
 * plan said to reuse {@code AdminReach}'s; doing so would have shipped a violation that ArchUnit and
 * Modulith both pass, because {@code identity} is on this module's allowed list.
 *
 * <h2>The narrowing is a predicate, not a filter — and that distinction is the whole feature</h2>
 *
 * <p>The gated ids go into {@link CaseScope} and become {@code project_id IN (…)} inside the same
 * {@code WHERE} that {@code CaseSearch.page} and {@code CaseSearch.total} share. Filtering in Java
 * after an unrestricted read would produce the right PAGE and the wrong TOTAL, and the wrong total
 * is a name-free existence claim about projects the caller was never granted — the disclosure §6t
 * and {@code AdminReach} both refuse, and the one {@code OrgChartService} refuses even as a count.
 *
 * <p><strong>RLS is not behind this.</strong> The policy predicate is the company; every project in
 * this company is visible to this session. There is no second boundary to be wrong under.
 *
 * <h2>Empty is answered before a query is built</h2>
 *
 * <p>A member holding no grants gets {@code total: 0} and no SQL, because an empty collection bound
 * into {@code IN ()} is a syntax error — the third time this codebase has met it
 * ({@code OrgChartService}, {@code BoardService.forAssignees}). {@link CaseScope} additionally
 * refuses to be constructed empty, so the short-circuit cannot be deleted quietly.
 *
 * <h2>No live-tree bound, which is a real difference from the grid</h2>
 *
 * <p>The scope is built through {@link CaseScope#acrossProjects}, whose {@code boundByTree} is
 * false: there is no one project whose suites could be enumerated. So a case filed in a
 * soft-deleted suite IS findable here where it is absent from that project's own grid — §249's Q4,
 * decided as ship-it-and-say-so, because a search is for finding a case you know exists and a case
 * hidden behind a deleted folder is precisely the one somebody cannot find.
 */
@Service
class CrossProjectSearchService {

    private final ProjectService projects;
    private final CaseSearch search;

    CrossProjectSearchService(ProjectService projects, CaseSearch search) {
        this.projects = projects;
        this.search = search;
    }

    @Transactional(readOnly = true)
    public CaseSearchPageView search(UUID userId, CrossProjectSearchQuery request) {
        return projects.readAllVisible(userId, visible -> {
            // The request may narrow within the visible set; it may never widen it. The
            // intersection is here, above the scope, so nothing downstream ever holds an id that
            // did not come back from the gate. An id naming an ungranted or non-existent project
            // therefore matches nothing, and the two are indistinguishable (§6t).
            List<Project> gated = request.projectIds().isEmpty()
                    ? visible
                    : visible.stream()
                            .filter(project -> request.projectIds().contains(project.getId()))
                            .toList();

            if (gated.isEmpty()) {
                // Reachable and empty — a member with no grants, or a projectId naming nothing they
                // hold. Also the IN () guard; see the header.
                return new CaseSearchPageView(request.page(), request.size(), 0L, List.of());
            }

            CaseScope scope = CaseScope.acrossProjects(
                    gated.stream().map(Project::getId).toList(), null, null, null);
            CaseQuery query = request.asCaseQuery();

            Map<UUID, Project> byId = gated.stream()
                    .collect(Collectors.toMap(Project::getId, project -> project));

            List<CaseSearchHitView> items = search.page(query, scope).stream()
                    .map(row -> hit(row, byId.get(row.getProjectId())))
                    .toList();

            return new CaseSearchPageView(
                    request.page(), request.size(), search.total(query, scope), items);
        });
    }

    /**
     * One hit, with its project named from the GATED set.
     *
     * <p>Never a second lookup. A {@code projectRepository.findById} here would be a second answer
     * to "which project is this", reachable for a project the gate excluded — and the rows came from
     * an {@code IN} over exactly these ids, so a miss is unreachable and is asserted rather than
     * defaulted. Defaulting to a blank name would turn a broken narrowing into a cosmetic blank.
     */
    private static CaseSearchHitView hit(TestCase row, Project project) {
        if (project == null) {
            throw new IllegalStateException(
                    "a hit from a project outside the gated set: " + row.getProjectId());
        }
        return new CaseSearchHitView(
                row.getId(),
                row.getKey(),
                row.getKeySeq(),
                row.getTitle(),
                project.getId(),
                project.getKey(),
                project.getName(),
                row.getLifecycleStatus(),
                row.getAutomationStatus(),
                row.getPriority(),
                row.getUpdatedAt());
    }
}
