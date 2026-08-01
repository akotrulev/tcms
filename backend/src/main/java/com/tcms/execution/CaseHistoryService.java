package com.tcms.execution;

import com.tcms.execution.CaseHistoryView.EntryView;
import com.tcms.execution.CaseHistoryView.MarkerView;
import com.tcms.execution.CaseHistoryView.SummaryView;
import com.tcms.project.CaseStatusView;
import com.tcms.project.ProjectService;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One case's execution history (plan §6p) — <em>"has this been passing, and did the edit break
 * it?"</em>
 *
 * <p>This is the screen the version pin exists for. Every execution stores the
 * {@code case_version_id} it ran against (§42), so an old failure can be attributed to the text that
 * was actually executed rather than to today's. Without that column this page could only lie
 * politely; with it, "green, green, <strong>edited to v5</strong>, red, red" is readable, and that
 * shape answers the single most common question asked of a failing test.
 *
 * <h2>Computed on read, and §6p specifies otherwise</h2>
 *
 * <p>§6p calls for this to be served from an {@code ExecutionFact} read model with a pre-aggregated
 * per-{@code (case, version)} rollup. That model belongs to the {@code reporting} module, which does
 * not exist. So this recomputes from {@code test_execution} per request — the same deliberate
 * deviation {@link PlanStatisticsService} records, with the same benefit (a figure recomputed per
 * request cannot be stale against a result recorded a second ago, which is exactly what a tester
 * checks after saving one) and the same cost, bounded here by one case's execution count.
 *
 * <p><strong>Unpaginated, and §6p asks for 50-at-a-time with infinite scroll.</strong> Stated as a
 * limit rather than solved quietly: the contract declares no paging parameters on this path and no
 * consumer sends any, so adding a server-side cap would silently truncate a timeline whose whole
 * value is completeness. A nightly-automated case accumulating thousands of executions is the case
 * that breaks it, and the repair is contract-first — paging parameters, then this query, then the
 * page — not a magic number here.
 *
 * <h2>Deleted runs are kept and marked</h2>
 *
 * <p>§6p: "Deleted runs must not orphan history — soft delete only, with the entry marked rather
 * than vanishing. History that quietly loses rows is worse than no history." So
 * {@link TestExecutionRepository#findForCase} deliberately does <em>not</em> filter
 * {@code deletedAt}, unlike its sibling {@code findForPlan}, and every entry carries
 * {@link EntryView#runDeleted}. The contrast between those two queries is the rule.
 */
@Service
public class CaseHistoryService {

    /** What the client asked the timeline to cover. */
    enum Scope {
        CURRENT_VERSION,
        ALL_VERSIONS
    }

    private final ProjectService projects;
    private final TestExecutionRepository executions;
    private final ConfigurationRepository configurations;
    private final TestPlanRepository plans;
    private final ResultStatuses statuses;
    private final ExecutionPeople people;
    private final CaseFacts cases;

    public CaseHistoryService(
            ProjectService projects,
            TestExecutionRepository executions,
            ConfigurationRepository configurations,
            TestPlanRepository plans,
            ResultStatuses statuses,
            ExecutionPeople people,
            CaseFacts cases) {

        this.projects = projects;
        this.executions = executions;
        this.configurations = configurations;
        this.plans = plans;
        this.statuses = statuses;
        this.people = people;
        this.cases = cases;
    }

    /**
     * One case's history, or empty for any of the ways the case can be unreachable.
     *
     * <p>Two gates, the same pair every project-scoped read in this module uses: the project gate
     * answers "may this caller see this project at all", and {@link CaseFacts#currentVersionNo}
     * matches the case against that project, so a case id from a sibling project does not resolve
     * through a path naming a project the caller does hold. Both collapse into an empty
     * {@link Optional} — 404, never 403 (§6t).
     *
     * @param versionNo an explicit version, which wins over {@code scope} when both are sent; null
     *                  to let {@code scope} decide
     */
    @Transactional(readOnly = true)
    public Optional<CaseHistoryView> history(
            UUID userId, UUID projectId, UUID caseId, Scope scope, Integer versionNo) {

        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> cases.currentVersionNo(project.getId(), caseId)
                        .map(currentVersionNo ->
                                compute(companyId, project.getId(), caseId, currentVersionNo,
                                        scope, versionNo)));
    }

    private CaseHistoryView compute(
            UUID companyId,
            UUID projectId,
            UUID caseId,
            int currentVersionNo,
            Scope scope,
            Integer requestedVersionNo) {

        Map<UUID, CaseStatusView> statusesById = statuses.byId(projectId);
        List<Row> all = recorded(rows(projectId, caseId));

        // Resolved once, from the same source the entries are rendered from, so the number in the
        // scope sentence and the number beside every row cannot disagree.
        Integer pinnedVersion = switch (scope) {
            case ALL_VERSIONS -> requestedVersionNo;
            case CURRENT_VERSION -> requestedVersionNo != null ? requestedVersionNo : currentVersionNo;
        };

        List<Row> inScope = pinnedVersion == null
                ? all
                : all.stream().filter(row -> row.caseVersionNo() == pinnedVersion).toList();

        return new CaseHistoryView(
                summary(inScope, statusesById, currentVersionNo, pinnedVersion),
                entries(companyId, projectId, inScope, statusesById),
                markers(companyId, caseId, pinnedVersion));
    }

    // ------------------------------------------------------------------ rows ---

    /**
     * One execution joined to the run that holds it, with its pinned version number resolved.
     *
     * <p>The version number comes from the snapshot rather than from the live case, which is the
     * whole point of the pin: an execution beside "v3" showing today's version would be two claims
     * that contradict each other on one line.
     */
    private record Row(TestExecution execution, TestRun run, int caseVersionNo) {

        Instant executedAt() {
            return execution.getExecutedAt();
        }

        /**
         * The release this result is about — the execution's own, falling back to its run's.
         *
         * <p>{@code test_execution.release_version} is denormalized from the run at creation (§6q),
         * so the two agree today. The fallback covers a row written before that denormalization or
         * by a future ingestion path that sets only the run's, and it costs one null check to make
         * "first failed in 4.3" answerable rather than blank.
         */
        String releaseVersion() {
            return execution.getReleaseVersion() != null
                    ? execution.getReleaseVersion()
                    : run.getReleaseVersion();
        }
    }

    /**
     * Drops the execution rows nobody has recorded — <strong>an execution that has not happened is
     * not history.</strong>
     *
     * <p>Opening a run materialises one row per case in its scope, carrying no status and no
     * {@code executed_at}. Those rows are real and they matter — they are the checklist — but they
     * belong to the run screen and to the plan's coverage figure, which is where un-started work is
     * already reported. On a timeline they are noise with no date, and a case sitting in five open
     * runs would show five entries claiming nothing.
     *
     * <p><strong>The contract settles it rather than taste:</strong> {@code executedAt} is a
     * required property of {@code ExecutionHistoryEntry}, so an unrecorded row has no representation
     * on the wire — the generated client type says the field is always a string, and the SPA sorts
     * the timeline by it. Emitting null there would break the sort of the one list this endpoint
     * exists to produce. Found by this endpoint's own test, before any of it reached a browser.
     *
     * <p>Filtered once, here, rather than in each of the four consumers: the summary, the flakiness
     * grouping, the failing-streak walk and the entry list must all agree about which rows exist, and
     * four independent skips are four chances for one of them to disagree.
     */
    private static List<Row> recorded(List<Row> rows) {
        return rows.stream().filter(row -> row.executedAt() != null).toList();
    }

    private List<Row> rows(UUID projectId, UUID caseId) {
        List<Object[]> raw = executions.findForCase(projectId, caseId);
        if (raw.isEmpty()) {
            return List.of();
        }

        // One batch for every version the case has ever been executed at, rather than one lookup
        // per row: a case executed nightly for a year has thousands of rows and a handful of
        // versions among them.
        Set<UUID> versionIds = new HashSet<>();
        for (Object[] row : raw) {
            versionIds.add(((TestExecution) row[0]).getCaseVersionId());
        }
        Map<UUID, CaseFacts.PinnedCase> pinned = cases.pinnedCases(versionIds);

        List<Row> rows = new ArrayList<>(raw.size());
        for (Object[] row : raw) {
            TestExecution execution = (TestExecution) row[0];
            CaseFacts.PinnedCase pin = pinned.get(execution.getCaseVersionId());
            // An unresolvable pin is a version the caller's company does not own, which RLS has
            // already refused. Version 0 rather than dropping the row: the result happened, and
            // omitting it would shorten history for the same reason a deleted run must not.
            rows.add(new Row(execution, (TestRun) row[1], pin == null ? 0 : pin.versionNo()));
        }
        return List.copyOf(rows);
    }

    // --------------------------------------------------------------- summary ---

    /**
     * The strip above the timeline.
     *
     * @param pinnedVersion the version every figure here is about, or null for all versions — which
     *                      is what makes the {@code scope} sentence true rather than decorative
     */
    private static SummaryView summary(
            List<Row> rows,
            Map<UUID, CaseStatusView> statusesById,
            int currentVersionNo,
            Integer pinnedVersion) {

        long passed = 0;
        long failed = 0;
        long executed = 0;
        Instant lastExecutedAt = null;

        // Every row here carries a verdict and a timestamp — see `recorded`, which is the one
        // place that is established.
        for (Row row : rows) {
            executed++;
            switch (kindOf(row, statusesById)) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                default -> {
                    // Blocked, skipped and the two not-run kinds are executions but not verdicts of
                    // either kind. They are in `executions` and in neither `passed` nor `failed`,
                    // which is why the SPA renders "N passed, of M executions" rather than implying
                    // the two numbers partition the set.
                }
            }
            lastExecutedAt = later(lastExecutedAt, row.executedAt());
        }

        Streak streak = currentFailingStreak(rows, statusesById);

        return new SummaryView(
                pinnedVersion == null ? "All versions" : "Current version (v" + pinnedVersion + ")",
                currentVersionNo,
                executed,
                passed,
                failed,
                lastExecutedAt,
                flakiness(rows, statusesById),
                streak == null ? null : streak.at(),
                streak == null ? null : streak.releaseVersion());
    }

    /**
     * Pass/fail alternation <strong>within a single version and configuration</strong> — the only
     * scoping under which flakiness means anything (§6p).
     *
     * <p>The scoping is the whole measurement, not a refinement of it. Alternation <em>across</em>
     * versions is just change: a test that failed, was fixed, and now passes alternated once, and
     * reporting that as flake accuses the test of being unreliable when somebody repaired it.
     * Alternation across <em>configurations</em> is a different false signal — "passes on Chrome,
     * fails on Safari" is a finding, and averaging it into one flake number is how it stops being
     * one.
     *
     * <p>Counted over consecutive <em>verdict-bearing</em> results in time order, so an unrecorded
     * row sitting between two failures does not read as two transitions. Only PASSED and FAILED
     * take part: blocked and skipped are statements about the environment and about a decision, and
     * a run alternating between failed and blocked is an unstable environment rather than a flaky
     * test.
     *
     * @return transitions ÷ adjacent pairs across every (version, configuration) group, or
     *         <strong>null when there is no pair anywhere</strong> — one result cannot alternate,
     *         and reporting 0.0 there would state that a test executed once is stable
     */
    private static Double flakiness(List<Row> rows, Map<UUID, CaseStatusView> statusesById) {
        Map<List<Object>, List<StatusKind>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            StatusKind kind = kindOf(row, statusesById);
            if (kind != StatusKind.PASSED && kind != StatusKind.FAILED) {
                continue;
            }
            groups.computeIfAbsent(
                            List.of(row.caseVersionNo(), row.run().getConfigurationId()),
                            key -> new ArrayList<>())
                    .add(kind);
        }

        long transitions = 0;
        long pairs = 0;
        for (List<StatusKind> series : groups.values()) {
            for (int i = 1; i < series.size(); i++) {
                pairs++;
                if (series.get(i) != series.get(i - 1)) {
                    transitions++;
                }
            }
        }
        return pairs == 0 ? null : transitions / (double) pairs;
    }

    private record Streak(Instant at, String releaseVersion) {
    }

    /**
     * The earliest execution of the <strong>current</strong> failing streak, with the release in
     * force at that point (§6p).
     *
     * <p>Not the earliest failure ever recorded, which is the reading the field name invites and the
     * one that makes the panel useless: a case that failed once in March and has passed every night
     * since would carry a permanent warning banner dated March. §6p says "the earliest execution of
     * the current failing streak", and the SPA renders it as an {@code Alert severity="warning"} —
     * a claim about now.
     *
     * <p>So: walk backwards from the most recent verdict while it is FAILED, and answer the oldest
     * one reached. If the latest verdict is anything else, the streak is over and this is null.
     * Blocked and skipped results are passed over rather than ending the streak: neither is a
     * statement that the test now works, and letting a blocked night clear a failing streak would
     * date the banner to after the environment came back rather than to when the test broke.
     */
    private static Streak currentFailingStreak(
            List<Row> rows, Map<UUID, CaseStatusView> statusesById) {

        List<Row> verdicts = rows.stream()
                .filter(row -> {
                    StatusKind kind = kindOf(row, statusesById);
                    return kind == StatusKind.PASSED || kind == StatusKind.FAILED;
                })
                .toList();

        Streak oldest = null;
        for (int i = verdicts.size() - 1; i >= 0; i--) {
            Row row = verdicts.get(i);
            if (kindOf(row, statusesById) != StatusKind.FAILED) {
                break;
            }
            oldest = new Streak(row.executedAt(), row.releaseVersion());
        }
        return oldest;
    }

    // --------------------------------------------------------------- entries ---

    /**
     * The timeline rows, newest first.
     *
     * <p>Four bulk lookups for the whole timeline and none per row — the discipline
     * {@link ExecutionViews} sets out: the people, the plan keys, the configuration names, the
     * defect links and the evidence counts are each one query regardless of how many executions the
     * case has.
     */
    private List<EntryView> entries(
            UUID companyId,
            UUID projectId,
            List<Row> rows,
            Map<UUID, CaseStatusView> statusesById) {

        if (rows.isEmpty()) {
            return List.of();
        }

        Set<UUID> userIds = new HashSet<>();
        Set<UUID> planIds = new HashSet<>();
        List<UUID> executionIds = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row.execution().getExecutedBy() != null) {
                userIds.add(row.execution().getExecutedBy());
            }
            if (row.run().getPlanId() != null) {
                planIds.add(row.run().getPlanId());
            }
            executionIds.add(row.execution().getId());
        }

        Map<UUID, UserRefView> resolved = people.resolve(companyId, userIds);
        Map<UUID, String> configurationNames = configurationNames(projectId);
        Map<UUID, String> planKeys = planKeys(planIds);
        Map<UUID, List<DefectLinkView>> defects = defectsByExecution(executionIds);
        Map<UUID, Long> evidence = evidenceByExecution(executionIds);

        List<EntryView> entries = new ArrayList<>(rows.size());
        // Reversed rather than re-sorted: the query already ordered ascending with a stable
        // tiebreak, so the descending order the page reads is that sequence backwards. Re-sorting
        // here would be a second ordering rule to keep in step with the one the flakiness walk
        // above depends on, and the two must walk the same sequence.
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row row = rows.get(i);
            TestExecution execution = row.execution();
            TestRun run = row.run();
            CaseStatusView status = execution.getStatusId() == null
                    ? null
                    : statusesById.get(execution.getStatusId());

            entries.add(new EntryView(
                    execution.getId(),
                    ResultStatuses.countable(status),
                    // The project's own word for it, and its own colour. A status id that resolves
                    // to nothing is a vocabulary row deleted under a result that referenced it;
                    // "Untested" is what the kind above already says, so the two agree.
                    status == null ? "Untested" : status.name(),
                    status == null ? UNRECORDED_COLOR : status.color(),
                    row.caseVersionNo(),
                    row.releaseVersion(),
                    run.getBuildNumber(),
                    run.getId(),
                    run.getKey(),
                    run.getName(),
                    run.getDeletedAt() != null,
                    run.getPlanId() == null ? null : planKeys.get(run.getPlanId()),
                    configurationNames.getOrDefault(
                            run.getConfigurationId(), String.valueOf(run.getConfigurationId())),
                    execution.getExecutedBy() == null
                            ? null
                            : resolved.get(execution.getExecutedBy()),
                    // Null until M3 ingestion issues tokens — see EntryView#executedByToken.
                    null,
                    run.getSource(),
                    execution.getExecutedAt(),
                    execution.getDurationMs(),
                    evidence.getOrDefault(execution.getId(), 0L),
                    defects.getOrDefault(execution.getId(), List.of())));
        }
        return List.copyOf(entries);
    }

    /**
     * The colour of a result with no status.
     *
     * <p>{@code statusColor} is required on the wire and drives the timeline's rail dot, so it
     * cannot be null; a row nobody has recorded is genuinely colourless, and the neutral grey says
     * that rather than borrowing a verdict's colour. Matches the SPA's own untested treatment.
     */
    private static final String UNRECORDED_COLOR = "#9ca3af";

    private Map<UUID, String> configurationNames(UUID projectId) {
        Map<UUID, String> names = new HashMap<>();
        for (Configuration configuration
                : configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId)) {
            names.put(configuration.getId(), configuration.getName());
        }
        return names;
    }

    private Map<UUID, String> planKeys(Set<UUID> planIds) {
        Map<UUID, String> keys = new HashMap<>();
        if (planIds.isEmpty()) {
            return keys;
        }
        for (TestPlan plan : plans.findAllById(planIds)) {
            keys.put(plan.getId(), plan.getKey());
        }
        return keys;
    }

    private Map<UUID, List<DefectLinkView>> defectsByExecution(List<UUID> executionIds) {
        Map<UUID, List<DefectLinkView>> byExecution = new HashMap<>();
        for (Object[] row : executions.findDefectLinks(executionIds)) {
            byExecution
                    .computeIfAbsent((UUID) row[1], id -> new ArrayList<>())
                    .add(new DefectLinkView(
                            (UUID) row[0],
                            (String) row[2],
                            (String) row[3],
                            (String) row[4],
                            (String) row[5]));
        }
        return byExecution;
    }

    private Map<UUID, Long> evidenceByExecution(List<UUID> executionIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : executions.countEvidence(executionIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    // --------------------------------------------------------------- markers ---

    /**
     * The version markers, filtered to the scope the entries are.
     *
     * <p>Under {@code CURRENT_VERSION} only that version's own marker survives, and keeping it is
     * deliberate: it is the moment the scope <em>begins</em>, so the timeline reads "edited to v7"
     * and then every result against v7 — which is the causation shape the screen exists for. Showing
     * the other versions' markers there would put edits on a timeline holding none of their results.
     */
    private List<MarkerView> markers(UUID companyId, UUID caseId, Integer pinnedVersion) {
        List<CaseFacts.VersionMark> marks = cases.versionMarkers(caseId);

        Set<UUID> authorIds = new HashSet<>();
        for (CaseFacts.VersionMark mark : marks) {
            if (mark.authorId() != null) {
                authorIds.add(mark.authorId());
            }
        }
        Map<UUID, UserRefView> resolved = people.resolve(companyId, authorIds);

        List<MarkerView> markers = new ArrayList<>();
        for (CaseFacts.VersionMark mark : marks) {
            if (pinnedVersion != null && mark.versionNo() != pinnedVersion) {
                continue;
            }
            markers.add(new MarkerView(
                    mark.versionNo(),
                    mark.occurredAt(),
                    // Null when the author cannot be named — the contract says so as of this slice,
                    // and `repository`'s version list has shipped it since §35.
                    mark.authorId() == null ? null : resolved.get(mark.authorId()),
                    mark.changeComment()));
        }
        return List.copyOf(markers);
    }

    // ----------------------------------------------------------------- shared ---

    private static StatusKind kindOf(Row row, Map<UUID, CaseStatusView> statusesById) {
        UUID statusId = row.execution().getStatusId();
        return ResultStatuses.countable(statusId == null ? null : statusesById.get(statusId));
    }

    private static Instant later(Instant held, Instant candidate) {
        if (candidate == null) {
            return held;
        }
        return held == null || candidate.isAfter(held) ? candidate : held;
    }
}
