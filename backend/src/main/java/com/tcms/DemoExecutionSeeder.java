package com.tcms;

import com.tcms.execution.ConfigurationService;
import com.tcms.execution.ConfigurationView;
import com.tcms.execution.CriterionInput;
import com.tcms.execution.DefectLinkInput;
import com.tcms.execution.DefectLinkService;
import com.tcms.execution.EvidenceService;
import com.tcms.execution.EvidenceUpload;
import com.tcms.execution.ExecutionResultRequest;
import com.tcms.execution.ExecutionService;
import com.tcms.execution.IngestionRequest;
import com.tcms.execution.IngestionService;
import com.tcms.execution.PlanCreateService;
import com.tcms.execution.PlanSectionInput;
import com.tcms.execution.PlanStatusRequest;
import com.tcms.execution.PlanStatusService;
import com.tcms.execution.PlanWriteRequest;
import com.tcms.execution.ReportFormat;
import com.tcms.execution.RunCreateService;
import com.tcms.execution.RunScheduleService;
import com.tcms.execution.RunScheduleWriteRequest;
import com.tcms.execution.RunScopeRequestView;
import com.tcms.execution.RunService;
import com.tcms.execution.RunStatusRequest;
import com.tcms.execution.RunStatusService;
import com.tcms.execution.TestExecutionSummaryView;
import com.tcms.execution.TestExecutionView;
import com.tcms.execution.TestRunDetailView;
import com.tcms.project.CaseStatus;
import com.tcms.project.CaseStatusRepository;
import com.tcms.reporting.ExecutionFactRefreshService;
import com.tcms.reporting.RollupRefreshService;
import com.tcms.repository.CaseHealthRecomputeService;
import com.tcms.repository.TestCaseDetailView;
import com.tcms.storage.BlobUpload;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives one project a plan, some runs and the results in them — plus the evidence, the defect
 * links, the CI ingestion and the schedule that hang off a run.
 *
 * <h2>Three runs, in three states, on purpose</h2>
 *
 * <p>A finished run, a run half worked, and one nobody has started. The run list is one of the few
 * screens whose whole job is to distinguish states, and a fixture where every run is COMPLETED
 * renders it as a list of identical rows. The same reasoning puts several verdicts in the finished
 * run rather than all passes: a progress bar, a donut and a pass-rate that have only ever seen
 * 100% are three things nobody has actually looked at.
 *
 * <h2>Results are recorded one at a time, through the same endpoint a tester uses</h2>
 *
 * <p>Which is what moves {@code suite_run_summary} and {@code plan_snapshot}, stamps
 * {@code case_last_result}, and lands the {@code execution_fact} rows the reports read. Writing
 * {@code test_execution} rows directly would produce a run whose executions say one thing and
 * whose rollups say another — and a reader comparing the run screen to the dashboard would be
 * looking at a defect this fixture invented.
 */
@Component
public class DemoExecutionSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoExecutionSeeder.class);

    /** The verdicts the finished run spreads across its cases, in order, repeating. */
    private static final List<String> VERDICT_CYCLE =
            List.of("Passed", "Passed", "Failed", "Passed", "Blocked", "Passed", "Skipped");

    private final PlanCreateService planCreate;
    private final PlanStatusService planStatus;
    private final RunCreateService runCreate;
    private final RunService runs;
    private final RunStatusService runStatus;
    private final RunScheduleService runSchedules;
    private final ExecutionService executions;
    private final EvidenceService evidence;
    private final DefectLinkService defects;
    private final IngestionService ingestion;
    private final ConfigurationService configurations;
    private final CaseStatusRepository caseStatuses;
    private final RollupRefreshService rollups;
    private final ExecutionFactRefreshService facts;
    private final CaseHealthRecomputeService caseHealth;

    public DemoExecutionSeeder(
            PlanCreateService planCreate,
            PlanStatusService planStatus,
            RunCreateService runCreate,
            RunService runs,
            RunStatusService runStatus,
            RunScheduleService runSchedules,
            ExecutionService executions,
            EvidenceService evidence,
            DefectLinkService defects,
            IngestionService ingestion,
            ConfigurationService configurations,
            CaseStatusRepository caseStatuses,
            RollupRefreshService rollups,
            ExecutionFactRefreshService facts,
            CaseHealthRecomputeService caseHealth) {

        this.planCreate = planCreate;
        this.planStatus = planStatus;
        this.runCreate = runCreate;
        this.runs = runs;
        this.runStatus = runStatus;
        this.runSchedules = runSchedules;
        this.executions = executions;
        this.evidence = evidence;
        this.defects = defects;
        this.ingestion = ingestion;
        this.configurations = configurations;
        this.caseStatuses = caseStatuses;
        this.rollups = rollups;
        this.facts = facts;
        this.caseHealth = caseHealth;
    }

    void seed(
            DemoCast cast,
            DemoSeedPlan.ProjectSpec spec,
            UUID projectId,
            List<TestCaseDetailView> cases,
            Map<String, UUID> suiteIds,
            DemoSeedReport report,
            String label) {

        if (cases.isEmpty()) {
            return;
        }
        boolean full = spec.depth() == DemoSeedPlan.Depth.FULL;

        Map<String, UUID> statusIds = caseStatuses.findByProjectIdOrderByPositionAscNameAsc(projectId)
                .stream()
                .collect(Collectors.toMap(CaseStatus::getName, CaseStatus::getId, (a, b) -> a));
        UUID configurationId = defaultConfiguration(cast, projectId);
        if (configurationId == null) {
            report.note(spec.key() + " has no default configuration, so no run was seeded.");
            return;
        }

        List<UUID> caseIds = cases.stream().map(TestCaseDetailView::id).toList();
        UUID planId = seedPlan(cast, spec, projectId, suiteIds, full);

        // The finished run: every case, a spread of verdicts, then closed.
        UUID completed = seedRun(cast, projectId, planId, configurationId, caseIds,
                "Regression — " + releaseOf(spec) + " release candidate", releaseOf(spec), "1841");
        if (completed != null) {
            int recorded = recordVerdicts(cast, projectId, completed, statusIds, full, true);
            runStatus.setStatus(cast.lead(), projectId, completed, new RunStatusRequest("COMPLETED"));
            report.line(label, spec.key() + " — run closed with " + recorded + " results");
        }

        if (full) {
            // The run in flight: the first three cases have verdicts and the rest do not, which is
            // the state the execution screen is actually designed around.
            UUID inProgress = seedRun(cast, projectId, planId, configurationId, caseIds,
                    "Smoke — nightly", releaseOf(spec), "1842");
            if (inProgress != null) {
                recordVerdicts(cast, projectId, inProgress, statusIds, true, false);
                report.line(label, spec.key() + " — one run in progress");
            }

            // The run nobody has opened. No results at all, so the empty execution state is
            // reachable without deleting anything.
            seedRun(cast, projectId, planId, configurationId, caseIds,
                    "Accessibility sweep — not started", releaseOf(spec), "1843");

            seedIngestion(cast, projectId, configurationId, cases, report, label);
            seedSchedule(cast, projectId, configurationId, suiteIds, report, label);
        }

        // The read models the dashboard, the plan statistics page and every report are drawn from.
        // Refreshed here rather than left to their schedulers, because a CLI run exits long before
        // either of those ticks — and a fixture whose dashboard is empty for the first quarter of
        // an hour is one whose first impression is a bug that is not there.
        rollups.refresh();
        facts.refresh();
        caseHealth.recompute();
    }

    private String releaseOf(DemoSeedPlan.ProjectSpec spec) {
        return switch (spec.key()) {
            case "WEB" -> "2026.8.0";
            case "MOB" -> "5.2.0";
            default -> "3.1.4";
        };
    }

    private UUID defaultConfiguration(DemoCast cast, UUID projectId) {
        return configurations.list(cast.adminId(), projectId).orElse(List.of()).stream()
                .filter(ConfigurationView::isDefault)
                .map(ConfigurationView::id)
                .findFirst()
                .orElse(null);
    }

    /**
     * The plan, with its scope prose, sections and criteria filled in — the plan screens render
     * all three and a plan that carries only a name leaves most of them blank.
     */
    private UUID seedPlan(
            DemoCast cast,
            DemoSeedPlan.ProjectSpec spec,
            UUID projectId,
            Map<String, UUID> suiteIds,
            boolean full) {

        List<PlanSectionInput> sections = suiteIds.entrySet().stream()
                .map(entry -> new PlanSectionInput(entry.getKey(), List.of(),
                        List.of(entry.getValue()), List.of()))
                .toList();

        PlanCreateService.Outcome outcome = planCreate.create(cast.lead(), projectId,
                new PlanWriteRequest(
                        releaseOf(spec) + " release test plan",
                        "What we will test before " + releaseOf(spec) + " ships, and what we "
                                + "will not.",
                        releaseOf(spec),
                        LocalDate.now().plusDays(21),
                        List.copyOf(suiteIds.values()),
                        List.<UUID>of(),
                        List.<String>of(),
                        sections,
                        "Everything a customer can reach from the storefront, on the two browsers "
                                + "that carry 94% of our traffic.",
                        "Back-office tooling, and the partner API — both covered by their own "
                                + "plans, both out of scope here.",
                        List.of(new CriterionInput("cases_approved", "gte", 90.0,
                                "At least 90% of the cases in scope are approved.")),
                        List.of(new CriterionInput("pass_rate", "gte", 95.0,
                                        "Pass rate at or above 95%."),
                                new CriterionInput("open_critical_defects", "eq", 0.0,
                                        "No open critical defects."))),
                DemoProjectSeeder.idempotencyKey(projectId, "plan", releaseOf(spec)));

        UUID planId = switch (outcome) {
            case PlanCreateService.Outcome.Created created -> created.view().id();
            case PlanCreateService.Outcome.Replayed replayed -> replayed.view().id();
            default -> null;
        };
        if (planId != null && full) {
            planStatus.setStatus(cast.lead(), projectId, planId, new PlanStatusRequest("IN_REVIEW"));
            planStatus.setStatus(cast.lead(), projectId, planId, new PlanStatusRequest("APPROVED"));
        }
        return planId;
    }

    private UUID seedRun(
            DemoCast cast,
            UUID projectId,
            UUID planId,
            UUID configurationId,
            List<UUID> caseIds,
            String name,
            String release,
            String build) {

        RunCreateService.Outcome outcome = runCreate.create(cast.lead(), projectId,
                new com.tcms.execution.RunWriteRequest(
                        name, planId, configurationId, release, build,
                        "9f2c1ab", "release/" + release, caseIds, List.of(), List.of(),
                        cast.engineer(), null),
                DemoProjectSeeder.idempotencyKey(projectId, "run", name));

        return switch (outcome) {
            case RunCreateService.Outcome.Created created -> created.view().id();
            case RunCreateService.Outcome.Replayed replayed -> replayed.view().id();
            default -> {
                log.warn("Run '{}' was refused: {}", name, outcome.getClass().getSimpleName());
                yield null;
            }
        };
    }

    /**
     * Records a verdict per execution, with per-step results.
     *
     * @param everything when false only the first three executions get a verdict, which is what
     *                   leaves a run legibly in progress
     * @return how many verdicts were recorded
     */
    private int recordVerdicts(
            DemoCast cast,
            UUID projectId,
            UUID runId,
            Map<String, UUID> statusIds,
            boolean withEvidence,
            boolean everything) {

        Optional<TestRunDetailView> detail = runs.findOne(cast.lead(), projectId, runId);
        if (detail.isEmpty()) {
            return 0;
        }
        List<TestExecutionSummaryView> targets = detail.get().executions();

        int recorded = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (!everything && i >= 3) {
                break;
            }
            TestExecutionSummaryView target = targets.get(i);
            String verdict = VERDICT_CYCLE.get(i % VERDICT_CYCLE.size());
            UUID statusId = statusIds.get(verdict);
            if (statusId == null) {
                continue;
            }

            // The tester alternates so the run's "executed by" column and the report's per-tester
            // section have more than one person in them.
            UUID tester = i % 2 == 0 ? cast.engineer() : cast.tester();

            List<ExecutionResultRequest.StepResultRequest> steps =
                    stepResults(cast, projectId, runId, target.id(), statusIds, verdict);

            ExecutionService.Outcome outcome = executions.record(tester, projectId, runId,
                    target.id(),
                    new ExecutionResultRequest(statusId, commentFor(verdict),
                            120_000L + (i * 9_000L), steps),
                    null);

            if (!(outcome instanceof ExecutionService.Outcome.Recorded)) {
                log.warn("Result for execution {} was refused: {}", target.id(),
                        outcome.getClass().getSimpleName());
                continue;
            }
            recorded++;

            if (withEvidence && "Failed".equals(verdict)) {
                attachFailureTrail(cast, projectId, runId, target.id(), tester);
            }
        }
        return recorded;
    }

    /**
     * A per-step verdict for every step of the case: all passing except the last, which takes the
     * execution's own verdict when that verdict is not a pass. That shape is the one worth seeing —
     * a failed execution whose steps are all green is a screen nobody can read.
     */
    private List<ExecutionResultRequest.StepResultRequest> stepResults(
            DemoCast cast,
            UUID projectId,
            UUID runId,
            UUID executionId,
            Map<String, UUID> statusIds,
            String verdict) {

        Optional<TestExecutionView> found =
                executions.findOne(cast.lead(), projectId, runId, executionId);
        if (found.isEmpty() || found.get().steps().isEmpty()) {
            return List.of();
        }
        var steps = found.get().steps();
        UUID passed = statusIds.get("Passed");
        UUID last = statusIds.getOrDefault(verdict, passed);

        List<ExecutionResultRequest.StepResultRequest> results = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            boolean isLast = i == steps.size() - 1;
            results.add(new ExecutionResultRequest.StepResultRequest(
                    steps.get(i).stepId(),
                    isLast ? last : passed,
                    isLast && !"Passed".equals(verdict)
                            ? "This is the step it goes wrong on."
                            : null));
        }
        return results;
    }

    private static String commentFor(String verdict) {
        return switch (verdict) {
            case "Failed" -> "Reproduced twice on the release candidate. Raised as NW-2214.";
            case "Blocked" -> "The payments sandbox was returning 503 for the whole window.";
            case "Skipped" -> "Out of scope for this build — the feature flag is off.";
            default -> null;
        };
    }

    /**
     * What a real failure leaves behind: a screenshot on the execution and a link to the defect
     * somebody raised for it.
     */
    private void attachFailureTrail(
            DemoCast cast, UUID projectId, UUID runId, UUID executionId, UUID tester) {

        byte[] bytes = ("Screenshot placeholder for the failing step. A one-pixel PNG would render "
                + "as a broken image; this is a text file, and the evidence list shows its type.")
                .getBytes(StandardCharsets.UTF_8);

        evidence.attach(tester, projectId, runId, executionId, null,
                new EvidenceUpload(
                        new BlobUpload("failure-notes.txt", "text/plain", bytes.length, bytes),
                        "What the page looked like when the card was declined."));

        defects.link(tester, projectId, runId, executionId,
                new DefectLinkInput("NW-2214",
                        "https://issues.northwind.test/browse/NW-2214", null));
    }

    /**
     * A CI-shaped run: a JUnit report posted at the ingestion endpoint, which is the only way an
     * {@code ingestion_job} row and a run with {@code source = CI} come to exist.
     */
    private void seedIngestion(
            DemoCast cast,
            UUID projectId,
            UUID configurationId,
            List<TestCaseDetailView> cases,
            DemoSeedReport report,
            String label) {

        List<String> keys = cases.stream()
                .map(TestCaseDetailView::automationKey)
                .filter(key -> key != null && !key.isBlank())
                .toList();
        if (keys.isEmpty()) {
            return;
        }

        StringBuilder xml = new StringBuilder(
                "<testsuite name=\"nightly\" tests=\"" + keys.size() + "\">\n");
        for (int i = 0; i < keys.size(); i++) {
            xml.append("  <testcase name=\"").append(keys.get(i)).append("\" time=\"")
                    .append(1.5 + i).append("\"");
            if (i == 1) {
                xml.append(">\n    <failure message=\"expected 34.00 but was 38.99\"/>\n  </testcase>\n");
            } else if (i == 2) {
                xml.append(">\n    <skipped/>\n  </testcase>\n");
            } else {
                xml.append("/>\n");
            }
        }
        xml.append("</testsuite>\n");

        IngestionService.Outcome outcome = ingestion.ingest(cast.engineer(), projectId,
                new IngestionRequest(ReportFormat.JUNIT, xml.toString(), null,
                        "CI — nightly automation", configurationId, "2026.8.0", "1844",
                        "9f2c1ab", "main"),
                DemoProjectSeeder.idempotencyKey(projectId, "ingestion", "nightly"));

        if (outcome instanceof IngestionService.Outcome.Ingested) {
            report.line(label, "one CI-ingested run and its ingestion job");
        } else {
            report.note("ingestion was refused: " + outcome.getClass().getSimpleName());
        }
    }

    /** One schedule, so the run schedule screen is not an empty state. */
    private void seedSchedule(
            DemoCast cast,
            UUID projectId,
            UUID configurationId,
            Map<String, UUID> suiteIds,
            DemoSeedReport report,
            String label) {

        UUID smoke = suiteIds.values().stream().findFirst().orElse(null);
        if (smoke == null) {
            return;
        }
        RunScheduleService.Outcome outcome = runSchedules.create(cast.lead(), projectId,
                new RunScheduleWriteRequest(
                        "Nightly smoke",
                        "0 30 2 * * *",
                        "Europe/Sofia",
                        new RunScopeRequestView(null, List.of(), List.of(smoke), List.of()),
                        configurationId,
                        cast.engineer(),
                        true));

        if (outcome instanceof RunScheduleService.Outcome.Created) {
            report.line(label, "a nightly run schedule");
        }
    }
}
