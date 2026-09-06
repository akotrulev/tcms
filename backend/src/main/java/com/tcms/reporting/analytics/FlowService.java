package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowPoint;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowReport;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.FlowIssue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues per status band, weekly — and the fourth band, which is the one that makes it honest.
 *
 * <h2>Why there are four bands and not the mockup's three (020 D-d)</h2>
 *
 * <p>At a past date {@code T}, an issue's band is knowable only when one of two things holds: it had
 * already reached done ({@code done_at <= T}, and {@code done_at} is <em>first</em> reached done,
 * never cleared by a reopen), or it was already in the status it is in now
 * ({@code status_entered_at <= T}). Otherwise it existed and this product does not know where it
 * was, because {@code issue_transition} is the workflow allow-list rather than a log of moves made.
 *
 * <p>Folding that into {@code notStarted} would draw a guess as data, and it would be
 * <em>systematically</em> wrong in one direction: the further back the chart looks, the more of the
 * "not started" band would be invention. So it is its own band — zero at the newest point by
 * construction, growing leftward — which is also, at no extra cost, this figure's too-little-data
 * signal.
 *
 * <p><strong>A status-history table would fix this properly and M9 does not add one.</strong> The
 * module takes no migration by decision (020 D-a), and the fix is named rather than left to be
 * rediscovered: {@code issue_status_history (issue_id, status_id, entered_at)}, written by
 * {@code IssueFactProjection}, would shrink this band to nothing going forward and would still be
 * empty for every date before the day it landed.
 */
@Service
public class FlowService {

    private static final String VIEW_REPORT = "report:view";

    /** The fallback window when none is named. */
    static final int DEFAULT_WINDOW_WEEKS = 12;

    /** Above this share of unknowns at the oldest point, the report says so instead of drawing. */
    private static final double TOO_MUCH_UNKNOWN = 1.0 / 3.0;

    /** {@code issue_status.kind}'s middle member — the only one that reads as in flight. */
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final ProjectService projects;
    private final DeliveryFacts facts;
    private final Clock clock;

    FlowService(ProjectService projects, DeliveryFacts facts, Clock clock) {
        this.projects = projects;
        this.facts = facts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GateResult<CumulativeFlowReport> of(UUID userId, UUID projectId, int windowWeeks) {
        return projects.readGranted(
                userId, projectId, VIEW_REPORT, project -> compute(project.getId(), windowWeeks));
    }

    /** The computation, without the gate — the seam the unit tests drive. */
    CumulativeFlowReport compute(UUID projectId, int windowWeeks) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = today.minusWeeks(windowWeeks - 1L);
        List<FlowIssue> population = facts.flowPopulation(projectId, endOf(today));

        List<CumulativeFlowPoint> points = new ArrayList<>(windowWeeks);
        for (LocalDate at = start; !at.isAfter(today); at = at.plusWeeks(1)) {
            points.add(pointAt(at, population));
        }

        return new CumulativeFlowReport(
                windowWeeks, points, sufficiencyOf(points.get(0), population.size()));
    }

    /**
     * Every issue that existed at the end of {@code at}, in the one band that can be known for it.
     *
     * <p>The order of the three tests is the decision: done first, because {@code done_at} survives
     * a reopen and a reopened issue was genuinely done on the earlier date; then the current status,
     * which is only claimable once the issue had entered it; then the unknown.
     */
    private static CumulativeFlowPoint pointAt(LocalDate at, List<FlowIssue> population) {
        Instant end = endOf(at);
        int notStarted = 0;
        int inFlight = 0;
        int done = 0;
        int unknown = 0;

        for (FlowIssue issue : population) {
            if (!issue.createdAt().isBefore(end)) {
                continue;
            }
            if (issue.doneAt() != null && issue.doneAt().isBefore(end)) {
                done++;
            } else if (issue.statusEnteredAt() != null && issue.statusEnteredAt().isBefore(end)) {
                // The kind is CHECK-constrained to NOT_STARTED / IN_PROGRESS / DONE. A DONE-kind
                // issue reaching here is one whose done_at is later than this point — a reopen, or
                // the projection's tick — and it belongs on the not-started side of that date
                // rather than in a fourth arm, because it demonstrably was not finished then.
                if (IN_PROGRESS.equals(issue.statusKind())) {
                    inFlight++;
                } else {
                    notStarted++;
                }
            } else {
                unknown++;
            }
        }
        return new CumulativeFlowPoint(at, notStarted, inFlight, done, unknown);
    }

    private static Sufficiency sufficiencyOf(CumulativeFlowPoint oldest, int considered) {
        if (considered == 0) {
            return Sufficiency.no("This project has no issues yet.", 0);
        }
        int atOldest = oldest.notStarted() + oldest.inFlight() + oldest.done()
                + oldest.beforeCurrentStatus();
        if (atOldest > 0
                && (double) oldest.beforeCurrentStatus() / atOldest > TOO_MUCH_UNKNOWN) {
            return Sufficiency.no(
                    "More than a third of the issues at the start of this window have no recorded "
                            + "status for that date.",
                    considered);
        }
        return Sufficiency.ok(considered);
    }

    /** The instant just after {@code at} ends, so an issue moved that day counts on that point. */
    private static Instant endOf(LocalDate at) {
        return at.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
