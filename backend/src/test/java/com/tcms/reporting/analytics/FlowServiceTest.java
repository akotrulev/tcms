package com.tcms.reporting.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowPoint;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowReport;
import com.tcms.reporting.analytics.DeliveryFacts.FlowIssue;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cumulative flow, and the fourth band that keeps it honest.
 *
 * <p>Everything asserted here follows from three timestamps and a current status — which is all
 * {@code issue_fact} carries, and the whole reason the fourth band exists (020 D-d).
 */
class FlowServiceTest {

    private static final UUID PROJECT = UUID.fromString("54c00000-0000-4000-8000-000000000001");

    /** A Wednesday, so nothing in these fixtures depends on where a week boundary happens to fall. */
    private static final Instant TODAY = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock NOW = Clock.fixed(TODAY, ZoneOffset.UTC);

    private static final Instant LONG_AGO = Instant.parse("2026-01-05T09:00:00Z");

    @Test
    @DisplayName("an issue whose current status was entered after the boundary lands in the fourth band")
    void unknownStatusIsItsOwnBand() {
        // Created in January, moved into its current status yesterday. Where was it in June?
        // Nothing in this product knows, and the chart must not guess NOT_STARTED.
        FlowIssue movedRecently = new FlowIssue(
                LONG_AGO, TODAY.minusSeconds(86_400), null, "IN_PROGRESS");

        CumulativeFlowReport report = reportOver(List.of(movedRecently), 12);

        CumulativeFlowPoint oldest = report.points().get(0);
        assertThat(oldest.beforeCurrentStatus()).isEqualTo(1);
        assertThat(oldest.notStarted())
                .as("folding the unknown into not-started would draw a guess as data")
                .isZero();
        assertThat(oldest.inFlight()).isZero();
    }

    @Test
    @DisplayName("the fourth band is empty at the newest point, by construction")
    void theFourthBandIsEmptyAtTheEnd() {
        CumulativeFlowReport report = reportOver(List.of(
                new FlowIssue(LONG_AGO, TODAY.minusSeconds(86_400), null, "IN_PROGRESS"),
                new FlowIssue(LONG_AGO, TODAY.minusSeconds(3_600), null, "NOT_STARTED")), 12);

        CumulativeFlowPoint newest = report.points().get(report.points().size() - 1);
        assertThat(newest.beforeCurrentStatus())
                .as("every issue has entered its current status by now — that is what 'current' means")
                .isZero();
        assertThat(newest.inFlight()).isEqualTo(1);
        assertThat(newest.notStarted()).isEqualTo(1);
    }

    @Test
    @DisplayName("done is claimed from done_at and survives a reopen")
    void doneIsClaimedFromDoneAt() {
        // Reached Done in July, reopened since — status_kind is IN_PROGRESS and done_at still says
        // July, because done_at is FIRST reached done and is never cleared.
        FlowIssue reopened = new FlowIssue(
                LONG_AGO, TODAY.minusSeconds(86_400),
                Instant.parse("2026-07-15T10:00:00Z"), "IN_PROGRESS");

        CumulativeFlowReport report = reportOver(List.of(reopened), 26);

        assertThat(bandOn(report, LocalDate.parse("2026-08-05")).done())
                .as("it genuinely was done on that date")
                .isEqualTo(1);
        assertThat(bandOn(report, LocalDate.parse("2026-08-05")).inFlight()).isZero();
    }

    @Test
    @DisplayName("an issue created after a point is absent from it, not counted as not-started")
    void anIssueIsAbsentBeforeItExists() {
        FlowIssue born = new FlowIssue(
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"), null, "NOT_STARTED");

        CumulativeFlowReport report = reportOver(List.of(born), 12);
        CumulativeFlowPoint oldest = report.points().get(0);

        assertThat(oldest.notStarted() + oldest.inFlight() + oldest.done()
                + oldest.beforeCurrentStatus()).isZero();
    }

    @Test
    @DisplayName("more than a third unknown at the oldest point answers sufficient:false with a reason")
    void tooMuchUnknownSaysSo() {
        List<FlowIssue> population = List.of(
                new FlowIssue(LONG_AGO, TODAY.minusSeconds(3_600), null, "IN_PROGRESS"),
                new FlowIssue(LONG_AGO, TODAY.minusSeconds(3_600), null, "IN_PROGRESS"),
                new FlowIssue(LONG_AGO, LONG_AGO, null, "NOT_STARTED"));

        CumulativeFlowReport report = reportOver(population, 12);

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.sufficiency().reason())
                .isEqualTo("More than a third of the issues at the start of this window have no "
                        + "recorded status for that date.");
        assertThat(report.points())
                .as("the points ARE still served, unlike the burn-up's — the reason is about their "
                        + "shape, so a client may render the sentence over a greyed chart")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a project with no issues is insufficient rather than a chart of zeroes")
    void noIssuesAtAll() {
        CumulativeFlowReport report = reportOver(List.of(), 12);

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.sufficiency().reason()).isEqualTo("This project has no issues yet.");
    }

    @Test
    @DisplayName("the window is the number of weekly points, ending today")
    void theWindowIsTheNumberOfPoints() {
        CumulativeFlowReport report = reportOver(
                List.of(new FlowIssue(LONG_AGO, LONG_AGO, null, "NOT_STARTED")), 8);

        assertThat(report.windowWeeks()).isEqualTo(8);
        assertThat(report.points()).hasSize(8);
        assertThat(report.points().get(7).date()).isEqualTo(LocalDate.parse("2026-09-02"));
    }

    private static CumulativeFlowReport reportOver(List<FlowIssue> population, int weeks) {
        AnalyticsFacts facts = new AnalyticsFacts().withPopulation(population);
        return new FlowService(null, facts, NOW).compute(PROJECT, weeks);
    }

    /** The point on or before {@code on} — the column a chart would draw at that date. */
    private static CumulativeFlowPoint bandOn(CumulativeFlowReport report, LocalDate on) {
        return report.points().stream()
                .filter(point -> !point.date().isAfter(on))
                .reduce((first, second) -> second)
                .orElseThrow();
    }
}
