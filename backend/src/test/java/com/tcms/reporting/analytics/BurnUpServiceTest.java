package com.tcms.reporting.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.reporting.analytics.AnalyticsViews.BurnUpPoint;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpReport;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseBaseline;
import com.tcms.reporting.analytics.DeliveryFacts.ReleaseIssue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The burn-up's arithmetic, and the one property the whole module exists for: <strong>scope added
 * after the release was created is reported apart from the scope that was there when it was
 * planned</strong> (009 review R12, plan 020 D-f).
 *
 * <p>No Spring context and no database. Every assertion here is about a fold over rows, and the
 * gate it runs behind belongs to {@code ProjectService} and is tested where it lives.
 */
class BurnUpServiceTest {

    private static final UUID PROJECT = UUID.fromString("54a00000-0000-4000-8000-000000000001");
    private static final UUID RELEASE = UUID.fromString("54a00000-0000-4000-8000-000000000002");

    private static final Instant CREATED = Instant.parse("2026-07-01T09:00:00Z");
    private static final Clock NOW = Clock.fixed(
            Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("scope added after the release was created is reported apart from original scope")
    void scopeAddedLateIsSeparate() {
        BurnUpReport report = reportOver(List.of(
                // Assigned before the release row existed — a release created around work already
                // targeted at it, which is what happens when somebody plans the version afterwards.
                issue(CREATED.minus(Duration.ofHours(1)), null),
                // Assigned in the SAME instant. Original scope: the comparison is strictly after,
                // matching ReadinessService.SCOPE_ADDED's `>`, so the two screens agree.
                issue(CREATED, null),
                // Five minutes later. LATE, and correctly so — release_assigned_at is stamped at
                // projection time, one outbox tick after the assignment, so a five-minute gap is a
                // real assignment made after the release existed rather than the tick.
                issue(CREATED.plus(Duration.ofMinutes(5)), null),
                issue(CREATED.plus(Duration.ofDays(42)), null)));

        assertThat(report.countedTotal()).isEqualTo(4);
        assertThat(report.scopeAddedAfterCreation())
                .as("strictly after, and the tick is not corrected for")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the total-scope series steps on the week the late scope arrived")
    void theSeriesStepsWhereScopeArrived() {
        Instant late = Instant.parse("2026-08-12T10:00:00Z");
        BurnUpReport report = reportOver(List.of(
                issue(CREATED, Instant.parse("2026-07-20T10:00:00Z")),
                issue(CREATED, null),
                issue(late, null)));

        LocalDate before = LocalDate.parse("2026-08-05");
        LocalDate after = LocalDate.parse("2026-08-19");

        assertThat(totalOn(report, before))
                .as("the late issue is not in scope before it was assigned")
                .isEqualTo(2);
        assertThat(totalOn(report, after)).isEqualTo(3);
        assertThat(report.points().get(report.points().size() - 1).date())
                .as("the right-hand edge is today, not up to six days ago")
                .isEqualTo(LocalDate.parse("2026-09-02"));
    }

    @Test
    @DisplayName("done counts an issue from the day it was finished, never before")
    void doneCountsFromTheDayItFinished() {
        BurnUpReport report = reportOver(List.of(
                issue(CREATED, Instant.parse("2026-08-12T23:59:00Z"))));

        assertThat(doneOn(report, LocalDate.parse("2026-08-05"))).isZero();
        assertThat(doneOn(report, LocalDate.parse("2026-08-12")))
                .as("end of that day, so an issue finished at 23:59 is done on its own point")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a release nothing targets answers sufficient:false with a reason, not a series")
    void nothingTargetingIsAnAnswer() {
        BurnUpReport report = reportOver(List.of());

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.sufficiency().reason())
                .isEqualTo("No story-level issue is targeting this release yet.");
        assertThat(report.points())
                .as("a zero-valued series is the flat line this design refuses")
                .isEmpty();
    }

    @Test
    @DisplayName("an imported release — every issue assigned late — says so rather than drawing it")
    void everythingAssignedLateIsAnAnswer() {
        // The Jira importer creates the release row and THEN imports, so this is every imported
        // project rather than a contrived case (020 D-n).
        BurnUpReport report = reportOver(List.of(
                issue(CREATED.plus(Duration.ofMinutes(1)), null),
                issue(CREATED.plus(Duration.ofMinutes(1)), null),
                issue(CREATED.plus(Duration.ofMinutes(2)), null)));

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.sufficiency().reason())
                .isEqualTo("Every issue was assigned after this release was created — the "
                        + "original-scope baseline is the import, not a plan.");
        assertThat(report.scopeAddedAfterCreation())
                .as("still counted and still served — the figure is true, the chart is not useful")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a release id that names nothing in this project is empty, not an exception")
    void unknownReleaseIsEmpty() {
        BurnUpService service = new BurnUpService(
                null, new AnalyticsFacts().withRelease(null), NOW);

        assertThat(service.compute(PROJECT, RELEASE)).isEmpty();
    }

    private static BurnUpReport reportOver(List<ReleaseIssue> scope) {
        AnalyticsFacts facts = new AnalyticsFacts()
                .withRelease(new ReleaseBaseline(RELEASE, "2.4", CREATED))
                .withScope(scope);
        Optional<BurnUpReport> report = new BurnUpService(null, facts, NOW).compute(PROJECT, RELEASE);
        assertThat(report).isPresent();
        return report.orElseThrow();
    }

    private static ReleaseIssue issue(Instant assignedAt, Instant doneAt) {
        return new ReleaseIssue(assignedAt, doneAt);
    }

    /** The last point on or before {@code on} — the value a chart would draw at that date. */
    private static int totalOn(BurnUpReport report, LocalDate on) {
        return pointOn(report, on).totalScope();
    }

    private static int doneOn(BurnUpReport report, LocalDate on) {
        return pointOn(report, on).done();
    }

    private static BurnUpPoint pointOn(BurnUpReport report, LocalDate on) {
        return report.points().stream()
                .filter(point -> !point.date().isAfter(on))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
