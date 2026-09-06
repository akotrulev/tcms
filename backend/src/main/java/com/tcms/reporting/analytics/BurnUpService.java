package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpPoint;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpReport;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseBaseline;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseIssue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <em>"Are we converging on 2.4?"</em> — done against total scope over time, with the step where
 * scope arrived after the release was created.
 *
 * <h2>That step is the point of the chart</h2>
 *
 * <p>A burn-up whose denominator grows silently reports a team going backwards for no visible
 * reason. This one shows where the scope came from, and {@code scopeAddedAfterCreation} says how
 * much — the one number in the figure that cannot be reconstructed after the fact (009 review R12),
 * because {@code release_assigned_at} exists nowhere else.
 *
 * <p>The counted set is {@code DeliveryFacts}' {@code COUNTED}, which is the release readiness
 * page's predicate (020 D-f). Two screens must not report different "scope added" figures for one
 * release, and they are kept identical by sharing the predicate rather than by a comment.
 *
 * <h2>The gate is {@code readGranted}, not {@code readVisible}</h2>
 *
 * <p>{@link GateResult}'s own javadoc names {@code report:view} as the first permission that
 * distinction was built for. A caller who can see the project but does not hold the key gets 403,
 * because 404 would tell somebody plainly looking at the project that their report had vanished; a
 * caller who cannot see the project gets 404, because telling those two apart makes the endpoint an
 * oracle for another company's tenancy.
 */
@Service
public class BurnUpService {

    private static final String VIEW_REPORT = "report:view";

    private final ProjectService projects;
    private final DeliveryFacts facts;
    private final Clock clock;

    BurnUpService(ProjectService projects, DeliveryFacts facts, Clock clock) {
        this.projects = projects;
        this.facts = facts;
        this.clock = clock;
    }

    /**
     * The burn-up for one release, or empty inside {@code Allowed} when the id names nothing here.
     *
     * <p>Empty rather than an exception: an exception thrown inside the gate's function unwinds the
     * read-only transaction the gate opened, and the controller collapses an empty {@code Allowed}
     * to the same bodiless 404 an unreachable project gets — which is what §6t requires anyway.
     */
    @Transactional(readOnly = true)
    public GateResult<Optional<BurnUpReport>> of(UUID userId, UUID projectId, UUID releaseId) {
        return projects.readGranted(
                userId, projectId, VIEW_REPORT, project -> compute(project.getId(), releaseId));
    }

    /**
     * The computation, without the gate — the seam the unit tests drive.
     *
     * <p>Package-private rather than private for exactly that: the gate is
     * {@code ProjectService}'s and is tested where it lives, and a unit test that had to stand up a
     * permission evaluator to assert an arithmetic property would be testing the wrong thing twice.
     */
    Optional<BurnUpReport> compute(UUID projectId, UUID releaseId) {
        return facts.release(projectId, releaseId).map(release -> {
            List<ReleaseIssue> scope = facts.releaseScope(projectId, releaseId);
            return report(release, scope);
        });
    }

    private BurnUpReport report(ReleaseBaseline release, List<ReleaseIssue> scope) {
        int counted = scope.size();
        int done = (int) scope.stream().filter(issue -> issue.doneAt() != null).count();
        int addedLate = (int) scope.stream().filter(issue -> addedLate(issue, release)).count();

        Sufficiency sufficiency = sufficiencyOf(counted, addedLate);
        List<BurnUpPoint> points = sufficiency.sufficient()
                ? weekly(release.createdAt(), scope)
                : List.of();

        return new BurnUpReport(
                release.releaseId(),
                release.version(),
                release.createdAt(),
                counted,
                done,
                addedLate,
                points,
                sufficiency);
    }

    /**
     * Assigned <strong>strictly after</strong> the release existed.
     *
     * <p>Strictly, so an issue assigned in the same instant is original scope — matching
     * {@code ReadinessService.SCOPE_ADDED}'s {@code >} exactly. {@code release_assigned_at} is
     * stamped by {@code IssueFactProjection} at projection time rather than at assignment time, one
     * outbox tick later with a one-minute floor, so this reads as "assigned at least one tick after
     * the release row existed". There is no other source for it at all, and the tick is stated here
     * rather than corrected for, because correcting would need the assignment time — the thing that
     * does not exist.
     */
    private static boolean addedLate(ReleaseIssue issue, ReleaseBaseline release) {
        return issue.assignedAt() != null && issue.assignedAt().isAfter(release.createdAt());
    }

    /**
     * Two ways this report has nothing to draw, and both answer 200 (020 D-e).
     *
     * <p>The second is the imported-project case (020 D-n): the Jira importer creates the release
     * row and <em>then</em> imports, so every imported issue's {@code release_assigned_at} lands
     * after {@code release.created_at} and the predicate counts all of it as late. That is literally
     * true and completely useless — it renders a project that added 100% of its scope after planning
     * — so the report says what happened instead of drawing it.
     */
    private static Sufficiency sufficiencyOf(int counted, int addedLate) {
        if (counted == 0) {
            return Sufficiency.no("No story-level issue is targeting this release yet.", 0);
        }
        if (addedLate == counted) {
            return Sufficiency.no(
                    "Every issue was assigned after this release was created — the original-scope "
                            + "baseline is the import, not a plan.",
                    counted);
        }
        return Sufficiency.ok(counted);
    }

    /**
     * One point per week from the release's creation to today, with today always the last.
     *
     * <p>Folded in Java over rows already in memory rather than as one query per week: the
     * population is one release's counted issues, which is tens.
     *
     * <p>The trailing partial week is appended so the right-hand edge is today rather than up to six
     * days ago — a burn-up that stops before now is one a reader distrusts, and it is the reading
     * they came for.
     */
    private List<BurnUpPoint> weekly(Instant from, List<ReleaseIssue> scope) {
        LocalDate start = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (today.isBefore(start)) {
            // A release created in the future is not a state the product can reach, but a clock
            // skewed backwards is, and a loop that never runs would answer an empty series where
            // the sufficiency says there is one.
            return List.of(pointAt(start, scope));
        }

        List<BurnUpPoint> points = new ArrayList<>();
        for (LocalDate at = start; !at.isAfter(today); at = at.plusWeeks(1)) {
            points.add(pointAt(at, scope));
        }
        if (points.get(points.size() - 1).date().isBefore(today)) {
            points.add(pointAt(today, scope));
        }
        return points;
    }

    /** Both counts as at the end of {@code at}, so an issue done that day is done on that point. */
    private static BurnUpPoint pointAt(LocalDate at, List<ReleaseIssue> scope) {
        Instant end = at.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int total = 0;
        int done = 0;
        for (ReleaseIssue issue : scope) {
            if (issue.assignedAt() == null || !issue.assignedAt().isBefore(end)) {
                continue;
            }
            total++;
            if (issue.doneAt() != null && issue.doneAt().isBefore(end)) {
                done++;
            }
        }
        return new BurnUpPoint(at, done, total);
    }
}
