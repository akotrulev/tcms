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
 * <h2>{@code passRate} and {@code coverage} stay null, and now for a better reason</h2>
 *
 * <p>They were null because the statistics endpoint did not exist. It does
 * ({@link PlanStatisticsService}), and they are still null — because filling them here would mean
 * resolving every plan's executions <em>once per row of the plan list</em>, which is that endpoint
 * run N times for a screen that shows a bar.
 *
 * <p>The deeper reason is the one that would survive making it cheap: a pass rate has no meaning
 * without the aggregation policy that produced it, and the list has nowhere to state one. Two
 * numbers computed under different policies, on two screens, neither of which says which — that is
 * the §6p ambiguity the whole statistics page is shaped to remove. The contract marks both nullable
 * and the SPA renders a null as "not measured", which is true.
 */
@Service
public class PlanService {

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final PlanScopeItemRepository scopeItems;
    private final ConfigurationRepository configurations;
    private final CaseFacts cases;
    private final PlanCriteria criteria;

    public PlanService(
            ProjectService projects,
            TestPlanRepository plans,
            PlanScopeItemRepository scopeItems,
            ConfigurationRepository configurations,
            CaseFacts cases,
            PlanCriteria criteria) {

        this.projects = projects;
        this.plans = plans;
        this.scopeItems = scopeItems;
        this.configurations = configurations;
        this.cases = cases;
        this.criteria = criteria;
    }

    /**
     * A project's plans, or empty when the caller may not see the project.
     *
     * <p>An empty <em>list</em> and an empty {@link Optional} mean different things and the
     * controller renders them differently: the first is a reachable project with no plans, which is
     * the state of every freshly provisioned project, and the second is a 404.
     */
    @Transactional(readOnly = true)
    public Optional<List<TestPlanView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project -> {
            List<TestPlan> rows = plans.findLiveInProject(project.getId());
            List<UUID> planIds = rows.stream().map(TestPlan::getId).toList();
            Map<UUID, Long> caseCounts = cases.caseCountsByPlan(project.getId(), planIds);
            Map<UUID, PlanScope> scopes = scopesOf(planIds);
            List<ConfigurationView> projectConfigurations = configurationsOf(project.getId());

            return rows.stream()
                    .map(plan -> view(plan, caseCounts, scopes, projectConfigurations))
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
                scopesOf(List.of(plan.getId())),
                configurationsOf(plan.getProjectId()));
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
            Map<UUID, PlanScope> scopes,
            List<ConfigurationView> configurations) {

        PlanScope scope = scopes.getOrDefault(plan.getId(), PlanScope.empty());
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
                null,
                null,
                plan.getDescription(),
                criteria.parse(plan.getEntryCriteria()),
                criteria.parse(plan.getExitCriteria()),
                configurations,
                scope.suiteIds(),
                scope.caseIds(),
                plan.getCurrentVersionNo(),
                plan.getLockVersion());
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

    private List<ConfigurationView> configurationsOf(UUID projectId) {
        return configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId).stream()
                .map(configuration -> new ConfigurationView(
                        configuration.getId(),
                        configuration.getName(),
                        configuration.isDefault(),
                        criteria.attributes(configuration.getAttributes())))
                .toList();
    }
}
