package com.tcms.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's two vocabularies — its classification dimensions and its execution statuses.
 *
 * <p>Both are read-only here. The rows come from {@code tcms_seed_company_defaults} /
 * {@code tcms_seed_project_defaults}, called by {@code provision-company}, which are the only
 * sanctioned writers until an editing endpoint exists (plan §6d).
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>Every method here goes through {@link ProjectService#readVisible}, so both access gates apply
 * exactly as they do to {@code GET /projects/{projectId}} — company first, then the explicit
 * project grant. Nothing in this class decides whether a caller may see a project, and that is the
 * point: a second answer to "may they?" is the bug class plan §6b names, and the quieter of two
 * answers is the one that decides what a client can read.
 *
 * <p>The failure that would otherwise be easy to ship is not the dramatic one. It is an endpoint
 * that checks only that the project exists in the caller's company — which passes every
 * cross-company test in the suite, because the rows genuinely are in the caller's company — and
 * hands a member with no grant at all the client's whole Feature tree. A feature taxonomy names
 * what a client is building.
 *
 * <h2>Two queries per dimension list, never one per dimension</h2>
 *
 * <p>Dimensions and their values are fetched separately and grouped in memory rather than through
 * a mapped {@code @OneToMany}, which would have lazily loaded values one dimension at a time. Four
 * dimensions is a small N, and an N+1 that is cheap today is still the shape that gets copied into
 * the case list.
 */
@Service
public class ProjectConfigurationService {

    private final ProjectService projects;
    private final ClassificationDimensionRepository dimensions;
    private final ClassificationValueRepository values;
    private final CaseStatusRepository statuses;

    public ProjectConfigurationService(
            ProjectService projects,
            ClassificationDimensionRepository dimensions,
            ClassificationValueRepository values,
            CaseStatusRepository statuses) {

        this.projects = projects;
        this.dimensions = dimensions;
        this.values = values;
        this.statuses = statuses;
    }

    /**
     * The dimensions that apply to this project — company-level and project-level together — each
     * with its live values, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and it has no dimensions". Collapsing them would turn an unprovisioned company
     * into a 404 and a permission failure into a plausible-looking empty screen.
     */
    @Transactional(readOnly = true)
    public Optional<List<ClassificationDimensionView>> findClassification(
            UUID userId, UUID projectId) {

        return projects.readVisible(userId, projectId, project -> classificationOf(project.getId()));
    }

    /** This project's execution statuses, or empty when the caller may not see the project. */
    @Transactional(readOnly = true)
    public Optional<List<CaseStatusView>> findStatuses(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project ->
                statuses.findByProjectIdOrderByPositionAscNameAsc(project.getId()).stream()
                        .map(CaseStatusView::of)
                        .toList());
    }

    private List<ClassificationDimensionView> classificationOf(UUID projectId) {
        List<ClassificationDimension> applicable = dimensions.findForProject(projectId);
        if (applicable.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, so the value query below must not run. Same guard, same reason, as
            // ProjectService.listVisible.
            return List.of();
        }

        Map<UUID, List<ClassificationValueView>> byDimension = valuesByDimension(
                applicable.stream().map(ClassificationDimension::getId).toList());

        return applicable.stream()
                .map(dimension -> new ClassificationDimensionView(
                        dimension.getId(),
                        dimension.getKey(),
                        dimension.getName(),
                        dimension.getCardinality(),
                        dimension.isHierarchical(),
                        dimension.isLocked(),
                        // A dimension with no values has no entry at all, and that is the normal
                        // state of a freshly seeded Feature tree — not a fallback for a failed
                        // lookup. An absent list must serialize as [] and never as null: the
                        // contract marks `values` required, so the SPA cannot null-check it.
                        byDimension.getOrDefault(dimension.getId(), List.of())))
                .toList();
    }

    /** Preserves the query's ordering within each dimension — {@code ArrayList}, not a set. */
    private Map<UUID, List<ClassificationValueView>> valuesByDimension(List<UUID> dimensionIds) {
        Map<UUID, List<ClassificationValueView>> grouped = new HashMap<>();
        for (ClassificationValue value : values.findLiveForDimensions(dimensionIds)) {
            grouped.computeIfAbsent(value.getDimensionId(), key -> new ArrayList<>())
                    .add(ClassificationValueView.of(value));
        }
        return grouped;
    }
}
