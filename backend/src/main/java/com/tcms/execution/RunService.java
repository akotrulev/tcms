package com.tcms.execution;

import com.tcms.project.CaseStatusView;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test runs — the list, and the detail with its checklist.
 *
 * <p>The two gates are {@link PlanService}'s, unchanged and borrowed for the same reasons.
 *
 * <h2>The filters narrow; they never resolve</h2>
 *
 * <p>{@code planId} and {@code releaseVersion} are questions <em>about this project's runs</em>. A
 * {@code planId} naming a plan in another project therefore answers an empty list, not a 404: the
 * honest answer to "which of my runs belong to that plan" is none, and answering 404 would make the
 * query string an existence oracle for plan ids the caller cannot otherwise see (§6t).
 *
 * <h2>Counts come from one grouped query per page</h2>
 *
 * <p>Not one per run, and not a stored column. {@code suite_run_summary} and {@code plan_snapshot}
 * exist for pre-aggregated rollups and belong to {@code reporting}, fed by events (§3 lesson 4) —
 * neither exists. Computing the bar from {@code test_execution} keeps the list honest at the cost of
 * one {@code GROUP BY}, and it means the counts cannot go stale relative to a result recorded a
 * second ago, which is exactly what a tester checks after saving one.
 */
@Service
public class RunService {

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final ConfigurationRepository configurations;
    private final ResultStatuses statuses;
    private final ExecutionViews views;
    private final PlanCriteria criteria;

    public RunService(
            ProjectService projects,
            TestRunRepository runs,
            TestExecutionRepository executions,
            ConfigurationRepository configurations,
            ResultStatuses statuses,
            ExecutionViews views,
            PlanCriteria criteria) {

        this.projects = projects;
        this.runs = runs;
        this.executions = executions;
        this.configurations = configurations;
        this.statuses = statuses;
        this.views = views;
        this.criteria = criteria;
    }

    /** A project's runs, newest first, or empty when the caller may not see the project. */
    @Transactional(readOnly = true)
    public Optional<List<TestRunView>> list(
            UUID userId, UUID projectId, UUID planId, String releaseVersion) {

        return projects.readVisible(userId, projectId, project -> {
            List<TestRun> rows =
                    runs.findLiveInProject(project.getId(), planId, blankToNull(releaseVersion));
            if (rows.isEmpty()) {
                return List.<TestRunView>of();
            }
            Context context = contextFor(project.getId(), rows);
            return rows.stream().map(run -> view(run, context)).toList();
        });
    }

    /** One run with its executions, or empty for any of the ways it can be unreachable. */
    @Transactional(readOnly = true)
    public Optional<TestRunDetailView> findOne(UUID userId, UUID projectId, UUID runId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId))
                .map(run -> {
                    Context context = contextFor(run.getProjectId(), List.of(run));
                    return TestRunDetailView.of(
                            view(run, context),
                            views.render(
                                    companyId,
                                    executions.findForRun(run.getProjectId(), run.getId()),
                                    context.statuses()));
                });
    }

    /**
     * One run as the API serves it, resolving its own plan key, configuration and counts.
     *
     * <p>Exists for {@link RunCreateService}, and it is not a second assembler for
     * {@link PlanService#viewOf}'s reason: a created run rendered by a different method would show
     * one thing on create and another on the reload after it. The counts it computes for a
     * just-created run are the honest ones — every execution unrecorded, so {@code notRun} equals
     * the checklist's length, which is exactly what the run list must show beside it.
     */
    @Transactional(readOnly = true)
    TestRunView viewOf(TestRun run) {
        return view(run, contextFor(run.getProjectId(), List.of(run)));
    }

    /**
     * Everything a batch of runs needs beyond their own columns, fetched once.
     *
     * <p>Three lookups for a page of runs: the plan keys, the configurations, and the tally. Each is
     * one query regardless of how many runs are on the page — the shape {@code repository.CaseSummaries}
     * established, and the reason a run list does not slow down as a project accumulates runs.
     */
    private record Context(
            Map<UUID, String> planKeys,
            Map<UUID, ConfigurationView> configurations,
            Map<UUID, CaseStatusView> statuses,
            Map<UUID, ResultCountsView> counts) {
    }

    private Context contextFor(UUID projectId, List<TestRun> rows) {
        Map<UUID, String> planKeys = new HashMap<>();
        List<UUID> planIds = rows.stream()
                .map(TestRun::getPlanId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!planIds.isEmpty()) {
            for (Object[] row : runs.findPlanKeys(planIds)) {
                planKeys.put((UUID) row[0], (String) row[1]);
            }
        }

        Map<UUID, ConfigurationView> byId = new HashMap<>();
        for (Configuration configuration
                : configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId)) {
            byId.put(configuration.getId(), new ConfigurationView(
                    configuration.getId(),
                    configuration.getName(),
                    configuration.isDefault(),
                    criteria.attributes(configuration.getAttributes())));
        }

        Map<UUID, CaseStatusView> statusesById = statuses.byId(projectId);

        Map<UUID, Map<UUID, Long>> byRun = new HashMap<>();
        for (Object[] row : executions.tallyByRun(rows.stream().map(TestRun::getId).toList())) {
            byRun.computeIfAbsent((UUID) row[0], id -> new HashMap<>())
                    // A null status id is a real group: every execution nobody has recorded yet.
                    .put((UUID) row[1], ((Number) row[2]).longValue());
        }
        Map<UUID, ResultCountsView> counts = new HashMap<>();
        for (TestRun run : rows) {
            counts.put(run.getId(),
                    ExecutionViews.tally(byRun.getOrDefault(run.getId(), Map.of()), statusesById));
        }

        return new Context(planKeys, byId, statusesById, counts);
    }

    /**
     * @throws IllegalStateException when the run's configuration is not in the project's list, which
     *         the schema makes unreachable ({@code configuration_id} is a {@code NOT NULL} foreign
     *         key and configurations are project-scoped). Thrown rather than serving a null, because
     *         the contract marks {@code configuration} required: a null there would be a response the
     *         SPA's own type says cannot happen, arriving at runtime.
     */
    private TestRunView view(TestRun run, Context context) {
        ConfigurationView configuration = context.configurations().get(run.getConfigurationId());
        if (configuration == null) {
            throw new IllegalStateException(
                    "Run " + run.getKey() + " names a configuration outside its project");
        }
        return new TestRunView(
                run.getId(),
                run.getKey(),
                run.getName(),
                run.getPlanId(),
                // Null when the run has no plan, and also when its plan has been soft-deleted —
                // both mean "no plan to link to", which is what the SPA renders.
                run.getPlanId() == null ? null : context.planKeys().get(run.getPlanId()),
                configuration,
                run.getStatus(),
                run.getReleaseVersion(),
                run.getBuildNumber(),
                run.getCommitSha(),
                run.getBranch(),
                run.getSource(),
                run.getStartedAt(),
                run.getCompletedAt(),
                context.counts().getOrDefault(
                        run.getId(), new ResultCountsView(0, 0, 0, 0, 0, 0)));
    }

    /**
     * An empty {@code releaseVersion=} is no filter at all.
     *
     * <p>The SPA clears a filter chip by removing the parameter, but a URL edited by hand or a form
     * that submits its empty input arrives as {@code ?releaseVersion=}. Treated literally that
     * matches only runs whose release is the empty string — of which there are none, ever — so the
     * list would come back empty for a filter the user believes they cleared.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
