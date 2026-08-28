package com.tcms.execution;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test plans — the {@code execution} module's first read.
 *
 * <h2>Two gates, borrowed unchanged</h2>
 *
 * <p>{@link ProjectService#readVisible} answers "may this caller see this project" — company
 * membership, then the explicit project grant — exactly as every read in {@code repository} does.
 * Nothing in this package decides that question itself; a second answer to "may they?" is the bug
 * class plan §6b names, and the quieter of two answers is the one that decides what a client reads.
 *
 * <p>The second gate is inside the query: {@link TestPlanRepository#findLiveInProject} matches the
 * project as well as the plan. Without it a plan id from a sibling project resolves through a path
 * naming a project the caller does hold — and every cross-company test still passes, because the row
 * is in the caller's own company.
 *
 * <h2><s>{@code passRate} and {@code coverage} stay null, and now for a better reason</s> → they are
 * written, and the reason decided which policy</h2>
 *
 * <p><s>They were null because the statistics endpoint did not exist. It does
 * ({@link PlanStatisticsService}), and they are still null — because filling them here would mean
 * resolving every plan's executions <em>once per row of the plan list</em>, which is that endpoint
 * run N times for a screen that shows a bar.</s> <strong>→ 005 S5.</strong> That objection is about
 * an implementation, and {@link PlanListStatistics} is not it: two queries for the whole page,
 * neither of them per plan. What made the old sentence right was never the arithmetic, it was the
 * loop.
 *
 * <p><strong>The second reason survives intact and decided the design rather than blocking it.</strong>
 * A pass rate has no meaning without the aggregation policy that produced it, and the list has
 * nowhere to state one. So the list does not <em>offer</em> one: it reports
 * {@link AggregationPolicy#LATEST}, the default the statistics page opens on, fixed. Two numbers
 * computed under different policies on two screens with neither saying which is the §6p ambiguity —
 * one number under one named policy, matching what a reader sees on the screen they click through
 * to, is not.
 *
 * <p>Null still means "not measured" and the SPA still renders it that way; it now happens on the
 * plans it is true of rather than on all of them.
 */
@Service
public class PlanService {

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanScopeItemRepository scopeItems;
    private final ConfigurationService configurations;
    private final CaseFacts cases;
    private final PlanCriteria criteria;
    private final PlanListStatistics statistics;

    public PlanService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanScopeItemRepository scopeItems,
            ConfigurationService configurations,
            CaseFacts cases,
            PlanCriteria criteria,
            PlanListStatistics statistics) {

        this.projects = projects;
        this.plans = plans;
        this.scopeItems = scopeItems;
        this.configurations = configurations;
        this.cases = cases;
        this.criteria = criteria;
        this.statistics = statistics;
    }

    /**
     * A project's plans, or empty when the caller may not see the project.
     *
     * <p>An empty <em>list</em> and an empty {@link Optional} mean different things and the
     * controller renders them differently: the first is a reachable project with no plans, which is
     * the state of every freshly provisioned project, and the second is a 404.
     *
     * <p><strong>The working list by default</strong> ({@code V65}, audit D2): archived plans are
     * omitted unless {@code includeArchived}. The default is the narrow one because a plan is
     * archived precisely to stop it being read, and a caller who has not asked for the archive is
     * every caller that existed before the column did.
     */
    @Transactional(readOnly = true)
    public Optional<List<TestPlanView>> list(UUID userId, UUID projectId, boolean includeArchived) {
        return projects.readVisible(userId, projectId, project -> {
            List<TestPlan> rows = includeArchived
                    ? plans.findAllInProject(project.getId())
                    : plans.findLiveInProject(project.getId());
            List<UUID> planIds = rows.stream().map(TestPlan::getId).toList();
            Map<UUID, Long> caseCounts = cases.caseCountsByPlan(project.getId(), planIds);
            Map<UUID, Map<String, Long>> sectionCounts =
                    cases.caseCountsBySection(project.getId(), planIds);
            Map<UUID, PlanScope> scopes = scopesOf(planIds);
            List<ConfigurationView> projectConfigurations = configurationsOf(project.getId());
            Map<UUID, PlanListStatistics.PlanStats> stats =
                    statistics.forPlans(project.getId(), rows);

            return rows.stream()
                    .map(plan -> view(
                            plan, caseCounts, sectionCounts, scopes, projectConfigurations, stats))
                    .toList();
        });
    }

    /** One plan, or empty when the caller may not see the project or the plan is not in it. */
    @Transactional(readOnly = true)
    public Optional<TestPlanView> findOne(UUID userId, UUID projectId, UUID planId) {
        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> plans.findLiveInProject(project.getId(), planId))
                .map(this::viewOf);
    }

    /**
     * One plan as the API serves it, resolving its own counts and configurations.
     *
     * <p>Exists for {@link PlanCreateService}, which must answer the same shape {@code GET} answers.
     * Not a second assembler: a created plan rendered by a different method would show one thing on
     * create and another on the reload that follows it, with neither screen revealing which is
     * stored — {@code repository.CaseCreateService} makes the same argument at more length, and the
     * force of it here is that a create response is the <em>first</em> thing anyone sees of a plan.
     *
     * <p>Two extra queries per create, which is the right trade at one row per user action.
     */
    @Transactional(readOnly = true)
    TestPlanView viewOf(TestPlan plan) {
        return view(
                plan,
                cases.caseCountsByPlan(plan.getProjectId(), List.of(plan.getId())),
                cases.caseCountsBySection(plan.getProjectId(), List.of(plan.getId())),
                scopesOf(List.of(plan.getId())),
                configurationsOf(plan.getProjectId()),
                // The same batched pass over a batch of one. A create answers the same numbers the
                // reload after it will, which is the whole reason this method exists rather than a
                // second assembler.
                statistics.forPlans(plan.getProjectId(), List.of(plan)));
    }

    /**
     * The wire shape, assembled once for both endpoints.
     *
     * <p>{@code configurations} is the <em>project's</em> list rather than a per-plan one, because
     * {@code configuration} has no plan column: ERD §5's diagram draws {@code TEST_PLAN ||--o{
     * CONFIGURATION} and {@code V1} implements it per project. The contract puts the array on the
     * plan, so the plan serves what a run created from it may choose between — which is the question
     * the field is read to answer.
     */
    private TestPlanView view(
            TestPlan plan,
            Map<UUID, Long> caseCounts,
            Map<UUID, Map<String, Long>> sectionCounts,
            Map<UUID, PlanScope> scopes,
            List<ConfigurationView> configurations,
            Map<UUID, PlanListStatistics.PlanStats> stats) {

        PlanScope scope = scopes.getOrDefault(plan.getId(), PlanScope.empty());
        Map<String, Long> counts = sectionCounts.getOrDefault(plan.getId(), Map.of());
        PlanListStatistics.PlanStats measured =
                stats.getOrDefault(plan.getId(), PlanListStatistics.PlanStats.NONE);
        return new TestPlanView(
                plan.getId(),
                plan.getKey(),
                plan.getKeySeq(),
                plan.getName(),
                plan.getStatus(),
                plan.getMilestoneName(),
                plan.getMilestoneDate(),
                // A plan with no scope items has no GROUP BY row at all, so zero is the normal path
                // for a plan someone has only just named — not a fallback for a broken query.
                caseCounts.getOrDefault(plan.getId(), 0L),
                measured.passRate(),
                measured.coverage(),
                measured.exitCriteriaMet(),
                measured.exitCriteriaTotal(),
                plan.getDescription(),
                criteria.parse(plan.getEntryCriteria()),
                criteria.parse(plan.getExitCriteria()),
                configurations,
                scope.suiteIds(),
                scope.caseIds(),
                scope.tags(),
                // Zero for a section absent from the counts, which is the ordinary answer for an
                // empty one: its single row targets nothing, so it produces no GROUP BY row at all.
                scope.sections().stream()
                        .map(section -> new PlanSectionView(
                                section.name(),
                                section.position(),
                                section.caseIds(),
                                section.suiteIds(),
                                section.tags(),
                                counts.getOrDefault(section.name(), 0L)))
                        .toList(),
                plan.getScopeText(),
                plan.getOutOfScopeText(),
                plan.getCurrentVersionNo(),
                plan.getLockVersion(),
                plan.isArchived());
    }

    /**
     * The scope of each of several plans, in one query.
     *
     * <p>Empty for a plan with no scope items, which is why the caller reads it through
     * {@code getOrDefault}: a plan somebody has only just named has no rows here at all, and that is
     * the normal path rather than a missing join.
     */
    private Map<UUID, PlanScope> scopesOf(List<UUID> planIds) {
        if (planIds.isEmpty()) {
            return Map.of();
        }
        return scopeItems.findForPlans(planIds).stream()
                .collect(Collectors.groupingBy(PlanScopeItem::getPlanId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> PlanScope.ofItems(e.getValue())));
    }

    /**
     * Delegated since the register got its own endpoint — see {@link ConfigurationService#of}, which
     * is this method's body moved rather than a second copy of it.
     */
    private List<ConfigurationView> configurationsOf(UUID projectId) {
        return configurations.of(projectId);
    }
}
