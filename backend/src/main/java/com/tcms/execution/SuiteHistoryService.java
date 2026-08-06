package com.tcms.execution;

import com.tcms.execution.CaseHistoryView.MarkerView;
import com.tcms.execution.SuiteHistoryView.EntryView;
import com.tcms.project.CaseStatusView;
import com.tcms.project.ProjectService;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * One suite's execution history (plan §6p) — <em>"is this area getting better or worse?"</em>
 *
 * <p>The trap the screen is built around, and therefore the trap this service must not hide: a
 * suite's pass rate dropping between two runs looks like a regression, but if four cases were
 * <em>added</em> in between, the drop is new coverage finding new problems. So every entry carries
 * both the suite version it ran under and the case count it actually covered, and the version marks
 * are returned alongside — the page draws the boundary between them and refuses to compare across
 * it silently.
 *
 * <h2>Computed on read, and §6p specifies otherwise</h2>
 *
 * <p>§6p serves this from {@code suite_run_summary}, a read model keyed
 * {@code (run, suite, configuration)} and updated as results land. That table exists in {@code V1}
 * and has no writer, because the {@code reporting} module does not exist. So this recomputes from
 * {@code test_execution} per request — the same deliberate deviation {@link PlanStatisticsService}
 * and {@link CaseHistoryService} each record, with the same benefit (a figure recomputed per request
 * cannot be stale against a result recorded a second ago) and the same cost. The cost is bounded
 * here by grouping in the database rather than in Java: see
 * {@link TestExecutionRepository#tallyForCases}.
 *
 * <h2>What this page cannot yet show, stated rather than faked</h2>
 *
 * <p><strong>The version-marker rail is structurally empty in every deployment today.</strong>
 * {@code repository.SuiteVersions} writes v1 at creation and nothing since — a rename is content
 * rather than composition, and the moves that would change composition are not built. So every entry
 * resolves to v1, the SPA's "the membership changed during this period" notice never fires, and that
 * is honest: the membership genuinely has not changed. The wiring is here so that the day a case
 * move writes v2, this screen shows it without a backfill.
 */
@Service
public class SuiteHistoryService {

    private final ProjectService projects;
    private final TestExecutionRepository executions;
    private final TestRunRepository runs;
    private final ConfigurationRepository configurations;
    private final ResultStatuses statuses;
    private final ExecutionPeople people;
    private final CaseFacts cases;

    public SuiteHistoryService(
            ProjectService projects,
            TestExecutionRepository executions,
            TestRunRepository runs,
            ConfigurationRepository configurations,
            ResultStatuses statuses,
            ExecutionPeople people,
            CaseFacts cases) {

        this.projects = projects;
        this.executions = executions;
        this.runs = runs;
        this.configurations = configurations;
        this.statuses = statuses;
        this.people = people;
        this.cases = cases;
    }

    /**
     * One suite's history, or empty for any of the ways the suite can be unreachable.
     *
     * <p>Two gates, the pair every project-scoped read in this module uses: the project gate answers
     * "may this caller see this project at all", and {@link CaseFacts#liveSuiteIds} matches the suite
     * against that project — so a suite id from a sibling project does not resolve through a path
     * naming a project the caller does hold. Both collapse into an empty {@link Optional} — 404,
     * never 403 (§6t).
     *
     * <p>An empty subtree is <strong>not</strong> an empty Optional. A folder with no cases exists and
     * has a history of nothing, and answering 404 there would tell a reader their suite is gone.
     */
    @Transactional(readOnly = true)
    public Optional<SuiteHistoryView> history(UUID userId, UUID projectId, UUID suiteId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .filter(project -> !cases.liveSuiteIds(project.getId(), Set.of(suiteId)).isEmpty())
                .map(project -> compute(companyId, project.getId(), suiteId));
    }

    private SuiteHistoryView compute(UUID companyId, UUID projectId, UUID suiteId) {
        List<CaseFacts.VersionMark> marks = cases.suiteVersionMarkers(suiteId);
        List<UUID> caseIds = cases.caseIdsInSuite(projectId, suiteId);

        // computedAt is null while this class still computes live. Phase 4 gives it the rollup's
        // own updated_at; shipping the field truthful before it is interesting is what lets the
        // client be built against the shape rather than against a plan to add it.
        return new SuiteHistoryView(
                entries(projectId, caseIds, marks), markers(companyId, marks), null);
    }

    // --------------------------------------------------------------- entries ---

    /** A run's tallies, accumulated as the (run × status) rows arrive. */
    private static final class Tally {
        private long passed;
        private long failed;
        private long blocked;
        private long skipped;
        private long notRun;
        private Instant lastExecutedAt;

        void add(StatusKind kind, long count, Instant executedAt) {
            switch (kind) {
                case PASSED -> passed += count;
                case FAILED -> failed += count;
                case BLOCKED -> blocked += count;
                case SKIPPED -> skipped += count;
                case UNTESTED, RETEST, WIP -> notRun += count;
            }
            if (executedAt != null && (lastExecutedAt == null || executedAt.isAfter(lastExecutedAt))) {
                lastExecutedAt = executedAt;
            }
        }

        ResultCountsView counts() {
            return new ResultCountsView(
                    passed, failed, blocked, skipped, notRun,
                    passed + failed + blocked + skipped + notRun);
        }
    }

    private List<EntryView> entries(
            UUID projectId, List<UUID> caseIds, List<CaseFacts.VersionMark> marks) {

        if (caseIds.isEmpty()) {
            // An empty IN list is a SQL syntax error, and an empty folder is ordinary.
            return List.of();
        }

        Map<UUID, CaseStatusView> statusesById = statuses.byId(projectId);

        // Insertion-ordered so the assembly below is deterministic; the final order is set at the
        // end, by time, which is the only order this page's rows mean anything in.
        Map<UUID, Tally> byRun = new LinkedHashMap<>();
        for (Object[] row : executions.tallyForCases(projectId, caseIds)) {
            UUID runId = (UUID) row[0];
            UUID statusId = (UUID) row[1];
            byRun.computeIfAbsent(runId, id -> new Tally())
                    .add(
                            ResultStatuses.countable(statusId == null ? null : statusesById.get(statusId)),
                            ((Number) row[2]).longValue(),
                            (Instant) row[3]);
        }

        if (byRun.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> configurationNames = configurationNames(projectId);

        List<EntryView> entries = new ArrayList<>(byRun.size());
        for (TestRun run : runs.findAllById(byRun.keySet())) {
            Tally tally = byRun.get(run.getId());

            // The run's own start when the suite has no recorded result in it yet. A run that has
            // opened but recorded nothing is still history — "this suite is in flight" — and the
            // contract marks executedAt required, so it cannot be answered with null. createdAt is
            // the last fallback: started_at is nullable, and a run always has a creation time.
            Instant executedAt = tally.lastExecutedAt != null
                    ? tally.lastExecutedAt
                    : run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();

            entries.add(new EntryView(
                    run.getId(),
                    run.getKey(),
                    run.getName(),
                    versionAt(marks, executedAt),
                    // The configuration's own name, falling back to its id rather than to a blank:
                    // the contract marks it required, and a vocabulary row deleted under a finished
                    // run should leave a row a reader can still match against something.
                    configurationNames.getOrDefault(
                            run.getConfigurationId(), String.valueOf(run.getConfigurationId())),
                    run.getReleaseVersion(),
                    executedAt,
                    tally.counts(),
                    run.getDeletedAt() != null));
        }

        // Oldest first, with the key as a stable tiebreak. The SPA sorts newest-first for display
        // and the two orders are independent on purpose: a client that re-sorts still needs the
        // sequence to be the same one on every request, or two loads of the same page disagree
        // about which run came first.
        entries.sort(Comparator.comparing(EntryView::executedAt).thenComparing(EntryView::runKey));
        return List.copyOf(entries);
    }

    private Map<UUID, String> configurationNames(UUID projectId) {
        Map<UUID, String> names = new HashMap<>();
        for (Configuration configuration
                : configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId)) {
            names.put(configuration.getId(), configuration.getName());
        }
        return names;
    }

    /**
     * The suite version in force at a moment — the newest mark authored at or before it.
     *
     * <p><strong>Not {@code suite.current_version_no}</strong>, which is the version now. Stamping
     * today's number on a run from March would put this year's membership under last year's pass
     * rate, and the version column exists precisely so a reader can tell those apart.
     *
     * <p>Falls back to 1, which is the column default on every suite row and therefore the honest
     * answer for the two populations that reach it: suites created before {@code SuiteVersions}
     * existed and have no marks at all, and executions predating a suite's first recorded version.
     *
     * <p><strong>Every mark is examined; the walk does not stop at the first one in the future.</strong>
     * The marks arrive in {@code version_no} order and it is tempting to break there, but that makes
     * the answer depend on version order and authoring order agreeing — and nothing enforces that:
     * {@code created_at} is a plain column, and a version row written by a backfill or an import
     * carries whatever timestamp it was given. A wrong break returns the version <em>before</em> a
     * gap rather than the one in force, silently. Scanning is a handful of rows.
     */
    private static int versionAt(List<CaseFacts.VersionMark> marks, Instant moment) {
        int version = 1;
        Instant authored = null;
        for (CaseFacts.VersionMark mark : marks) {
            if (mark.occurredAt() == null || mark.occurredAt().isAfter(moment)) {
                continue;
            }
            // The latest mark at or before the moment; the higher version number breaks a tie,
            // which is the order two versions written in the same millisecond happened in.
            if (authored == null
                    || mark.occurredAt().isAfter(authored)
                    || (mark.occurredAt().equals(authored) && mark.versionNo() > version)) {
                authored = mark.occurredAt();
                version = mark.versionNo();
            }
        }
        return version;
    }

    // --------------------------------------------------------------- markers ---

    /**
     * Every recorded version of the suite, oldest first.
     *
     * <p>Unfiltered, unlike {@link CaseHistoryService#markers}, because this endpoint has no scope
     * parameter to filter against: the case timeline narrows to one version by default and its
     * markers follow that scope, while a suite's history is always the whole of it.
     */
    private List<MarkerView> markers(UUID companyId, List<CaseFacts.VersionMark> marks) {
        Set<UUID> authorIds = new HashSet<>();
        for (CaseFacts.VersionMark mark : marks) {
            if (mark.authorId() != null) {
                authorIds.add(mark.authorId());
            }
        }
        Map<UUID, UserRefView> resolved = people.resolve(companyId, authorIds);

        List<MarkerView> markers = new ArrayList<>(marks.size());
        for (CaseFacts.VersionMark mark : marks) {
            markers.add(new MarkerView(
                    mark.versionNo(),
                    mark.occurredAt(),
                    // Null when the author cannot be named — suite_version.created_by is nullable and
                    // carries no foreign key to app_user, the same two real states §49 declared on
                    // the case timeline's markers.
                    mark.authorId() == null ? null : resolved.get(mark.authorId()),
                    mark.changeComment()));
        }
        return List.copyOf(markers);
    }
}
