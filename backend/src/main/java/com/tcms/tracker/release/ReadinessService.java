package com.tcms.tracker.release;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Completion;
import com.tcms.tracker.CountedIssue;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueStatusKind;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.IssueTypeView;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <em>"Can we ship 2.4?"</em> — every counted story done, <strong>and</strong> every linked test case
 * passing for that version. Story R4, and the question this product can answer because it owns both
 * halves.
 *
 * <h2>Live rows for everything except one number</h2>
 *
 * <p>The story arm, the blockers and the epics all read {@code issue}, {@code issue_type} and
 * {@code issue_status} <strong>directly</strong>. {@code issue_fact} is fed through the outbox, whose
 * delivery has a one-minute floor, and a screen whose whole purpose is to answer whether the work is
 * finished must not show a story as open for a minute after somebody closed it.
 *
 * <p><strong>One number comes from {@code issue_fact}</strong>: {@code scopeAddedAfterCreation},
 * because {@code release_assigned_at} exists nowhere else and cannot be reconstructed after the fact
 * (009 review R12). It can therefore lag the rest of the payload by a tick, which the screen says —
 * and the two never disagree about the same fact, because they answer different ones (plan 013 D-f).
 *
 * <h2>What "counted" means, and why a Task in a release counts for nothing</h2>
 *
 * <p>D8's set: <strong>story-level issues whose type counts toward completion</strong>. So a Spike
 * never blocks a release, exactly as it never moves an epic's percentage, and the count of excluded
 * ones is served so a total that does not match the backlog is explained rather than doubted.
 *
 * <p>An {@code EPIC}-level issue is excluded by the same flag — every epic type carries
 * {@code counts_toward_completion = false} — and epics appear in their own list instead. A
 * {@code TASK}-level issue pointed at a release is excluded too, and that one is a decision rather
 * than a consequence: a Task is a breakdown <em>of</em> a story, so counting both would count the
 * same work twice, and counting the task instead of the story would make a release's total change
 * depending on how finely somebody had broken it down.
 */
@Service
public class ReadinessService {

    private static final String READ = "release:read";

    /**
     * Every issue targeting one release, with its type and status. Tracker's own three tables, so
     * this is not a crossing and does not belong in {@link ReleaseFacts}.
     *
     * <p>Ordered by status position then key sequence, so the blockers table reads in workflow order
     * — not started, in progress, in review — which is the order somebody works down it.
     * {@code key_seq} and never the key string: {@code ACME-100} sorts before {@code ACME-2} as text.
     */
    private static final String SCOPE = """
            SELECT i.id, i.issue_key, i.title,
                   t.id, t.name, t.level, t.counts_toward_completion,
                   s.id, s.name, s.kind, s."position", s.blocking,
                   i.estimate_hours, i.start_date, i.target_date
              FROM issue            i
              JOIN issue_type       t ON t.id = i.type_id
              JOIN issue_status     s ON s.id = i.status_id
             WHERE i.project_id = :projectId
               AND i.release_id = :releaseId
               AND i.deleted_at IS NULL
             ORDER BY s."position", i.key_seq
            """;

    /**
     * The children of the epics in this release, flattened to what {@link Completion} needs.
     *
     * <p>An epic's children are not necessarily in the release — an epic can span two versions — so
     * this is keyed on the parent and not filtered by {@code release_id}. That is deliberate: an
     * epic's percentage is a property of the epic, and the roadmap and the overview must not show a
     * different one from the epic's own page.
     */
    private static final String EPIC_CHILDREN = """
            SELECT c.parent_id, ct.counts_toward_completion, cs.kind, c.estimate_hours
              FROM issue            c
              JOIN issue_type       ct ON ct.id = c.type_id
              JOIN issue_status     cs ON cs.id = c.status_id
             WHERE c.parent_id IN (:epicIds)
               AND c.deleted_at IS NULL
            """;

    /**
     * M9's burn-up scope line, counted once here.
     *
     * <p>{@code release_assigned_at} is stamped by {@code IssueFactProjection} at projection time
     * rather than at assignment time, so this is "assigned at least one outbox tick after the
     * release was created" — which is the number the chart wants and is accurate to within that
     * tick. There is no other source for it at all.
     */
    private static final String SCOPE_ADDED = """
            SELECT count(*)
              FROM issue_fact f
             WHERE f.project_id = :projectId
               AND f.release_id = :releaseId
               AND f.deleted_at IS NULL
               AND f.type_level = 'STORY'
               AND f.counts_toward_completion
               AND f.release_assigned_at > :createdAt
            """;

    private final com.tcms.project.ProjectService projects;
    private final ReleaseRepository releases;
    private final ReleaseFacts facts;
    private final VerificationLinkRepository links;
    private final PeopleDirectory people;
    private final EntityManager entities;

    ReadinessService(
            com.tcms.project.ProjectService projects,
            ReleaseRepository releases,
            ReleaseFacts facts,
            VerificationLinkRepository links,
            PeopleDirectory people,
            EntityManager entities) {

        this.projects = projects;
        this.releases = releases;
        this.facts = facts;
        this.links = links;
        this.people = people;
        this.entities = entities;
    }

    /** {@code GET /releases/{releaseId}/readiness}. */
    @Transactional(readOnly = true)
    public GateResult<ReadinessView> readiness(UUID userId, UUID releaseId) {
        Optional<Release> resolved = releases.findScopedById(releaseId);
        if (resolved.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.readGranted(userId, resolved.get().getProjectId(), READ, project ->
                fullReadinessOf(project.getId(), releases.findScopedById(releaseId).orElseThrow()));
    }

    /**
     * The row-sized answer for {@code GET /projects/{projectId}/releases}.
     *
     * <p>Package-private and called from {@link ReleaseService}'s already-gated list, which is why it
     * takes a project id rather than gating again: gating twice inside one request is how a second,
     * differently-worded rule appears.
     */
    ReadinessSummaryView summaryOf(UUID projectId, Release release) {
        Core core = coreOf(projectId, release);
        return new ReadinessSummaryView(
                core.verdict(), core.stories().done(), core.stories().total(),
                core.tests().linkedCases(), core.tests().hasRun(),
                core.tests().passed(), core.tests().failed());
    }

    /** Every release's verdict at once, for the roadmap, which colours a bar per release. */
    Map<UUID, ReleaseVerdict> verdictsOf(UUID projectId, List<Release> all) {
        Map<UUID, ReleaseVerdict> verdicts = new LinkedHashMap<>();
        for (Release release : all) {
            verdicts.put(release.getId(), coreOf(projectId, release).verdict());
        }
        return verdicts;
    }

    // =====================================================================

    /** The two halves and the verdict — everything both the list row and the full answer need. */
    private record Core(
            ReleaseStoriesView stories, ReleaseTestsView tests, ReleaseVerdict verdict,
            List<ScopeRow> rows, Map<UUID, ReleaseFacts.VersionResult> results,
            Map<UUID, ReleaseFacts.CaseRef> cases, Map<UUID, List<UUID>> casesByIssue) {
    }

    /** One issue targeting the release, flattened. */
    private record ScopeRow(
            UUID id, String key, String title, IssueTypeView type, IssueStatusView status,
            Double estimateHours, LocalDate startDate, LocalDate targetDate) {

        boolean isCountedStory() {
            return type.level() == IssueLevel.STORY && type.countsTowardCompletion();
        }

        boolean isExcludedStory() {
            return type.level() == IssueLevel.STORY && !type.countsTowardCompletion();
        }

        boolean isDone() {
            return status.kind() == IssueStatusKind.DONE;
        }
    }

    private Core coreOf(UUID projectId, Release release) {
        List<ScopeRow> rows = scopeOf(projectId, release.getId());

        List<ScopeRow> counted = rows.stream().filter(ScopeRow::isCountedStory).toList();
        int done = (int) counted.stream().filter(ScopeRow::isDone).count();
        int inProgress = (int) counted.stream()
                .filter(row -> row.status().kind() == IssueStatusKind.IN_PROGRESS).count();
        int notStarted = (int) counted.stream()
                .filter(row -> row.status().kind() == IssueStatusKind.NOT_STARTED).count();
        ReleaseStoriesView stories = new ReleaseStoriesView(
                done, counted.size(), inProgress, notStarted,
                (int) rows.stream().filter(ScopeRow::isExcludedStory).count());

        Map<UUID, List<UUID>> casesByIssue = linkedCasesOf(counted);
        Set<UUID> caseIds = new LinkedHashSet<>();
        casesByIssue.values().forEach(caseIds::addAll);

        Map<UUID, ReleaseFacts.CaseRef> cases = facts.casesOf(projectId, caseIds);
        // A case linked and then deleted stays in issue_link — there is no foreign key on
        // target_id and there cannot be one (scope §3) — so readiness counts what still exists
        // rather than a link to nothing. The link row is left alone: removing it here would make a
        // read path a write path.
        List<UUID> live = ReleaseFacts.known(caseIds, cases);
        Map<UUID, ReleaseFacts.VersionResult> results =
                facts.latestPerCaseForVersion(projectId, release.getVersion(), live);

        ReleaseTestsView tests = testsOf(projectId, release.getVersion(), live, results);
        return new Core(stories, tests, verdictOf(stories, tests), rows, results, cases,
                casesByIssue);
    }

    private ReleaseTestsView testsOf(
            UUID projectId, String version, List<UUID> live,
            Map<UUID, ReleaseFacts.VersionResult> results) {

        if (results.isEmpty()) {
            return ReleaseTestsView.noRun(live.size());
        }
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        for (ReleaseFacts.VersionResult result : results.values()) {
            if (result.countsAsPass()) {
                passed++;
            } else if ("BLOCKED".equals(result.statusKind())) {
                blocked++;
            } else {
                failed++;
            }
        }
        Optional<ReleaseFacts.RunRef> run = facts.latestRunForVersion(projectId, version, live);
        UserRef executor = run.map(ReleaseFacts.RunRef::executedBy)
                .map(id -> people.resolve(CompanyContext.require(), List.of(id)).get(id))
                .orElse(null);

        return new ReleaseTestsView(
                true, live.size(), results.size(), passed, failed, blocked,
                live.size() - results.size(),
                run.map(ReleaseFacts.RunRef::executedAt).orElse(null),
                run.map(ReleaseFacts.RunRef::runId).orElse(null),
                run.map(ReleaseFacts.RunRef::name).orElse(null),
                executor);
    }

    /**
     * D-d's three members, in the order that makes this module's acceptance criterion true.
     *
     * <p>{@code NOT_TESTED} wins over {@code READY} and loses to a story that is not done: nothing
     * outstanding <em>except proof</em> is a different state from nothing outstanding, and from work
     * that is not finished. A release with no run therefore says so instead of reporting zero
     * failures, which is what the null counts beside this exist to make unrepresentable.
     */
    private ReleaseVerdict verdictOf(ReleaseStoriesView stories, ReleaseTestsView tests) {
        boolean storiesDone = stories.done() == stories.total();
        if (!tests.hasRun()) {
            return storiesDone ? ReleaseVerdict.NOT_TESTED : ReleaseVerdict.NOT_READY;
        }
        if (!storiesDone || tests.failed() > 0 || tests.blocked() > 0 || tests.untested() > 0) {
            return ReleaseVerdict.NOT_READY;
        }
        return ReleaseVerdict.READY;
    }

    private ReadinessView fullReadinessOf(UUID projectId, Release release) {
        Core core = coreOf(projectId, release);
        return new ReadinessView(
                release.getId(), release.getVersion(), release.getStatus(), core.verdict(),
                core.stories(), core.tests(),
                blockersOf(core), epicsOf(projectId, release.getId(), core.rows()),
                scopeAddedAfterCreation(projectId, release));
    }

    /**
     * <em>What stands between here and shipping.</em>
     *
     * <p>A story is a blocker when it is not done, or when it is done and one of its linked cases
     * did not pass in the latest result for this version — the second is the case this whole module
     * exists for, and it is why the row carries the failing case keys rather than a red dot.
     *
     * <p><strong>An untested case blocks only once something has run.</strong> When nothing has,
     * every case is untested and every done story would be listed — which would contradict the
     * {@code NOT_TESTED} verdict beside it and turn "nobody has run this yet" into a list of
     * accusations.
     */
    private List<ReleaseBlockerView> blockersOf(Core core) {
        List<ReleaseBlockerView> blockers = new ArrayList<>();
        for (ScopeRow row : core.rows()) {
            if (!row.isCountedStory()) {
                continue;
            }
            List<UUID> caseIds = core.casesByIssue().getOrDefault(row.id(), List.of());
            int passed = 0;
            int failed = 0;
            int blocked = 0;
            int untested = 0;
            List<String> failedKeys = new ArrayList<>();
            for (UUID caseId : caseIds) {
                ReleaseFacts.CaseRef known = core.cases().get(caseId);
                if (known == null) {
                    continue;
                }
                ReleaseFacts.VersionResult result = core.results().get(caseId);
                if (result == null) {
                    untested++;
                } else if (result.countsAsPass()) {
                    passed++;
                } else if ("BLOCKED".equals(result.statusKind())) {
                    blocked++;
                } else {
                    failed++;
                    failedKeys.add(known.key());
                }
            }
            boolean unproven = core.tests().hasRun() && (failed > 0 || blocked > 0 || untested > 0);
            if (row.isDone() && !unproven) {
                continue;
            }
            blockers.add(new ReleaseBlockerView(
                    row.key(), row.title(), row.type(), row.status(),
                    passed, failed, blocked, untested, List.copyOf(failedKeys)));
        }
        return List.copyOf(blockers);
    }

    private List<RoadmapEpicView> epicsOf(UUID projectId, UUID releaseId, List<ScopeRow> rows) {
        List<ScopeRow> epics = rows.stream()
                .filter(row -> row.type().level() == IssueLevel.EPIC)
                .toList();
        if (epics.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> percent = completionOf(epics.stream().map(ScopeRow::id).toList());
        return epics.stream()
                .map(row -> new RoadmapEpicView(
                        row.key(), row.title(), row.startDate(), row.targetDate(), releaseId,
                        row.status().kind(), percent.get(row.id())))
                .toList();
    }

    /**
     * D8's percentage per epic, through {@link Completion} and never beside it.
     *
     * <p>"One number, one place" is not a style rule — this repository has shipped two contradictory
     * percentages on one screen, and a service that computed its own is how that happened.
     */
    Map<UUID, Integer> completionOf(Collection<UUID> epicIds) {
        Map<UUID, Integer> percent = new HashMap<>();
        if (epicIds.isEmpty()) {
            return percent;
        }
        Map<UUID, List<CountedIssue>> children = new HashMap<>();
        for (Object result : entities.createNativeQuery(EPIC_CHILDREN)
                .setParameter("epicIds", epicIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            children.computeIfAbsent(UUID.fromString(row[0].toString()), key -> new ArrayList<>())
                    .add(new CountedIssue(
                            (Boolean) row[1],
                            IssueStatusKind.valueOf((String) row[2]),
                            row[3] == null ? null : ((Number) row[3]).doubleValue()));
        }
        for (UUID epicId : epicIds) {
            List<CountedIssue> counted = children.get(epicId);
            // Null rather than zero for an epic that counts nothing: a 0% on an epic with no
            // stories claims no progress on work that does not exist yet.
            percent.put(epicId, counted == null ? null : Completion.of(counted).percent());
        }
        return percent;
    }

    private int scopeAddedAfterCreation(UUID projectId, Release release) {
        Object count = entities.createNativeQuery(SCOPE_ADDED)
                .setParameter("projectId", projectId)
                .setParameter("releaseId", release.getId())
                .setParameter("createdAt", java.sql.Timestamp.from(release.getCreatedAt()))
                .getSingleResult();
        return ((Number) count).intValue();
    }

    private Map<UUID, List<UUID>> linkedCasesOf(List<ScopeRow> counted) {
        Map<UUID, List<UUID>> byIssue = new LinkedHashMap<>();
        if (counted.isEmpty()) {
            return byIssue;
        }
        List<UUID> issueIds = counted.stream().map(ScopeRow::id).toList();
        for (VerificationLink link
                : links.findByTargetTypeAndIssueIdIn(VerificationLink.TEST_CASE, issueIds)) {
            byIssue.computeIfAbsent(link.getIssueId(), key -> new ArrayList<>())
                    .add(link.getTargetId());
        }
        return byIssue;
    }

    private List<ScopeRow> scopeOf(UUID projectId, UUID releaseId) {
        List<ScopeRow> rows = new ArrayList<>();
        for (Object result : entities.createNativeQuery(SCOPE)
                .setParameter("projectId", projectId)
                .setParameter("releaseId", releaseId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            rows.add(new ScopeRow(
                    UUID.fromString(row[0].toString()),
                    (String) row[1],
                    (String) row[2],
                    new IssueTypeView(
                            UUID.fromString(row[3].toString()),
                            (String) row[4],
                            IssueLevel.valueOf((String) row[5]),
                            (Boolean) row[6]),
                    new IssueStatusView(
                            UUID.fromString(row[7].toString()),
                            (String) row[8],
                            IssueStatusKind.valueOf((String) row[9]),
                            ((Number) row[10]).intValue(),
                            (Boolean) row[11]),
                    row[12] == null ? null : ((Number) row[12]).doubleValue(),
                    toLocalDate(row[13]),
                    toLocalDate(row[14])));
        }
        return List.copyOf(rows);
    }

    /**
     * {@code DATE} arrives as {@link java.sql.Date} through a native query, unlike the
     * {@link LocalDate} an entity mapping would hand back — {@code ReleaseFacts}' timestamp
     * conversion one column type over, and {@link RoadmapService} shares this one rather than
     * writing a second.
     */
    static LocalDate toLocalDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case java.sql.Date date -> date.toLocalDate();
            default -> throw new IllegalStateException("Unexpected date type " + value.getClass());
        };
    }
}
