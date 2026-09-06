package com.tcms.reporting.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeBand;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeReport;
import com.tcms.reporting.analytics.AnalyticsViews.MeasuredFrom;
import com.tcms.reporting.analytics.DeliveryFacts.CompletedIssue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cycle time <strong>per issue type</strong>, and the two things the report says about itself: what
 * it measured from, and whether there was enough of it to mean anything.
 */
class CycleTimeServiceTest {

    private static final UUID PROJECT = UUID.fromString("54b00000-0000-4000-8000-000000000001");
    private static final Clock NOW = Clock.fixed(
            Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("cycle time is reported per issue type, not as one aggregate")
    void bandsArePerType() {
        CycleTimeReport report = reportOver(List.of(
                new CompletedIssue("Bug", 3.1),
                new CompletedIssue("Bug", 4.0),
                new CompletedIssue("Story", 6.2),
                new CompletedIssue("Story", 8.0),
                new CompletedIssue("Story", 10.0),
                new CompletedIssue("Task", 1.8),
                new CompletedIssue("Task", 2.0)));

        assertThat(report.bands()).extracting(CycleTimeBand::typeName)
                .as("count descending, then alphabetical — deterministic between two identical runs")
                .containsExactly("Story", "Bug", "Task");
        assertThat(report.bands()).extracting(CycleTimeBand::count).containsExactly(3, 2, 2);
    }

    @Test
    @DisplayName("the report says it measured from creation, because nothing records a start")
    void measuredFromIsOnTheReport() {
        CycleTimeReport report = reportOver(fiveOf("Story"));

        assertThat(report.measuredFrom()).isEqualTo(MeasuredFrom.CREATED);
        assertThat(MeasuredFrom.values())
                .as("one member: STARTED is what this gains the day a status history exists, and an "
                        + "enum member nothing can produce is a promise rather than a fact")
                .hasSize(1);
    }

    @Test
    @DisplayName("the median is a duration some issue actually took — nearest rank, not interpolated")
    void medianIsNearestRank() {
        CycleTimeReport report = reportOver(List.of(
                new CompletedIssue("Story", 1.0),
                new CompletedIssue("Story", 2.0),
                new CompletedIssue("Story", 3.0),
                new CompletedIssue("Story", 4.0),
                new CompletedIssue("Story", 100.0)));

        CycleTimeBand story = report.bands().get(0);
        assertThat(story.medianDays())
                .as("an interpolated median of this set is also 3.0; what matters is that 3.0 names "
                        + "an issue somebody can go and look at")
                .isEqualTo(3.0);
        assertThat(story.p90Days()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("fewer than five completions answers sufficient:false with a reason, not a flat series")
    void tooLittleDataSaysSo() {
        CycleTimeReport report = reportOver(List.of(
                new CompletedIssue("Story", 6.2), new CompletedIssue("Task", 1.8)));

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.sufficiency().reason())
                .isEqualTo("Only 2 issues were completed in the last 90 days — "
                        + "too few to report a median.");
        assertThat(report.bands())
                .as("the bands are STILL served with their counts — the reader decides whether two "
                        + "issues is enough, they just are not handed a median as if it settled it")
                .hasSize(2);
    }

    @Test
    @DisplayName("one completion reads 'issue was', not 'issues were'")
    void theSentenceAgrees() {
        CycleTimeReport report = reportOver(List.of(new CompletedIssue("Story", 6.2)));

        assertThat(report.sufficiency().reason())
                .isEqualTo("Only 1 issue was completed in the last 90 days — "
                        + "too few to report a median.");
    }

    @Test
    @DisplayName("a project with nothing completed is insufficient rather than an empty success")
    void nothingCompleted() {
        CycleTimeReport report = reportOver(List.of());

        assertThat(report.sufficiency().sufficient()).isFalse();
        assertThat(report.bands()).isEmpty();
    }

    private static List<CompletedIssue> fiveOf(String typeName) {
        return List.of(
                new CompletedIssue(typeName, 1.0),
                new CompletedIssue(typeName, 2.0),
                new CompletedIssue(typeName, 3.0),
                new CompletedIssue(typeName, 4.0),
                new CompletedIssue(typeName, 5.0));
    }

    private static CycleTimeReport reportOver(List<CompletedIssue> completed) {
        AnalyticsFacts facts = new AnalyticsFacts().withCompleted(completed);
        return new CycleTimeService(null, facts, NOW)
                .compute(PROJECT, CycleTimeService.DEFAULT_WINDOW_DAYS);
    }
}
