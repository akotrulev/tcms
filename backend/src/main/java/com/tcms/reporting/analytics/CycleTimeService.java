package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeBand;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeReport;
import com.tcms.reporting.analytics.AnalyticsViews.MeasuredFrom;
import com.tcms.reporting.analytics.AnalyticsViews.Sufficiency;
import com.tcms.reporting.analytics.DeliveryFacts.CompletedIssue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long issues take to finish, <strong>per type and never as one aggregate</strong>.
 *
 * <p>A median over stories and tasks together is a number about neither — the mockup's four rows are
 * four different questions, and the answer to "how long does a story take" is not improved by
 * averaging it with the tasks underneath it.
 *
 * <h2>Measured from creation, and the response says so</h2>
 *
 * <p>{@link MeasuredFrom#CREATED} (020 D-c). This product records no moment at which work started:
 * {@code issue_transition} is the workflow allow-list, not a history, so {@code status_entered_at}
 * on a completed issue is when it entered the <em>Done</em> status. The strict name for what this
 * measures is <em>lead time</em>; calling it "started to done" would be the class of defect
 * {@code CLAUDE.md} records as two contradictory percentages on one screen — a number whose label
 * says it is a different number.
 */
@Service
public class CycleTimeService {

    private static final String VIEW_REPORT = "report:view";

    /** Below this many completions in the whole window, a median is a coincidence. */
    private static final int ENOUGH = 5;

    /** The windows the contract offers, and the fallback when none is named. */
    static final int DEFAULT_WINDOW_DAYS = 90;

    private final ProjectService projects;
    private final DeliveryFacts facts;
    private final Clock clock;

    CycleTimeService(ProjectService projects, DeliveryFacts facts, Clock clock) {
        this.projects = projects;
        this.facts = facts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GateResult<CycleTimeReport> of(UUID userId, UUID projectId, int windowDays) {
        return projects.readGranted(
                userId, projectId, VIEW_REPORT, project -> compute(project.getId(), windowDays));
    }

    /** The computation, without the gate — the seam the unit tests drive. */
    CycleTimeReport compute(UUID projectId, int windowDays) {
        Instant since = clock.instant().minus(Duration.ofDays(windowDays));
        List<CompletedIssue> completed = facts.completedSince(projectId, since);

        // LinkedHashMap and not groupingBy: the rows arrive ordered by type_name, so the grouping
        // is stable before the sort below reorders it — which makes a test that pins the order
        // pinning the sort rather than a HashMap's iteration.
        Map<String, List<Double>> byType = new LinkedHashMap<>();
        for (CompletedIssue issue : completed) {
            byType.computeIfAbsent(issue.typeName(), ignored -> new ArrayList<>())
                    .add(issue.elapsedDays());
        }

        List<CycleTimeBand> bands = new ArrayList<>(byType.size());
        byType.forEach((typeName, days) -> bands.add(new CycleTimeBand(
                typeName, days.size(), percentile(days, 50), percentile(days, 90))));

        // Biggest population first, then alphabetical. Deterministic, because a report whose row
        // order changes between two identical requests is one nobody trusts — §6e's own bullet.
        bands.sort(Comparator.comparingInt(CycleTimeBand::count).reversed()
                .thenComparing(CycleTimeBand::typeName));

        return new CycleTimeReport(
                MeasuredFrom.CREATED, windowDays, bands, sufficiencyOf(completed.size(), windowDays));
    }

    /**
     * Thin is an answer, and the bands are still served (020 D-e).
     *
     * <p>The reader decides whether four issues is enough to act on; what they must not be given is
     * a median presented as if it settled the question. So the bands go out with their counts and
     * the report says the window was thin.
     */
    private static Sufficiency sufficiencyOf(int completed, int windowDays) {
        if (completed < ENOUGH) {
            return Sufficiency.no(
                    "Only " + completed + (completed == 1 ? " issue was" : " issues were")
                            + " completed in the last " + windowDays
                            + " days — too few to report a median.",
                    completed);
        }
        return Sufficiency.ok(completed);
    }

    /**
     * Nearest-rank percentile, over a sorted copy.
     *
     * <p>Nearest-rank rather than interpolated, deliberately: every value in the result is a
     * duration some issue actually took, so <em>"the p90 is 14 days"</em> names an issue somebody
     * can go and look at. An interpolated 13.7 names nothing, and the first question anyone asks of
     * a cycle-time outlier is which one it was.
     */
    private static double percentile(List<Double> values, int percentile) {
        List<Double> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
