package com.tcms.repository;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's reusable condition library (plan §6f, §6h).
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>The read goes through {@link ProjectService#readVisible}, so a caller passes exactly the two
 * gates {@code GET /projects/{projectId}} applies — company first, then the explicit project
 * grant. Nothing in this class decides whether a caller may see a project, which is the rule the
 * whole module follows and the one a permission check appearing here would break.
 *
 * <p>The failure that gate prevents is quiet rather than dramatic: an endpoint checking only that
 * the project is in the caller's company passes every cross-company test in the suite — the rows
 * genuinely are in the caller's company — and hands a member granted nothing a list of the
 * client's setup procedures. A condition library reads "seed a captured payment of 120.00 EUR",
 * "open the legacy admin console", "restore the merchant balance"; it is a description of how the
 * client's system is operated, which is why it is behind the grant and not merely behind the
 * company.
 *
 * <h2>One query, and no grouping</h2>
 *
 * <p>Unlike the suite tree and the classification vocabularies, this list is flat and owns nothing
 * — so it is a single query with no second fetch to group in memory. The two filters are resolved
 * into bind parameters by {@link ConditionQuery} before they arrive.
 */
@Service
public class ConditionService {

    private final ProjectService projects;
    private final ConditionRepository conditions;

    public ConditionService(ProjectService projects, ConditionRepository conditions) {
        this.projects = projects;
        this.conditions = conditions;
    }

    /**
     * The project's matching live conditions, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and nothing matched". Collapsing them would turn both a freshly provisioned
     * project — whose library is empty, since {@code tcms_seed_project_defaults} seeds statuses
     * and a configuration and deliberately not a client's setup procedures — and a typeahead that
     * has narrowed past the data into a 404 the user cannot back out of.
     */
    @Transactional(readOnly = true)
    public Optional<List<ConditionView>> findLibrary(
            UUID userId, UUID projectId, ConditionQuery request) {

        return projects.readVisible(userId, projectId, project ->
                conditions.findLibrary(project.getId(), request.types(), request.needle()).stream()
                        .map(ConditionView::of)
                        .toList());
    }
}
